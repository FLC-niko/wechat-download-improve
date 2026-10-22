#!/usr/bin/env bash
# Atomic deploy for wechat-download-kt.
#
# Invoked as root by /usr/local/bin/deploy-kt-upload.sh (sudoers: deploy user
# may run exactly this script). Layout:
#
#   <app>/deploy/releases/kt-<sha12>/{wechat-download-kt/...}
#   <app>/deploy/current     -> releases/kt-<sha12>
#   <app>/deploy/previous    -> previous release (for rollback)
#   <app>/build/install      -> ../deploy/current
#
# Runtime files (config.yaml, data/, logs/, qrcode/) are never touched.
set -euo pipefail

FILE="${1:?usage: deploy-kt.sh <tar.gz> <tag> <sha256>}"
TAG="${2:?tag required}"
SHA="${3:?sha256 required}"

APP=/home/topview/projects/wechat-download-improve/wechat-download-kt
OWNER=topview
DEPLOY="$APP/deploy"
RELEASES="$DEPLOY/releases"
HEALTH_URL="http://127.0.0.1:8001/api/health"

log() { logger -t deploy-kt -- "$*"; echo "[deploy-kt] $*"; }

[[ "$TAG" =~ ^kt-[0-9a-f]{12}$ ]] || { log "invalid tag: $TAG"; exit 2; }
[[ "$SHA" =~ ^[0-9a-f]{64}$ ]] || { log "invalid sha256"; exit 2; }
[[ -f "$FILE" ]] || { log "artifact missing: $FILE"; exit 2; }
echo "$SHA  $FILE" | sha256sum -c - >/dev/null || { log "sha256 mismatch"; exit 3; }

mkdir -p "$RELEASES" "$DEPLOY/backups"

# 1) Unpack the artifact into an immutable release directory.
DEST="$RELEASES/$TAG"
rm -rf "$DEST"
mkdir -p "$DEST"
tar -xzf "$FILE" -C "$DEST"
if [ ! -x "$DEST/wechat-download-kt/bin/wechat-download-kt" ]; then
    log "bad artifact layout (missing wechat-download-kt/bin/...)"
    rm -rf "$DEST"
    exit 4
fi
chown -R "$OWNER:$OWNER" "$DEST"

# 2) Back up the SQLite database (keep the newest 3).
if [ -f "$APP/data/rss.db" ]; then
    BK="$DEPLOY/backups/rss-$(date +%Y%m%d-%H%M%S).db"
    if python3 - "$APP/data/rss.db" "$BK" <<'PY'
import sqlite3, sys
src, dst = sys.argv[1], sys.argv[2]
s = sqlite3.connect(src)
d = sqlite3.connect(dst)
s.backup(d)
d.close()
s.close()
PY
    then
        ls -1t "$DEPLOY"/backups/rss-*.db 2>/dev/null | tail -n +4 | xargs -r rm -f
    else
        log "warning: sqlite backup failed, continuing"
    fi
fi

# 3) One-time migration: build/install was a real directory before this flow.
PREV=""
if [ -L "$DEPLOY/current" ]; then
    PREV="$(readlink -f "$DEPLOY/current")"
fi
if [ ! -L "$APP/build/install" ]; then
    LEGACY="$RELEASES/legacy-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$LEGACY"
    mv "$APP/build/install" "$LEGACY/install"
    ln -sfn "../deploy/current" "$APP/build/install"
    PREV="$LEGACY/install"
    log "migrated build/install to $LEGACY/install"
fi

# 4) Atomic switch (symlink swap via mv -T).
if [ -n "$PREV" ] && [ -d "$PREV" ]; then
    ln -sfn "$(realpath --relative-to="$DEPLOY" "$PREV")" "$DEPLOY/previous.tmp"
    mv -T "$DEPLOY/previous.tmp" "$DEPLOY/previous"
fi
ln -sfn "releases/$TAG" "$DEPLOY/current.tmp"
mv -T "$DEPLOY/current.tmp" "$DEPLOY/current"

# 5) Restart the application as its owner.
if ! sudo -u "$OWNER" -H bash -c "cd '$APP' && ./manage.sh restart"; then
    log "manage.sh restart failed"
fi

healthy() {
    curl -fsS --max-time 3 "$HEALTH_URL" 2>/dev/null | grep -qE '"live"[[:space:]]*:[[:space:]]*true'
}
OK=0
for _ in $(seq 1 20); do
    if healthy; then OK=1; break; fi
    sleep 2
done

# 6) Roll back on failure.
if [ "$OK" -ne 1 ] && [ -n "$PREV" ] && [ -d "$PREV" ]; then
    log "health check failed; rolling back to $PREV"
    ln -sfn "$(realpath --relative-to="$DEPLOY" "$PREV")" "$DEPLOY/current.tmp"
    mv -T "$DEPLOY/current.tmp" "$DEPLOY/current"
    sudo -u "$OWNER" -H bash -c "cd '$APP' && ./manage.sh restart" || true
    sleep 3
    if healthy; then log "rollback healthy"; else log "rollback also unhealthy"; fi
    exit 5
fi
if [ "$OK" -ne 1 ]; then
    log "health check failed and no previous release to roll back to"
    exit 5
fi

# 7) Prune old releases (keep current, previous, last 3).
CUR="$(readlink -f "$DEPLOY/current" 2>/dev/null || true)"
PREF="$(readlink -f "$DEPLOY/previous" 2>/dev/null || true)"
for d in $(ls -1dt "$RELEASES"/kt-* 2>/dev/null | tail -n +4); do
    [ "$(readlink -f "$d")" = "$CUR" ] && continue
    [ "$(readlink -f "$d")" = "$PREF" ] && continue
    rm -rf "$d"
done

python3 - "$DEPLOY/state.json" "$TAG" "$SHA" <<'PY'
import json, os, sys, time
path, tag, sha = sys.argv[1:4]
prev = {}
if os.path.exists(path):
    try:
        prev = json.load(open(path))
    except Exception:
        prev = {}
json.dump({
    "last_tag": tag,
    "last_sha": sha,
    "previous_tag": prev.get("last_tag"),
    "deployed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "health": "ok",
}, open(path, "w"), indent=1)
PY

rm -f "$FILE"
log "deployed $TAG (sha256 ${SHA:0:12}...)"
