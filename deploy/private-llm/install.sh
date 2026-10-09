#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
KEY=${KEY:-}
if [ -z "$KEY" ]; then
    IFS= read -r KEY
fi
case "$KEY" in
    ''|*[!a-fA-F0-9]*) printf '%s\n' 'KEY must be a non-empty hex value' >&2; exit 2 ;;
esac

sudo install -d -m 0755 /etc/systemd/system/ollama.service.d
sudo install -m 0644 "$ROOT/ollama-override.conf" /etc/systemd/system/ollama.service.d/private-llm.conf
sudo systemctl daemon-reload
sudo systemctl restart ollama

MODEL=$(sed -n 's/^FROM[[:space:]]\+//p' "$ROOT/Modelfile")
[ -n "$MODEL" ]
ollama pull "$MODEL"
ollama create private-chat -f "$ROOT/Modelfile"

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
sed "s/__KEY__/$KEY/g" "$ROOT/nginx-private-llm.conf" > "$tmp"
sudo install -o root -g www-data -m 0640 "$tmp" /etc/nginx/conf.d/private-llm.conf
sudo nginx -t
sudo systemctl reload nginx
printf '%s\n' 'private local LLM installation completed'
