#!/usr/bin/env bash
set -euo pipefail
# Uploaded package must be extracted under /opt/bilingualreader/package first.
test "$(id -u)" = 0
install -d -m 755 /opt/bilingualreader /opt/bilingualreader/app /opt/bilingualreader/acme-webroot
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq nginx python3-venv
if ! id readertranslate >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin readertranslate
fi
python3 -m venv /opt/bilingualreader/venv
/opt/bilingualreader/venv/bin/pip install --disable-pip-version-check gunicorn==23.0.0
python3 -m venv /opt/bilingualreader/acme-venv
/opt/bilingualreader/acme-venv/bin/pip install --disable-pip-version-check certbot==5.4.0
install -m 644 /opt/bilingualreader/package/backend/gateway.py /opt/bilingualreader/app/gateway.py
install -m 644 /opt/bilingualreader/package/deploy/qwen/bilingualreader.service /etc/systemd/system/bilingualreader.service
if ! test -f /opt/bilingualreader/.env; then
  install -m 600 /opt/bilingualreader/package/deploy/qwen/.env.example /opt/bilingualreader/.env
fi
systemctl daemon-reload
systemctl enable --now bilingualreader.service
systemctl enable --now nginx
