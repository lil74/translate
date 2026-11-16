#!/bin/sh
set -eu
cd /opt/bilingualreader/asr-update
command -v ffprobe >/dev/null || { echo 'Install ffmpeg before deploying ASR' >&2; exit 1; }
for file in /opt/bilingualreader/app/gateway.py /etc/nginx/conf.d/bilingualreader-https.conf /etc/systemd/system/bilingualreader.service; do
    test -e "$file.before-asr-20261116" || cp -p "$file" "$file.before-asr-20261116"
done
python3 -m py_compile gateway.py asr.py
install -m 644 gateway.py asr.py /opt/bilingualreader/app/
install -d -m 700 -o readertranslate -g readertranslate /var/lib/bilingualreader/asr
grep -q '^ASR_PUBLIC_URL=' /opt/bilingualreader/.env || printf '\nASR_PUBLIC_URL=https://8.148.28.233\n' >> /opt/bilingualreader/.env
install -m 644 bilingualreader.service bilingualreader-asr-cleanup.service bilingualreader-asr-cleanup.timer /etc/systemd/system/
install -m 644 nginx-https.conf /etc/nginx/conf.d/bilingualreader-https.conf
nginx -t
systemctl daemon-reload
systemctl restart bilingualreader
systemctl reload nginx
systemctl enable --now bilingualreader-asr-cleanup.timer
systemctl is-active bilingualreader
