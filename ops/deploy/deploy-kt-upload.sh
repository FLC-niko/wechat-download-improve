#!/usr/bin/env bash
# Forced command for the `deploy` SSH user.
#
# Client usage (artifact is streamed on stdin):
#   ssh deploy@deploy.989gdut989.dpdns.org "deploy-kt-upload kt-<sha12> <sha256>" < kt.tar.gz
#
# Accepts only a tag matching kt-<12 hex> and a 64-hex sha256; verifies the
# checksum before handing the file to the root deploy script.
set -euo pipefail

CMD="${SSH_ORIGINAL_COMMAND:-}"
if [ "$CMD" = "ping" ]; then
    echo "pong"
    exit 0
fi

read -r verb tag sha extra <<< "$CMD"
[ "${verb:-}" = "deploy-kt-upload" ] || { echo "denied"; exit 1; }
[ -z "${extra:-}" ] || { echo "bad arguments"; exit 1; }
[[ "${tag:-}" =~ ^kt-[0-9a-f]{12}$ ]] || { echo "bad tag"; exit 1; }
[[ "${sha:-}" =~ ^[0-9a-f]{64}$ ]] || { echo "bad sha256"; exit 1; }

FILE="$(mktemp "/tmp/kt-upload.XXXXXX.tar.gz")"
trap 'rm -f "$FILE"' EXIT
cat > "$FILE"
chmod 600 "$FILE"
SIZE=$(stat -c %s "$FILE")
if [ "$SIZE" -gt 209715200 ]; then
    echo "artifact too large"
    exit 1
fi
if ! echo "$sha  $FILE" | sha256sum -c - >/dev/null 2>&1; then
    echo "sha256 mismatch"
    exit 1
fi

sudo /usr/local/bin/deploy-kt.sh "$FILE" "$tag" "$sha"
