#!/usr/bin/env bash
set -euo pipefail
set +x

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
if [ -f "$ROOT/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$ROOT/.env"
    set +a
fi
: "${PRIVATE_LLM_KEY:?PRIVATE_LLM_KEY is missing from .env}"
PRIVATE_LLM_URL=${PRIVATE_LLM_URL:-http://127.0.0.1:8080}
history='[]'

while IFS= read -r -p 'Вы › ' prompt; do
    [ "$prompt" = /exit ] && break
    [ -z "$prompt" ] && continue
    payload=$(PROMPT="$prompt" HISTORY="$history" python3 -c '
import json, os
h = json.loads(os.environ["HISTORY"])
h.append({"role": "user", "content": os.environ["PROMPT"]})
print(json.dumps({"model": "private-chat", "messages": h, "stream": False}, ensure_ascii=False))
')
    response=$(curl --silent --show-error --fail-with-body \
        --connect-timeout 5 --max-time 120 \
        -H "Authorization: Bearer $PRIVATE_LLM_KEY" \
        -H 'Content-Type: application/json' \
        --data "$payload" "$PRIVATE_LLM_URL/api/chat") || {
        printf '%s\n' 'Модель › ошибка запроса'
        continue
    }
    answer=$(RESPONSE="$response" python3 -c '
import json, os
try:
    print(json.loads(os.environ["RESPONSE"]).get("message", {}).get("content", ""))
except (ValueError, TypeError):
    print("ошибка разбора ответа")
')
    printf 'Модель › %s\n' "$answer"
    history=$(PROMPT="$prompt" RESPONSE="$response" HISTORY="$history" python3 -c '
import json, os
h = json.loads(os.environ["HISTORY"])
h.append({"role": "user", "content": os.environ["PROMPT"]})
r = json.loads(os.environ["RESPONSE"])
if r.get("message"):
    h.append(r["message"])
print(json.dumps(h, ensure_ascii=False))
')
done
