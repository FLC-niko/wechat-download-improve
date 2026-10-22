#!/usr/bin/env bash
# One-time server setup for CI deployments (run as root on the app server).
#
#   sudo bash install-server.sh /path/to/deploy_key.pub
#
# Installs:
#   - `deploy` system user (password locked, key-only, forced command)
#   - /usr/local/bin/deploy-kt-upload.sh and /usr/local/bin/deploy-kt.sh
#   - /etc/sudoers.d/wechat-deploy (deploy -> root, only deploy-kt.sh)
set -euo pipefail

PUBKEY="${1:?usage: install-server.sh <deploy_public_key_file>}"
HERE="$(cd "$(dirname "$0")" && pwd)"

if ! id deploy >/dev/null 2>&1; then
    useradd -m -s /bin/bash deploy
    passwd -l deploy >/dev/null
fi

install -m 755 -o root -g root "$HERE/deploy-kt.sh" /usr/local/bin/deploy-kt.sh
install -m 755 -o root -g root "$HERE/deploy-kt-upload.sh" /usr/local/bin/deploy-kt-upload.sh

install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
install -m 600 -o deploy -g deploy /dev/null /home/deploy/.ssh/authorized_keys
KEY="$(cat "$PUBKEY")"
printf 'command="/usr/local/bin/deploy-kt-upload.sh",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty %s\n' "$KEY" \
    > /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys

printf 'deploy ALL=(root) NOPASSWD: /usr/local/bin/deploy-kt.sh\n' > /etc/sudoers.d/wechat-deploy
chmod 440 /etc/sudoers.d/wechat-deploy
visudo -cf /etc/sudoers.d/wechat-deploy >/dev/null

echo "installed. ssh deploy@<host> ping  -> expect 'pong'"
