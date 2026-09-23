#!/usr/bin/env bash
#
# Однократная установка команды ai-agent в ~/.local/bin.
#
# Что делает:
#   1. собирает проект в исполняемый JAR со всеми зависимостями (Maven Shade);
#   2. генерирует launcher ai-agent, привязанный к текущему расположению проекта;
#   3. устанавливает его в ~/.local/bin после успешной сборки.
#
# Повторный запуск обновляет команду (пересобирает и переустанавливает).
# После переноса проекта в другое место нужна повторная установка.
# Без sudo; ключ API внутрь launcher не записывается — он берётся из .env при запуске.

set -u

fail() {
    echo "Ошибка: $1" >&2
    exit "${2:-1}"
}

# Директория проекта — по расположению скрипта.
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)" || {
    fail "не удалось определить директорию проекта"
}
cd "$PROJECT_DIR" || fail "не удалось перейти в директорию проекта"

# Java 21+ для сборки.
java_major() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}
MAJOR_VERSION="$(java_major "$(command -v java 2>/dev/null || echo /nonexistent)")" || true
if [ -z "$MAJOR_VERSION" ] || [ "$MAJOR_VERSION" -lt 21 ]; then
    if [ -x /usr/libexec/java_home ]; then
        FOUND_JAVA_HOME="$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)"
        if [ -n "$FOUND_JAVA_HOME" ] && [ -x "$FOUND_JAVA_HOME/bin/java" ]; then
            export PATH="$FOUND_JAVA_HOME/bin:$PATH"
        fi
    fi
fi
command -v java >/dev/null 2>&1 || fail "не найден JDK: установите JDK 21+ для сборки"
MAJOR_VERSION="$(java_major "$(command -v java)")" || true
if [ -z "$MAJOR_VERSION" ] || [ "$MAJOR_VERSION" -lt 21 ]; then
    fail "требуется JDK 21 или новее"
fi

# Maven Wrapper предпочтительнее, иначе системный Maven.
if [ -x "./mvnw" ]; then
    MVN_CMD="./mvnw"
elif command -v mvn >/dev/null 2>&1; then
    MVN_CMD="mvn"
else
    fail "не найден Maven. Установите Maven (например: brew install maven) или добавьте Maven Wrapper (mvnw)"
fi

echo "Сборка проекта…"
# Зависимости берутся из локального репозитория Maven; для первой сборки
# может потребоваться интернет (см. README). Платных обращений к LLM нет.
if ! "$MVN_CMD" -q clean package; then
    fail "сборка не удалась — диагностика выше; команда ai-agent не установлена"
fi
JAR_PATH="$(ls -t target/*.jar 2>/dev/null | grep -v 'original-' | head -n 1 || true)"
if [ -z "$JAR_PATH" ]; then
    fail "сборка не создала JAR в target/; команда ai-agent не установлена"
fi
JAR_NAME="$(basename "$JAR_PATH")"

# Храним копию JAR рядом с launcher, а не в каталоге проекта. Поэтому
# установленный MCP stdio-процесс продолжает запускаться, если проект временно
# переименован или недоступен.
INSTALL_DIR="${HOME}/.local/share/ai-advent-agent"
INSTALL_JAR="$INSTALL_DIR/ai-agent.jar"
mkdir -p "$INSTALL_DIR" 2>/dev/null || fail "не удалось создать каталог $INSTALL_DIR"
cp "$JAR_PATH" "$INSTALL_JAR" || fail "не удалось установить JAR в $INSTALL_JAR"
chmod 600 "$INSTALL_JAR" 2>/dev/null || true

# Каталог установки — только для пользователя, без sudo.
BIN_DIR="${HOME}/.local/bin"
TARGET="$BIN_DIR/ai-agent"
mkdir -p "$BIN_DIR" 2>/dev/null || fail "не удалось создать каталог $BIN_DIR"

# Не перезаписываем чужую команду без согласия. Наш launcher помечен
# служебной строкой AI_ADVENT_AGENT_LAUNCHER — свой файл обновляем молча.
if [ -e "$TARGET" ] && ! grep -q "AI_ADVENT_AGENT_LAUNCHER" "$TARGET" 2>/dev/null; then
    if [ -t 0 ]; then
        printf "Файл %s уже существует и создан не этим установщиком. Перезаписать? [y/N] " "$TARGET"
        read -r answer || answer=""
        case "$answer" in
            y|Y|yes|да|Да) ;;
            *) echo "Установка отменена. Существующий файл не изменён."; exit 1 ;;
        esac
    else
        fail "$TARGET уже существует и создан не этим установщиком. Запустите установку в интерактивном терминале или удалите файл вручную."
    fi
fi

# Launcher: директория проекта вшивается на момент установки (пути с пробелами
# и кириллицей экранируются через printf %q). Сам ключ нигде не хранится:
# он читается из .env при каждом запуске и им же передаётся в Java как env.
{
    printf '#!/usr/bin/env bash\n'
    printf '# AI_ADVENT_AGENT_LAUNCHER v1 — установлен install-cli.sh из проекта ai-advent-agent.\n'
    printf '# JAR установлен отдельно от проекта; повторный install-cli.sh обновляет его.\n'
    printf 'set -u\n'
    printf 'PROJECT_DIR=%q\n' "$PROJECT_DIR"
    printf 'INSTALL_JAR=%q\n' "$INSTALL_JAR"
    cat <<'LAUNCHER_BODY'

JAR="$INSTALL_JAR"
if [ ! -f "$JAR" ]; then
    echo "Ошибка: JAR не найден: $JAR" >&2
    echo "Выполните ./install-cli.sh из директории проекта для пересборки и переустановки." >&2
    exit 1
fi

# Совместимая Java: сначала java из PATH, затем стандартный поиск macOS.
java_major() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}
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
    echo "Ошибка: не найден JDK 21 или новее. Установите JDK 21+ и убедитесь, что java доступна в PATH." >&2
    exit 1
fi

# --help не требует .env и ключа: сразу передаём управление приложению.
HELP_ONLY=0
for arg in "$@"; do
    if [ "$arg" = "--help" ] || [ "$arg" = "-h" ]; then
        HELP_ONLY=1
    fi
done

if [ "$HELP_ONLY" -eq 0 ]; then
    ENV_FILE="$PROJECT_DIR/.env"
    if [ -f "$ENV_FILE" ]; then
        # .env — доверенный локальный файл (игнорируется Git). Загружаем без eval:
        # поддерживается формат существующего файла — строки вида export KEY="value".
        # Значения из .env имеют приоритет над одноимёнными переменными текущего shell.
        while IFS= read -r env_line || [ -n "$env_line" ]; do
            env_line="${env_line%$'\r'}"
            case "$env_line" in ''|\#*) continue ;; esac
            env_key="${env_line%%=*}"
            env_value="${env_line#*=}"
            case "$env_key" in export\ *) env_key="${env_key#export }" ;; esac
            env_key="${env_key//[[:space:]]/}"
            env_value="${env_value#\"}"; env_value="${env_value%\"}"
            env_value="${env_value#\'}"; env_value="${env_value%\'}"
            if [ -n "$env_key" ]; then
                export "$env_key=$env_value"
            fi
        done < "$ENV_FILE"
    else
        echo "Предупреждение: файл .env не найден в $PROJECT_DIR — переменные окружения не загружены." >&2
    fi
fi

# exec передаёт управление Java напрямую: текущий терминал и каталог не меняются,
# stdin не перехватывается, после выхода пользователь возвращается в свой shell.
exec java -jar "$JAR" "$@"
LAUNCHER_BODY
} > "$TARGET" || fail "не удалось записать $TARGET"
chmod 755 "$TARGET"

echo "Установлено: $TARGET"
echo "Артефакт: $JAR_PATH"

# Проверка PATH: дочерний процесс не может изменить PATH родительского терминала,
# поэтому выводим инструкцию, если каталог недоступен.
case ":$PATH:" in
    *":$BIN_DIR:"*)
        echo "Каталог ~/.local/bin уже в PATH — команда ai-agent готова к использованию."
        ;;
    *)
        echo
        echo "Каталог ~/.local/bin отсутствует в PATH. Настройте вручную:"
        echo "  текущая сессия:   export PATH=\"\$HOME/.local/bin:\$PATH\""
        echo "  постоянно (bash): добавьте ту же строку в ~/.bashrc у Ubuntu или"
        echo "  в ~/.zshrc на macOS и выполните: source ~/.bashrc (или ~/.zshrc)"
        echo "После PATH-настройки запустите: ai-agent"
        ;;
esac
