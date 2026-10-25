#!/usr/bin/env bash
# Releases one version to the EC2 server (user decision 2026-10-18: semantic versions from v1.0.0, with the commit
# hash). Run on the PC from Git Bash, in the repository:
#
#   deploy/prod/bin/release.sh 1.0.1 [service ...]     services default to all (db backend web cloudflared)
#
# 1. Stops unless: on main, nothing uncommitted, X.Y.Z is new (no tag vX.Y.Z), CHANGELOG.md has a "## vX.Y.Z" part.
# 2. Writes X.Y.Z into backend/build.gradle, web/package.json and the image lines of deploy/prod/compose.yaml,
#    commits "Release vX.Y.Z" and tags it vX.Y.Z (annotated). The jar's build-info then carries that commit.
# 3. Builds the jar and web/dist, then arm64 images (WSL docker buildx) tagged X.Y.Z and sha-<commit>.
# 4. Copies the images and compose.yaml to the server, loads them and runs bin/up.sh there. Then removes the server's
#    older chaekcheck/backend and chaekcheck/web images, keeping this version and the one before it (the newest other
#    vX.Y.Z tag) for going back (user 2026-10-25); an image a container still uses is kept, and dangling layers go.
# 5. Pushes main and the tag, and makes the GitHub release with that CHANGELOG part (gh).
# A failure stops the script; what is done stays done (see deploy/prod/README.md "실패하면").
set -euo pipefail

VERSION="${1:-}"
shift || true
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: release.sh X.Y.Z [service ...]" >&2; exit 1; }
TAG="v$VERSION"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
SERVER="${CHAEKCHECK_SERVER:-ubuntu@52.64.117.112}"
KEY="${CHAEKCHECK_SSH_KEY:-$ROOT/.secrets/chaekcheck-key.pem}"
REMOTE_DIR=/srv/chaekcheck
# the repository as WSL sees it (Git Bash /c/... -> /mnt/c/...)
WSL_ROOT="$(pwd | sed -E 's#^/([a-zA-Z])/#/mnt/\L\1/#')"

stop() { echo "release.sh: $*" >&2; exit 1; }

# The server-side cleanup of step 4 for versions $1 (now) and $2 (before): image ids to keep are those two versions'
# backend and web images and every image a container (running or stopped) uses; other chaekcheck/backend and
# chaekcheck/web tags are removed one by one (the last tag of an image deletes it), then dangling layers.
prune_script() {
	cat <<SH
keep=\$( { for repo in chaekcheck/backend chaekcheck/web; do for version in $1 $2; do
  docker image inspect --format '{{.Id}}' \$repo:\$version 2>/dev/null; done; done
  docker ps -aq | xargs -r docker inspect --format '{{.Image}}'; } | sort -u)
docker image ls --no-trunc --format '{{.Repository}}:{{.Tag}} {{.ID}}' | grep -E '^chaekcheck/(backend|web):' \
  | while read -r ref id; do
    if ! printf '%s\n' "\$keep" | grep -qx "\$id"; then docker rmi "\$ref" >/dev/null && echo "   removed \$ref"; fi
  done
docker image prune -f >/dev/null
df -h / | tail -1
SH
}

[[ "$(git branch --show-current)" == main ]] || stop "not on main"
[[ -z "$(git status --porcelain)" ]] || stop "uncommitted changes; commit or stash them first"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && stop "tag $TAG exists already"
grep -q "^## $TAG" CHANGELOG.md || stop "CHANGELOG.md has no '## $TAG' part"
[[ -f "$KEY" ]] || stop "no SSH key at $KEY"

echo "== 1/5 version $VERSION into the build files"
sed -i -E "s/^version = '[^']*'/version = '$VERSION'/" backend/build.gradle
sed -i -E "0,/\"version\": \"[^\"]*\"/s//\"version\": \"$VERSION\"/" web/package.json
sed -i -E "s#(image: chaekcheck/(backend|web)):.*#\1:$VERSION#" deploy/prod/compose.yaml
git add backend/build.gradle web/package.json deploy/prod/compose.yaml
git diff --cached --quiet || git commit -q -m "Release $TAG"
git tag -a "$TAG" -m "Release $TAG"
COMMIT="$(git rev-parse --short HEAD)"
echo "   $TAG = $COMMIT"

echo "== 2/5 jar and web build"
(cd backend && ./gradlew bootJar -q)
(cd web && npm run build >/dev/null)

echo "== 3/5 arm64 images $VERSION and sha-$COMMIT"
wsl -d Ubuntu -- bash -ec "
  cd '$WSL_ROOT'
  docker buildx build --platform linux/arm64 -t chaekcheck/backend:$VERSION -t chaekcheck/backend:sha-$COMMIT --load backend >/dev/null
  docker buildx build --platform linux/arm64 -t chaekcheck/web:$VERSION -t chaekcheck/web:sha-$COMMIT -f deploy/prod/web.Dockerfile --load . >/dev/null
  mkdir -p ~/chaekcheck-release
  docker save chaekcheck/backend:$VERSION chaekcheck/backend:sha-$COMMIT chaekcheck/web:$VERSION chaekcheck/web:sha-$COMMIT \
    --platform linux/arm64 | gzip -1 > ~/chaekcheck-release/images-$VERSION.tar.gz"
ARCHIVE="//wsl.localhost/Ubuntu$(wsl -d Ubuntu -- bash -c 'echo ~' | tr -d '')/chaekcheck-release/images-$VERSION.tar.gz"

echo "== 4/5 server: load and start (${*:-all services})"
scp -q -i "$KEY" "$ARCHIVE" "$SERVER:$REMOTE_DIR/images-$VERSION.tar.gz"
scp -q -i "$KEY" deploy/prod/compose.yaml "$SERVER:$REMOTE_DIR/compose.yaml"
scp -q -i "$KEY" deploy/prod/bin/up.sh "$SERVER:$REMOTE_DIR/bin/up.sh"
ssh -i "$KEY" "$SERVER" "cd $REMOTE_DIR && gunzip -c images-$VERSION.tar.gz | docker load >/dev/null \
  && rm -f images-$VERSION.tar.gz && chmod +x bin/up.sh && bin/up.sh $*"

# the version before this one, kept on the server for going back
PREVIOUS="$(git tag --list 'v*' --sort=-v:refname | grep -vx "$TAG" | head -1 | sed 's/^v//')"
echo "   server images: keep $VERSION and ${PREVIOUS:-none}, remove older"
ssh -i "$KEY" "$SERVER" "$(prune_script "$VERSION" "$PREVIOUS")"

echo "== 5/5 push and GitHub release"
git push -q origin main "$TAG"
NOTES="$(mktemp)"
awk -v tag="## $TAG" 'index($0, tag) == 1 {on=1; next} /^## v/ {on=0} on' CHANGELOG.md > "$NOTES"
gh release create "$TAG" --title "$TAG" --notes-file "$NOTES"
rm -f "$NOTES"
echo "done: $TAG ($COMMIT). Check: curl -s https://chaekcheck.com/api/v1/version"
