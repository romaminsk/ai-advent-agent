#!/usr/bin/env bash
#
# Внешний запуск агента: открывает ОТДЕЛЬНОЕ окно Terminal.app
# и запускает в нём внутренний скрипт run-agent.command.
#
# Исходное окно терминала не становится чатом и не ждёт завершения диалога.
# Сам скрипт передаёт только путь к внутреннему скрипту; секреты из .env
# загружаются уже внутри нового окна.
#
# Запуск: ./start-agent.sh

set -u

# Директория проекта определяется по расположению самого скрипта,
# а не по текущему рабочему каталогу; пути с пробелами и кириллицей
# всюду передаются в кавычках.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)" || {
    echo "Ошибка: не удалось определить директорию проекта." >&2
    exit 1
}
INNER_SCRIPT="$SCRIPT_DIR/run-agent.command"

if [ ! -f "$INNER_SCRIPT" ]; then
    echo "Ошибка: не найден внутренний скрипт: $INNER_SCRIPT" >&2
    exit 1
fi
if [ ! -x "$INNER_SCRIPT" ]; then
    echo "Ошибка: внутренний скрипт не является исполняемым: $INNER_SCRIPT" >&2
    echo "Выполните один раз: chmod +x run-agent.command" >&2
    exit 1
fi

# Путь передаётся osascript как аргумент и экранируется средствами
# AppleScript (quoted form) — не встраивается в shell-команду напрямую.
# «do script» без указания окна открывает новое окно Terminal,
# не трогая существующие сессии.
if ! osascript - "$INNER_SCRIPT" <<'APPLESCRIPT'
on run argv
    set innerPath to item 1 of argv
    tell application "Terminal"
        activate
        do script quoted form of innerPath
    end tell
end run
APPLESCRIPT
then
    echo "Ошибка: не удалось открыть окно Terminal.app." >&2
    echo "Возможно, macOS не выдала разрешение на автоматизацию Terminal:" >&2
    echo "Системные настройки → Конфиденциальность и безопасность → Автоматизация." >&2
    echo "Альтернатива: запустите внутренний скрипт напрямую или двойным щелчком в Finder:" >&2
    echo "  \"$INNER_SCRIPT\"" >&2
    exit 1
fi
