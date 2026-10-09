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
URL=${PRIVATE_LLM_URL:-http://127.0.0.1:8080}
fail=0
check() { printf '%s | %s\n' "$1" "$2"; [ "$2" = ok ] || fail=1; }

code=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 "$URL/health" || true)
[ "$code" = 200 ] && check 'health без ключа' ok || check 'health без ключа' "fail ($code)"
code=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 -H 'Content-Type: application/json' --data '{"model":"private-chat","messages":[]}' "$URL/api/chat" || true)
[ "$code" = 401 ] && check 'chat без ключа' ok || check 'chat без ключа' "fail ($code)"
code=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 -H 'Authorization: Bearer invalid' "$URL/api/tags" || true)
[ "$code" = 401 ] && check 'неверный ключ' ok || check 'неверный ключ' "fail ($code)"
code=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 120 -H "Authorization: Bearer $PRIVATE_LLM_KEY" -H 'Content-Type: application/json' --data '{"model":"private-chat","messages":[{"role":"user","content":"Ответь одним словом: привет"}],"stream":false}' "$URL/api/chat" || true)
[ "$code" = 200 ] && check 'chat с ключом' ok || check 'chat с ключом' "fail ($code)"

printf 'итог | %s\n' "$([ "$fail" -eq 0 ] && printf ok || printf fail)"
exit "$fail"
