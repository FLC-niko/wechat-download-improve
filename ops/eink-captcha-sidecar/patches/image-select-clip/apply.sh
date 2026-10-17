#!/usr/bin/env bash
set -euo pipefail

install_root="${1:-/opt/crack-tcaptcha}"
source_dir="${install_root}/source"
venv_dir="${install_root}/venv"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "${EUID}" -ne 0 ]; then
  echo "Run as root: sudo bash $0 [/opt/crack-tcaptcha]" >&2
  exit 1
fi

site_packages="$("${venv_dir}/bin/python" -c 'import site; print(site.getsitepackages()[0])')"

install -m 0644 "${script_dir}/clip_vision.py" \
  "${source_dir}/src/crack_tcaptcha/solvers/clip_vision.py"
install -m 0644 "${script_dir}/image_select.py" \
  "${source_dir}/src/crack_tcaptcha/pipelines/image_select.py"
install -m 0644 "${script_dir}/clip_vision.py" \
  "${site_packages}/crack_tcaptcha/solvers/clip_vision.py"
install -m 0644 "${script_dir}/image_select.py" \
  "${site_packages}/crack_tcaptcha/pipelines/image_select.py"

systemctl restart crack-tcaptcha
echo "local image_select clip backend applied; crack-tcaptcha restarted"
