#!/usr/bin/env bash
#
# Однократное создание локального тега qwen2.5:3b-rag8k (num_ctx 8192)
# для полностью локального RAG. Не меняет системные настройки Ollama:
# OLLAMA_CONTEXT_LENGTH остаётся прежним, тег нужен только RAG-профилю.
#
# Использование:
#   ./scripts/ollama_rag_model.sh                 # тег по умолчанию
#   OLLAMA_RAG_TAG=custom:tag ./scripts/ollama_rag_model.sh
#
# После создания добавьте в .env проекта:
#   export OLLAMA_MODEL="qwen2.5:3b-rag8k"
# затем в агенте: /model ollama

set -eu

TAG="${OLLAMA_RAG_TAG:-qwen2.5:3b-rag8k}"
MODELFILE="$(cd "$(dirname "$0")" && pwd)/Modelfile.qwen25-3b-rag8k"

command -v ollama >/dev/null 2>&1 || {
    echo "Ошибка: ollama не найдена в PATH (установите https://ollama.com)." >&2
    exit 1
}

echo "Создание тега ${TAG} из ${MODELFILE} (FROM qwen2.5:3b, PARAMETER num_ctx 8192)…"
ollama create "$TAG" -f "$MODELFILE"
echo "Готово. Проверка: ollama show ${TAG} | grep num_ctx"
