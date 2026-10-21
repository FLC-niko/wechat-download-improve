#!/usr/bin/env bash
# Cloudflare tunnel watchdog.
#
# cloudflared reconnects on its own, but edge connections can wedge without the
# process dying (systemd then sees it as healthy). This watchdog checks the
# authoritative HA-connection gauge exposed by cloudflared's metrics endpoint
# and restarts the service after N consecutive zero-connection checks.
set -u

STATE_DIR=/run/cloudflared-watchdog
mkdir -p "$STATE_DIR"
exec 9>"$STATE_DIR/lock"
flock -n 9 || exit 0

THRESHOLD=${WATCHDOG_FAIL_THRESHOLD:-3}
PROBE_URL=${PROBE_URL:-https://einkrecovery.989gdut989.dpdns.org/api/health}
FAIL_FILE="$STATE_DIR/fail_count"
TAG=cloudflared-watchdog

log() { logger -t "$TAG" -- "$*"; }

fails=$(cat "$FAIL_FILE" 2>/dev/null || echo 0)
ok=0

if ! systemctl is-active --quiet cloudflared; then
    log "cloudflared is not active; starting it"
    systemctl start cloudflared
    sleep 3
fi

conns=$(curl -fsS --max-time 5 http://127.0.0.1:20241/metrics 2>/dev/null \
    | awk '/^cloudflared_tunnel_ha_connections/{print $2; exit}')

if [ -n "${conns:-}" ]; then
    if [ "${conns%%.*}" -gt 0 ] 2>/dev/null; then
        ok=1
    fi
else
    # Metrics endpoint unavailable: fall back to an end-to-end probe.
    # Any HTTP status (2xx-4xx) proves edge -> tunnel -> origin works;
    # 401 is expected here because /api/health requires auth.
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
        "$PROBE_URL" 2>/dev/null || echo 000)
    case "$code" in
        2*|3*|4*) ok=1 ;;
    esac
    conns="http:${code}"
fi

if [ "$ok" -eq 1 ]; then
    if [ "${fails:-0}" -ne 0 ]; then
        log "tunnel healthy again after $fails failed check(s)"
    fi
    echo 0 > "$FAIL_FILE"
    exit 0
fi

fails=$((fails + 1))
echo "$fails" > "$FAIL_FILE"
log "tunnel unhealthy (connections=${conns:-n/a}), consecutive=$fails/$THRESHOLD"
if [ "$fails" -ge "$THRESHOLD" ]; then
    log "restarting cloudflared after $fails consecutive failures"
    systemctl restart cloudflared
    echo 0 > "$FAIL_FILE"
fi
