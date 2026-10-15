#!/usr/bin/env bash
set -euo pipefail

if [ "${EUID}" -ne 0 ]; then
  echo "Run as root: sudo CRACK_TCAPTCHA_REF=<40-char-commit> $0" >&2
  exit 1
fi

ref="${CRACK_TCAPTCHA_REF:-}"
if [[ ! "${ref}" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "CRACK_TCAPTCHA_REF must be an explicitly reviewed 40-character commit SHA" >&2
  exit 1
fi

for command_name in git python3 node npm; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "Missing required command: ${command_name}" >&2
    exit 1
  }
done

python3 -c 'import sys; assert sys.version_info >= (3, 10), "Python 3.10+ is required"'
node_major="$(node -p 'process.versions.node.split(".")[0]')"
if [ "${node_major}" -lt 18 ]; then
  echo "Node.js 18+ is required" >&2
  exit 1
fi

install_root="/opt/crack-tcaptcha"
source_dir="${install_root}/source"
venv_dir="${install_root}/venv"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

install -d -m 0755 "${install_root}"
if [ ! -d "${source_dir}/.git" ]; then
  git clone --filter=blob:none https://github.com/hailan09/crackTCaptcha.git "${source_dir}"
fi

if [ -n "$(git -C "${source_dir}" status --porcelain)" ]; then
  echo "Refusing to update a dirty solver checkout: ${source_dir}" >&2
  exit 1
fi
git -C "${source_dir}" fetch --no-tags origin "${ref}"
git -C "${source_dir}" checkout --detach "${ref}"

python3 -m venv "${venv_dir}"
"${venv_dir}/bin/python" -m pip install --upgrade pip
"${venv_dir}/bin/pip" install "${source_dir}[word-click]"

tdc_js_dir="${source_dir}/src/crack_tcaptcha/tdc/js"
if [ ! -f "${tdc_js_dir}/package-lock.json" ]; then
  echo "Pinned source is missing tdc/js/package-lock.json; refusing a non-reproducible npm install" >&2
  exit 1
fi
npm --prefix "${tdc_js_dir}" ci --omit=dev --no-audit --no-fund

install -m 0644 "${script_dir}/crack-tcaptcha.service" /etc/systemd/system/crack-tcaptcha.service
if [ ! -f /etc/crack-tcaptcha.env ]; then
  install -m 0600 "${script_dir}/crack-tcaptcha.env.example" /etc/crack-tcaptcha.env
fi
systemctl daemon-reload

echo "Installed crack-tcaptcha at reviewed commit ${ref}."
echo "Before starting: edit /etc/crack-tcaptcha.env and replace the placeholder secret."
echo "Then run: sudo systemctl enable --now crack-tcaptcha"
echo "Health check: curl http://127.0.0.1:9991/health"
