#!/usr/bin/env bash
#
# Демонстрация локальной LLM (Ollama) через OpenAI-совместимый эндпоинт
# /v1/chat/completions — тот же формат, который использует ai-agent
# (см. README, раздел «Локальная LLM (Ollama)»).
#
# Три запроса разной сложности:
#   1. арифметика (короткий ответ);
#   2. объяснение «процесс vs поток» (4–5 предложений);
#   3. написание кода: Java-метод проверки палиндрома с кириллицей.
#
# Переменные окружения (можно переопределить):
#   OLLAMA_BASE_URL — базовый URL Ollama (по умолчанию http://localhost:11434)
#   OLLAMA_MODEL    — модель (по умолчанию qwen2.5:3b)
#
# Ключ API для Ollama не нужен; curl-заглушка (-H) оставлена для совместимости
# с клиентами, требующими непустой Authorization.
set -u

BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
MODEL="${OLLAMA_MODEL:-qwen2.5:3b}"
ENDPOINT="$BASE_URL/v1/chat/completions"

timestamp_ms() {
    date +%s%N 2>/dev/null | awk '{print int($1/1000000)}' \
        || python3 -c 'import time; print(int(time.time()*1000))'
}

# Проверка доступности Ollama до запросов: без неё только диагностика.
if ! curl -s -m 5 "$BASE_URL/api/tags" >/dev/null 2>&1; then
    echo "Ollama недоступна: $BASE_URL не отвечает (curl $BASE_URL/api/tags)." >&2
    echo "Запустите: ollama serve (или приложение Ollama), затем повторите." >&2
    exit 1
fi
if ! curl -s -m 5 "$BASE_URL/api/tags" | grep -q "\"$MODEL\""; then
    echo "Модель $MODEL не найдена в Ollama. Скачайте: ollama pull $MODEL" >&2
    exit 1
fi

# Один запрос к /v1/chat/completions: печатает ответ модели и время.
# Аргументы: заголовок, max_tokens, текст запроса пользователя.
ask() {
    local title="$1" max_tokens="$2" prompt="$3"
    local body start api_ms answer
    body=$(jq -n --arg m "$MODEL" --arg q "$prompt" --argjson t "$max_tokens" \
        '{model: $m, max_tokens: $t, messages: [{role: "system", content: "Ты отвечаешь по-русски, точно и без вступлений."}, {role: "user", content: $q}]}')
    printf '### %s\n' "$title"
    start=$(timestamp_ms)
    answer=$(curl -sS -m 300 "$ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ollama" \
        -d "$body" | jq -r '.choices[0].message.content // .error.message // "нет ответа"')
    api_ms=$(( $(timestamp_ms) - start ))
    printf '%s\n' "$answer"
    printf '%s %d мс\n\n' "--- время запроса (включая ожидание модели):" "$api_ms"
}

ask "Запрос 1 (простой): 17*23" 64 "Сколько будет 17*23? Ответь одним числом."

ask "Запрос 2 (средний): объяснение" 512 \
    "Чем процесс отличается от потока (thread)? Ответь в 4–5 предложениях."

ask "Запрос 3 (сложный): код" 1024 \
    "Напиши Java-метод isPalindrome(String), который проверяет, является ли строка палиндромом, с поддержкой кириллицы (без библиотечных нормализаций). Только код метода."
