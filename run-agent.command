#!/usr/bin/env bash
#
# Внутренний запуск агента: подготовка окружения, сборка и запуск CLI.
# Запускается из start-agent.sh (в новом окне Terminal.app) или двойным
# щелчком в Finder; можно вызвать и напрямую из терминала.
#
# Требования проверяются на месте: JDK 21+ и Maven (или ./mvnw).
# Секреты берутся из локального .env и нигде не печатаются.

set -u

# Этап запоминается для сообщений об ошибках.
STAGE="Определение директории проекта"

# Директория проекта — по расположению самого скрипта (не по рабочему
# каталогу): одинаково работает из Terminal, Finder и через start-agent.sh.
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)" || {
    echo "Ошибка: не удалось определить директорию проекта." >&2
    exit 1
}
cd "$PROJECT_DIR" || exit 1

# В терминале, открытом из Finder, локаль может быть не задана —
# без UTF-8 русский текст чата искажается. Меняем только если не задана.
if [ -z "${LANG:-}" ] && [ -z "${LC_ALL:-}" ]; then
    export LANG="en_US.UTF-8"
fi

# Сообщение об ошибке; ожидание Enter только при интерактивном stdin.
fail() {
    echo "Ошибка: $1" >&2
    echo "Этап: $STAGE" >&2
    if [ -t 0 ]; then
        echo "Нажмите Enter, чтобы закрыть запуск."
        read -r _ || true
    fi
    exit "${2:-1}"
}

# Старшая версия Java из вывода "java -version" (пусто, если не удалось определить).
java_major() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

echo "Проверка окружения…"
STAGE="Проверка окружения"

# .env — доверенный локальный файл: source исполняет его shell-код.
# Он игнорируется Git (.gitignore) и не должен попадать в репозиторий.
if [ ! -f ".env" ]; then
    fail "Не найден файл .env в директории проекта: $PROJECT_DIR. Создайте его по образцу из README.md."
fi
if [ ! -r ".env" ]; then
    fail "Файл .env недоступен для чтения. Проверьте права доступа."
fi
# shellcheck disable=SC1091
source .env || fail "Не удалось загрузить .env (возможно, в файле есть синтаксическая ошибка)."

# Проверяем только наличие значений; сами значения не печатаем.
for required_var in LLM_API_KEY LLM_API_URL LLM_MODEL; do
    if [ -z "${!required_var:-}" ]; then
        fail "Обязательная переменная $required_var не задана или пуста. Заполните .env (значение не проверяется на этом этапе)."
    fi
done

# Java: сначала java из PATH, при необходимости — стандартный поиск macOS.
MAJOR_VERSION=""
if command -v java >/dev/null 2>&1; then
    MAJOR_VERSION="$(java_major "$(command -v java)")"
fi
if [ -z "$MAJOR_VERSION" ] || [ "$MAJOR_VERSION" -lt 21 ]; then
    if [ -x /usr/libexec/java_home ]; then
        FOUND_JAVA_HOME="$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)"
        if [ -n "$FOUND_JAVA_HOME" ] && [ -x "$FOUND_JAVA_HOME/bin/java" ]; then
            FOUND_MAJOR="$(java_major "$FOUND_JAVA_HOME/bin/java")"
            if [ -n "$FOUND_MAJOR" ] && [ "$FOUND_MAJOR" -ge 21 ]; then
                export JAVA_HOME="$FOUND_JAVA_HOME"
                export PATH="$FOUND_JAVA_HOME/bin:$PATH"
                MAJOR_VERSION="$FOUND_MAJOR"
            fi
        fi
    fi
fi
if [ -z "$MAJOR_VERSION" ] || [ "$MAJOR_VERSION" -lt 21 ]; then
    fail "Не найден JDK 21 или новее. Установите JDK 21+ (например, Temurin или Corretto) и убедитесь, что java доступна в PATH."
fi

# Maven выбирает JDK по JAVA_HOME; она может указывать на старую JDK,
# выбранную в IDEA. Если она несовместима — не мешаем: Maven возьмёт java из PATH.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_HOME_MAJOR="$(java_major "$JAVA_HOME/bin/java")"
    if [ -z "$JAVA_HOME_MAJOR" ] || [ "$JAVA_HOME_MAJOR" -lt 21 ]; then
        export JAVA_HOME=""
    fi
fi

# Maven Wrapper предпочтительнее, если он есть и исправен; иначе системный Maven.
if [ -x "./mvnw" ]; then
    MVN_CMD="./mvnw"
elif command -v mvn >/dev/null 2>&1; then
    MVN_CMD="mvn"
else
    fail "Maven не найден. Установите Maven (например: brew install maven) или добавьте Maven Wrapper (mvnw) в проект."
fi

echo "Сборка проекта…"
STAGE="Сборка проекта"
# Сборка без запуска тестов: в проекте только локальные проверки (SelfTest
# запускается отдельно), платных обращений к LLM при сборке нет.
# При ошибке Maven печатает диагностику сам; запуск не выполняется.
if ! "$MVN_CMD" -q clean package; then
    fail "Сборка завершилась с ошибкой — диагностика выше. Исправьте ошибки компиляции и запустите снова."
fi

echo "Запуск агента…"
STAGE="Запуск агента"
RUN_EXIT_CODE=0
# Классы только что собраны: при неудачной сборке мы сюда не доходим.
# stdin нового терминала передаётся CLI напрямую (без pipe и фонового запуска).
"$MVN_CMD" -q exec:java || RUN_EXIT_CODE=$?

if [ "$RUN_EXIT_CODE" -eq 0 ]; then
    echo "Агент завершён."
else
    echo "Ошибка: этап «Запуск агента…» завершился с кодом $RUN_EXIT_CODE." >&2
fi

# Даём прочитать итог в новом окне; ожидание — только при интерактивном stdin.
# Исходный код завершения сохраняется независимо от ожидания Enter.
if [ -t 0 ]; then
    echo "Нажмите Enter, чтобы закрыть запуск."
    read -r _ || true
fi
exit "$RUN_EXIT_CODE"
