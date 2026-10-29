#!/usr/bin/env bash
# Releases one version to the EC2 server (user decision 2026-10-18: semantic versions from v1.0.0, with the commit
# hash). Updated for zero-downtime deployment (cycle 27, ADR-0167).
#
#   deploy/prod/bin/release.sh X.Y.Z [backend] [web]                 build, send, switch (zero-downtime)
#   deploy/prod/bin/release.sh --switch-only X.Y.Z [backend] [web]   switch with server images already there
#
# Services default to: backend then web. Order matters: backend first (new API before new UI).
# db and cloudflared: "maintenance deploy: see deploy/prod/README.md".
#
# 1. Stops unless: on main, nothing uncommitted, X.Y.Z is new (no tag vX.Y.Z), CHANGELOG.md has a "## vX.Y.Z" part.
# 2. Writes X.Y.Z into backend/build.gradle and web/package.json, commits "Release vX.Y.Z" and tags it vX.Y.Z.
# 3. Builds the jar and web/dist, then arm64 images tagged X.Y.Z and sha-<commit>.
# 4. Copies the images, scripts and nginx config to the server, loads images, runs switch.sh for each service.
#    Then removes the server's older chaekcheck/backend and chaekcheck/web images, keeping this version, the one
#    before it, and the versions in slots.env (user 2026-10-25). Dangling layers go.
# 5. Pushes main and the tag, and makes the GitHub release with that CHANGELOG part (gh).
# A failure stops the script; what is done stays done (see deploy/prod/README.md "실패하면").
set -euo pipefail

# ── dawn check (ADR-0171): before anything else ──
hhmm="${CHAEKCHECK_CLOCK_HHMM:-$(TZ=Asia/Seoul date +%H%M)}"
# 10# forces decimal: bash [[ -ge ]] treats leading-zero strings as octal (0400 = 256, not 400).
if (( 10#$hhmm >= 325 && 10#$hhmm <= 510 )); then
    echo "release.sh: REFUSED reason=dawn (03:25-05:10 Asia/Seoul, scheduled jobs); nothing changed"
    exit 1
fi

# ── arguments ──
SWITCH_ONLY=false
if [[ "${1:-}" == "--switch-only" ]]; then
    SWITCH_ONLY=true
    shift
fi

VERSION="${1:-}"
shift || true
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: release.sh [--switch-only] X.Y.Z [backend] [web]" >&2; exit 1; }
TAG="v$VERSION"

# services to deploy (default: backend then web)
SERVICES=("${@}")
if [[ ${#SERVICES[@]} -eq 0 ]]; then
    SERVICES=(backend web)
fi
for s in "${SERVICES[@]}"; do
    if [[ "$s" == "db" || "$s" == "cloudflared" ]]; then
        echo "release.sh: $s requires a maintenance deploy: see deploy/prod/README.md" >&2; exit 1
    fi
done

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
SERVER="${CHAEKCHECK_SERVER:-ubuntu@52.64.117.112}"
KEY="${CHAEKCHECK_SSH_KEY:-$ROOT/.secrets/chaekcheck-key.pem}"
REMOTE_DIR=/srv/chaekcheck
# the repository as WSL sees it (Git Bash /c/... -> /mnt/c/...)
WSL_ROOT="$(pwd | sed -E 's#^/([a-zA-Z])/#/mnt/\L\1/#')"

stop() { echo "release.sh: $*" >&2; exit 1; }

# The server-side cleanup: keep $1 (now), $2 (before), and the versions from slots.env.
prune_script() {
	cat <<SH
keep=\$( { for repo in chaekcheck/backend chaekcheck/web; do for version in $1 $2; do
  docker image inspect --format '{{.Id}}' \$repo:\$version 2>/dev/null; done; done
  if [ -f /srv/chaekcheck/slots.env ]; then . /srv/chaekcheck/slots.env
    for v in \${BACKEND_BLUE_VERSION:-} \${BACKEND_GREEN_VERSION:-} \${WEB_VERSION:-}; do
      [ -n "\$v" ] && for repo in chaekcheck/backend chaekcheck/web; do
        docker image inspect --format '{{.Id}}' \$repo:\$v 2>/dev/null; done; done; fi
  docker ps -aq | xargs -r docker inspect --format '{{.Image}}'; } | sort -u)
docker image ls --no-trunc --format '{{.Repository}}:{{.Tag}} {{.ID}}' | grep -E '^chaekcheck/(backend|web):' \
  | while read -r ref id; do
    if ! printf '%s\n' "\$keep" | grep -qx "\$id"; then docker rmi "\$ref" >/dev/null && echo "   removed \$ref"; fi
  done
docker image prune -f >/dev/null
df -h / | tail -1
SH
}

if $SWITCH_ONLY; then
    # ── switch-only mode: no git, no build, no transfer ──
    [[ -f "$KEY" ]] || stop "no SSH key at $KEY"

    # verify images/releases exist on server
    for s in "${SERVICES[@]}"; do
        if [[ "$s" == "backend" ]]; then
            ssh -i "$KEY" "$SERVER" "docker image inspect chaekcheck/backend:$VERSION >/dev/null 2>&1" \
                || stop "chaekcheck/backend:$VERSION not on server: use release.sh to build and send it"
        elif [[ "$s" == "web" ]]; then
            ssh -i "$KEY" "$SERVER" "docker image inspect chaekcheck/web:$VERSION >/dev/null 2>&1 || [ -d $REMOTE_DIR/web/releases/$VERSION ]" \
                || stop "chaekcheck/web:$VERSION not on server: use release.sh to build and send it"
        fi
    done

    echo "== switch-only $VERSION: ${SERVICES[*]}"
    for s in "${SERVICES[@]}"; do
        ssh -i "$KEY" "$SERVER" "cd $REMOTE_DIR && chmod +x bin/switch.sh && bin/switch.sh $s $VERSION" \
            || { echo "release.sh: switch.sh $s $VERSION failed"; exit 1; }
    done
    echo "done: $VERSION (switch-only). ${SERVICES[*]}"
    exit 0
fi

# ── full release ──
[[ "$(git branch --show-current)" == main ]] || stop "not on main"
[[ -z "$(git status --porcelain)" ]] || stop "uncommitted changes; commit or stash them first"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && stop "tag $TAG exists already"
grep -q "^## $TAG" CHANGELOG.md || stop "CHANGELOG.md has no '## $TAG' part"
[[ -f "$KEY" ]] || stop "no SSH key at $KEY"

echo "== 1/5 version $VERSION into the build files"
sed -i -E "s/^version = '[^']*'/version = '$VERSION'/" backend/build.gradle
sed -i -E "0,/\"version\": \"[^\"]*\"/s//\"version\": \"$VERSION\"/" web/package.json
git add backend/build.gradle web/package.json
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
ARCHIVE="//wsl.localhost/Ubuntu$(wsl -d Ubuntu -- bash -c 'echo ~' | tr -d '')/chaekcheck-release/images-$VERSION.tar.gz"

echo "== 4/5 server: load and switch (${SERVICES[*]})"
scp -q -i "$KEY" "$ARCHIVE" "$SERVER:$REMOTE_DIR/images-$VERSION.tar.gz"
scp -q -i "$KEY" deploy/prod/compose.yaml "$SERVER:$REMOTE_DIR/compose.yaml"
scp -q -i "$KEY" deploy/prod/bin/up.sh "$SERVER:$REMOTE_DIR/bin/up.sh"
scp -q -i "$KEY" deploy/prod/bin/switch.sh "$SERVER:$REMOTE_DIR/bin/switch.sh"
# nginx config goes to incoming/ for switch.sh to apply
ssh -i "$KEY" "$SERVER" "mkdir -p $REMOTE_DIR/nginx/incoming"
scp -q -i "$KEY" deploy/nginx/ec2-tunnel.conf deploy/nginx/chaekcheck-locations.conf \
    deploy/nginx/rate-limits.conf deploy/nginx/security-headers.conf \
    "$SERVER:$REMOTE_DIR/nginx/incoming/"
ssh -i "$KEY" "$SERVER" "cd $REMOTE_DIR && gunzip -c images-$VERSION.tar.gz | docker load >/dev/null \
  && rm -f images-$VERSION.tar.gz && chmod +x bin/up.sh bin/switch.sh"

for s in "${SERVICES[@]}"; do
    ssh -i "$KEY" "$SERVER" "cd $REMOTE_DIR && bin/switch.sh $s $VERSION" \
        || { echo "release.sh: switch.sh $s $VERSION failed"; exit 1; }
done

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
echo "done: $TAG ($COMMIT). ${SERVICES[*]}"
