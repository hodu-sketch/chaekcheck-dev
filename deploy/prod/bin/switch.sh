#!/usr/bin/env bash
# Zero-downtime deploy: switches one backend slot or the web files (cycle 27, ADR-0167/0168/0169/0170/0171).
#
#   switch.sh backend X.Y.Z    — bring up the idle slot with X.Y.Z, point nginx at it, stop the old slot
#   switch.sh web X.Y.Z        — extract the web image to releases/X.Y.Z, switch the symlink, nginx reload
#
# Runs on the server from /srv/chaekcheck. Expects: slots.env, secrets.env, compose.yaml, bin/up.sh.
# The dawn window (03:25–05:10 Asia/Seoul) is always refused (ADR-0171: scheduled jobs run then).
# A failed backend switch rolls back the version in slots.env and stops the new-slot container (the old slot
# keeps serving). A Liquibase migration that already ran stays (ADR-0170).
set -euo pipefail

DIR="${CHAEKCHECK_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$DIR"

KIND="${1:-}"
VERSION="${2:-}"
[[ "$KIND" =~ ^(backend|web)$ ]] || { echo "usage: switch.sh backend|web X.Y.Z" >&2; exit 1; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: switch.sh backend|web X.Y.Z" >&2; exit 1; }

# ── common 1: single instance ──
exec 9>"$DIR/.switch.lock"
flock -n 9 || { echo "switch: REFUSED $KIND $VERSION reason=already-running; nothing changed"; exit 1; }

# ── common 2: dawn check (ADR-0171) ──
hhmm="${CHAEKCHECK_CLOCK_HHMM:-$(TZ=Asia/Seoul date +%H%M)}"
if [[ "$hhmm" -ge 325 && "$hhmm" -le 510 ]]; then
    echo "switch: REFUSED $KIND $VERSION reason=dawn (03:25-05:10 Asia/Seoul, scheduled jobs); nothing changed"
    exit 1
fi

# ── common 3: slots.env ──
SLOTS="$DIR/slots.env"
[[ -f "$SLOTS" ]] || { echo "switch: FAIL $KIND $VERSION reason=no-slots-env (README: first-time migration); nothing changed"; exit 1; }

# shellcheck source=/dev/null
source "$SLOTS"
[[ -n "${ACTIVE_SLOT:-}" ]] || { echo "switch: FAIL $KIND $VERSION reason=no-active-slot; nothing changed"; exit 1; }

# ── common 4: dc helper ──
dc() {
    local env_files=(--env-file "$DIR/secrets.env")
    [[ -f "$DIR/prod.env" ]] && env_files+=(--env-file "$DIR/prod.env")
    env_files+=(--env-file "$SLOTS")
    docker compose "${env_files[@]}" "$@"
}

# ── common 5: atomic slots.env write (change one line) ──
slots_set() {
    local key="$1" value="$2"
    local tmp
    tmp="$(mktemp "$DIR/.slots.env.XXXXXX")"
    if grep -q "^${key}=" "$SLOTS"; then
        sed "s|^${key}=.*|${key}=${value}|" "$SLOTS" > "$tmp"
    else
        { cat "$SLOTS"; echo "${key}=${value}"; } > "$tmp"
    fi
    mv -f "$tmp" "$SLOTS"
    # re-source so dc() picks up the new values
    # shellcheck source=/dev/null
    source "$SLOTS"
}

# ── common 6: incoming nginx config ──
apply_incoming_nginx() {
    local incoming="$DIR/nginx/incoming"
    [[ -d "$incoming" ]] && [[ -n "$(ls -A "$incoming" 2>/dev/null)" ]] || return 0

    # back up current
    local backup
    backup="$(mktemp -d "$DIR/nginx/.backup.XXXXXX")"
    cp -a "$DIR/nginx/conf.d/." "$backup/conf.d/" 2>/dev/null || true
    cp -a "$DIR/nginx/chaekcheck/." "$backup/chaekcheck/" 2>/dev/null || true

    # place incoming files
    for f in "$incoming"/*; do
        local name
        name="$(basename "$f")"
        case "$name" in
            ec2-tunnel.conf) cp -f "$f" "$DIR/nginx/conf.d/default.conf" ;;
            *) cp -f "$f" "$DIR/nginx/chaekcheck/$name" ;;
        esac
    done

    # test; on failure restore backup
    if ! dc exec -T web nginx -t >/dev/null 2>&1; then
        cp -a "$backup/conf.d/." "$DIR/nginx/conf.d/" 2>/dev/null || true
        cp -a "$backup/chaekcheck/." "$DIR/nginx/chaekcheck/" 2>/dev/null || true
        rm -rf "$backup"
        return 1
    fi
    rm -rf "$backup"
    rm -f "$incoming"/*
    return 0
}

# ═══════════════════════════════════════════════════════════════════════════
# backend
# ═══════════════════════════════════════════════════════════════════════════
switch_backend() {
    # 1. image check
    docker image inspect "chaekcheck/backend:$VERSION" >/dev/null 2>&1 \
        || { echo "switch: FAIL backend $VERSION reason=no-image; site still on $ACTIVE_SLOT"; exit 1; }

    # 2. new slot
    local new_slot old_slot
    if [[ "$ACTIVE_SLOT" == "blue" ]]; then new_slot=green; old_slot=blue; else new_slot=blue; old_slot=green; fi

    local old_version
    if [[ "$new_slot" == "blue" ]]; then old_version="${BACKEND_BLUE_VERSION:-}"; else old_version="${BACKEND_GREEN_VERSION:-}"; fi

    # 3. write version for the new slot
    local version_key="BACKEND_${new_slot^^}_VERSION"
    slots_set "$version_key" "$VERSION"

    # failure cleanup
    backend_fail() {
        local reason="$1"
        echo "--- last 40 lines from backend-$new_slot ---"
        dc logs --no-log-prefix --tail 40 "backend-$new_slot" 2>/dev/null || true
        echo "---"
        dc stop "backend-$new_slot" 2>/dev/null || true
        slots_set "$version_key" "$old_version"
        local was_version
        if [[ "$old_slot" == "blue" ]]; then was_version="$BACKEND_BLUE_VERSION"; else was_version="$BACKEND_GREEN_VERSION"; fi
        echo "switch: FAIL backend $VERSION slot=$new_slot reason=$reason; site still $was_version on $old_slot"
        exit 1
    }

    # 4. start the new slot
    "$DIR/bin/up.sh" --no-deps --force-recreate "backend-$new_slot"

    # 5. readiness polling (1s interval, max 60s)
    local start_time elapsed=0
    start_time=$(date +%s)
    while [[ $elapsed -lt 60 ]]; do
        sleep 1
        elapsed=$(( $(date +%s) - start_time ))

        # (a) container state
        local state restart_count
        local cid
        cid="$(dc ps -aq "backend-$new_slot" 2>/dev/null | head -1)"
        if [[ -z "$cid" ]]; then
            backend_fail "no-container"
        fi
        state="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo unknown)"
        restart_count="$(docker inspect -f '{{.RestartCount}}' "$cid" 2>/dev/null || echo 0)"
        if [[ "$state" == "exited" || "$state" == "dead" ]]; then
            backend_fail "exited"
        fi
        if [[ "$restart_count" -gt 0 ]]; then
            backend_fail "restarted"
        fi

        # (b) readiness probe via web container
        local body
        body="$(dc exec -T web wget -q -T 2 -O- "http://backend-$new_slot:8080/actuator/health/readiness" 2>/dev/null || true)"
        if echo "$body" | grep -q '"status":"UP"'; then
            break
        fi
    done
    if [[ $elapsed -ge 60 ]]; then
        backend_fail "timeout"
    fi
    local ready_seconds=$elapsed

    # 6. version check
    local reported_version
    reported_version="$(dc exec -T web wget -q -T 2 -O- "http://backend-$new_slot:8080/api/v1/version" 2>/dev/null || true)"
    if ! echo "$reported_version" | grep -q "\"version\":\"$VERSION\""; then
        backend_fail "version-mismatch"
    fi

    # 7. nginx: apply incoming config + write active-backend.conf
    apply_incoming_nginx || backend_fail "nginx-test"

    local conf="$DIR/nginx/chaekcheck/active-backend.conf"
    local conf_tmp
    conf_tmp="$(mktemp "$DIR/nginx/chaekcheck/.active-backend.conf.XXXXXX")"
    cat > "$conf_tmp" <<NGINX
upstream chaekcheck_backend {
    server backend-$new_slot:8080;
    keepalive 16;
}
NGINX
    mv -f "$conf_tmp" "$conf"

    if ! dc exec -T web nginx -t >/dev/null 2>&1; then
        # restore to old slot
        conf_tmp="$(mktemp "$DIR/nginx/chaekcheck/.active-backend.conf.XXXXXX")"
        cat > "$conf_tmp" <<NGINX
upstream chaekcheck_backend {
    server backend-$old_slot:8080;
    keepalive 16;
}
NGINX
        mv -f "$conf_tmp" "$conf"
        backend_fail "nginx-test"
    fi

    dc exec -T web nginx -s reload

    # 8. wait for nginx
    sleep 2

    # 9. update ACTIVE_SLOT
    slots_set "ACTIVE_SLOT" "$new_slot"

    # 10. stop old slot
    local old_exit=""
    dc stop "backend-$old_slot" 2>/dev/null || true
    local old_cid
    old_cid="$(docker ps -aq --filter "name=chaekcheck-backend-${old_slot}-1" 2>/dev/null | head -1)"
    if [[ -n "$old_cid" ]]; then
        local exit_code
        exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$old_cid" 2>/dev/null || echo "?")"
        if [[ "$exit_code" == "137" ]]; then
            old_exit=" old-exit=137 (killed)"
        else
            old_exit=" old-exit=$exit_code"
        fi
    fi

    local was_version
    if [[ "$old_slot" == "blue" ]]; then was_version="$BACKEND_BLUE_VERSION"; else was_version="$BACKEND_GREEN_VERSION"; fi
    echo "switch: OK backend $VERSION slot=$new_slot ready=${ready_seconds}s (was $was_version on $old_slot,$old_exit)"
}

# ═══════════════════════════════════════════════════════════════════════════
# web
# ═══════════════════════════════════════════════════════════════════════════
switch_web() {
    local prev_version="${WEB_VERSION:-}"
    local releases="$DIR/web/releases"

    # 2. release folder
    if [[ ! -d "$releases/$VERSION" ]]; then
        # extract from image
        docker image inspect "chaekcheck/web:$VERSION" >/dev/null 2>&1 \
            || { echo "switch: FAIL web $VERSION reason=no-image; site still $prev_version"; exit 1; }

        local cid
        cid="$(docker create "chaekcheck/web:$VERSION")"
        mkdir -p "$releases/$VERSION.tmp"
        docker cp "$cid:/usr/share/nginx/html/." "$releases/$VERSION.tmp/"
        docker rm "$cid" >/dev/null

        # carry forward the previous version's hashed assets so old pages can still load them
        if [[ -n "$prev_version" && -d "$releases/$prev_version/assets" ]]; then
            cp -an "$releases/$prev_version/assets/." "$releases/$VERSION.tmp/assets/" 2>/dev/null || true
        fi

        [[ -f "$releases/$VERSION.tmp/index.html" ]] \
            || { rm -rf "$releases/$VERSION.tmp"; echo "switch: FAIL web $VERSION reason=no-index-html; site still $prev_version"; exit 1; }

        mv "$releases/$VERSION.tmp" "$releases/$VERSION"
    fi

    # 3. atomic symlink swap (relative path)
    ( cd "$DIR/web" && ln -sfn "releases/$VERSION" current.tmp && mv -T current.tmp current )

    # 4. incoming config + nginx test
    apply_incoming_nginx || true

    if ! dc exec -T web nginx -t >/dev/null 2>&1; then
        # restore symlink
        if [[ -n "$prev_version" && -d "$releases/$prev_version" ]]; then
            ( cd "$DIR/web" && ln -sfn "releases/$prev_version" current.tmp && mv -T current.tmp current )
        fi
        dc exec -T web nginx -s reload 2>/dev/null || true
        echo "switch: FAIL web $VERSION reason=nginx-test; site still $prev_version"
        exit 1
    fi

    dc exec -T web nginx -s reload

    # 5. update WEB_VERSION
    slots_set "WEB_VERSION" "$VERSION"

    # 6. clean up: keep only current and previous
    for d in "$releases"/*/; do
        local name
        name="$(basename "$d")"
        [[ "$name" == "$VERSION" || "$name" == "$prev_version" ]] && continue
        rm -rf "$d"
    done

    echo "switch: OK web $VERSION (was $prev_version)"
}

# ═══════════════════════════════════════════════════════════════════════════
case "$KIND" in
    backend) switch_backend ;;
    web) switch_web ;;
esac
