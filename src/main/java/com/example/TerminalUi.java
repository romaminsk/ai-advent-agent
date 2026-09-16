package com.example;

import java.util.List;

/**
 * Терминальный интерфейс агента: приветствие, ввод, отображение ответов,
 * статусы ожидания, ошибки и справка.
 *
 * Main координирует работу через этот интерфейс; LlmAgent не зависит
 * от терминала, цветов и библиотек ввода.
 */
public interface TerminalUi extends AutoCloseable {

    /** Тип ввода: команда (без вызова API), сообщение для агента, конец ввода. */
    enum InputType { COMMAND, MESSAGE, EOF }

    /**
     * Ввод пользователя. COMMAND — служебная строка (начинается с «/»,
     * а также exit и quit); MESSAGE — готовый текст запроса, в том числе
     * собранный в многострочном режиме.
     */
    record Input(InputType type, String text) {
        public static Input command(String text) {
            return new Input(InputType.COMMAND, text);
        }

        public static Input message(String text) {
            return new Input(InputType.MESSAGE, text);
        }

        public static Input eof() {
            return new Input(InputType.EOF, "");
        }
    }

    /** Индикатор ожидания ответа; закрывается в finally после ответа или ошибки. */
    interface ProgressIndicator extends AutoCloseable {
        @Override
        void close();
    }

    /** Компактный блок при старте и после /clear (модель передаётся из Config). */
    void showWelcome(String model);

    /**
     * Блокирующее чтение ввода. В многострочном режиме собирает строки
     * в один MESSAGE (до /send или /cancel); служебные строки внутри
     * многострочного режима считаются содержимым сообщения.
     */
    Input nextInput();

    /** Ответ агента: заголовок «Агент», затем исходный текст без изменений. */
    void showMessage(String answer);

    /** Служебное сообщение (подтверждения, подсказки). */
    void showSystem(String text);

    /** Ошибка: выделяется и цветом, и текстом. */
    void showError(String text);

    /** Справка по командам чата (компактный индекс). */
    void showHelp();

    /**
     * Подробная справка /help <команда>: назначение, использование,
     * примеры, эффекты, связанные команды. Неизвестная команда —
     * сообщение с одним шагом («Индекс: /help»).
     */
    default void showCommandHelp(@SuppressWarnings("unused") String name) {
        // По умолчанию — только индекс; реализации с поддержкой
        // подробных справок переопределяют метод.
    }

    /** История текущей беседы с ролями. */
    void showHistory(List<ChatMessage> history);

    /** Подтверждение сброса непустой истории: true, если пользователь согласился. */
    boolean confirmReset();

    /**
     * Подтверждение удаления истории диалога (/clear): subject — «текущего
     * диалога» или «временной беседы измерений». true — только y или yes
     * без учёта регистра; пустой ввод, EOF и любой другой ответ — отказ.
     */
    boolean confirmHistoryClear(String subject);

    /**
     * Подтверждение сравнения (/context compare): два запроса на одной
     * истории, возможный дополнительный запрос на создание резюме.
     * true — только y или yes.
     */
    boolean confirmCompare();

    /**
     * Подтверждение сравнения стратегий (/strategy compare): три запроса
     * на одной истории, возможный дополнительный запрос на подготовку фактов.
     * true — только y или yes.
     */
    boolean confirmStrategyCompare();

    /**
     * Подтверждение очистки блока фактов (/facts clear). true — только y или yes.
     */
    boolean confirmFactsClear();

    /**
     * Подтверждение удаления ветки (/branch delete <имя>); история хвоста
     * ветки будет потеряна безвозвратно. true — только y или yes.
     */
    boolean confirmBranchDelete(String name);

    /**
     * Подтверждение необратимого удаления (/profile clear или /pipeline clear):
     * subject — «профиль пользователя» или «все пайплайны». true — только
     * y или yes.
     */
    boolean confirmProfileClear(String subject);

    /** Индикатор на время HTTP-запроса; используйте в try-with-resources. */
    ProgressIndicator startProgress();

    /** Очистка экрана без изменения истории; без поддержки — ничего не делает. */
    void clearScreen();

    /**
     * Метка активного режима в приглашении ввода например, «измерение»;
     * null возвращает обычное приглашение. Реализации без поддержки
     * метки игнорируют её.
     */
    default void setActiveModeLabel(String label) {
    }

    /**
     * Текущая задача в приглашении ввода (например, «карточка проекта»);
     * null — задача не задана, показывается обычное приглашение.
     * Длинный текст приглашение обрезает само. Реализации без поддержки
     * игнорируют её.
     */
    default void setPromptTask(String task) {
    }

    /** Освобождает терминальные ресурсы, восстанавливает состояние терминала. */
    @Override
    void close();

    /**
     * Компактный индекс справки /help: группы команд в колонках,
     * уложен примерно в один экран 80×24; при узкой ширине — вертикально.
     * Единый для всех терминалов.
     */
    static String chatIndex(int width) {
        record Row(String group, String commands) {}
        Row[] rows = {
                new Row("Память", "/memory /remember /forget /task /history"),
                new Row("Профиль", "/profile /skill /pipeline"),
                new Row("Контекст", "/context /summary /strategy /facts /branch"),
                new Row("Диалог", "/clear /reset"),
                new Row("Режимы", "/mode /multiline /paste /demo"),
                new Row("Статистика", "/tokens /stats /limit"),
                new Row("Прочее", "/help /exit (также exit, quit)"),
        };
        StringBuilder out = new StringBuilder();
        for (Row row : rows) {
            if (width >= 60) {
                out.append(pad(row.group(), 11)).append(row.commands()).append('\n');
            } else {
                out.append(row.group()).append('\n');
                for (String c : splitCommands(row.commands())) {
                    out.append("  ").append(c).append('\n');
                }
            }
        }
        out.append("/help <команда> — описание и примеры\n");
        out.append("Tab — дополнить · ↑↓ — история · Ctrl+C — отменить ввод");
        return out.toString();
    }

    private static java.util.List<String> splitCommands(String row) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String token : row.split("\\s+")) {
            if (token.startsWith("/")) {
                out.add(token);
            }
        }
        return out;
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /**
     * Справка /help <команда>: назначение, использование, примеры,
     * важные эффекты и связанные команды. null — команда не документирована.
     */
    static String chatCommandHelp(String command) {
        String name = command == null ? "" : UiText.lower(command.trim());
        return switch (name) {
            case "/help" -> """
                    /help — справка по командам

                    Использование
                      /help
                      /help <команда>

                    Примеры
                      /help /task
                      /help /context

                    Связано: любой /команда — короткое описание в индексе.""";
            case "/history" -> """
                    /history — история текущего диалога (краткосрочная память)

                    Использование
                      /history

                    Эффекты
                      показываются пары «вы — агент» текущей беседы; /clear удаляет
                      их, перезапуск восстанавливает из файла истории.

                    Связано: /memory, /clear, /reset.""";
            case "/memory" -> """
                    /memory — долговременная память: записи «ключ: значение»

                    Использование
                      /memory

                    Эффекты
                      записи переживают /clear, /reset и перезапуск;
                      удаление записи: /forget <ключ>.

                    Связано: /remember, /forget, /task.""";
            case "/remember" -> """
                    /remember — сохранить запись в долговременную память

                    Использование
                      /remember <текст>

                    Примеры
                      /remember кодовое слово: ЯКОРЬ-42
                      /remember запрет: базы данных
                      /remember бюджет, 620 рублей

                    Эффекты
                      часть до «:»/«=» (до 48 символов, не более 3 слов, без запятой)
                      становится ключом; без разделителя — фраза до первой запятой
                      (если короче трёх слов) или первое слово. Запись хранится
                      в отдельном файле и переживает очистки и перезапуски.
                      Не вызывает API.

                    Связано: /memory, /forget.""";
            case "/forget" -> """
                    /forget — удалить запись долговременной памяти

                    Использование
                      /forget <ключ>

                    Примеры
                      /forget кодовое слово
                      /forget запрет

                    Эффекты
                      точное совпадение — без учёта регистра; частичное совпадение
                      (подстрока) при единственном кандидате тоже удаляется;
                      при нескольких совпадениях показывается список — уточните.

                    Связано: /memory, /remember.""";
            case "/task" -> """
                    /task — состояние задачи: конечный автомат этапов

                    Использование
                      /task start <описание>          — начать (этап planning)
                      /task <текст>                    — короткая форма задания описания
                      /task stage <этап> [причина]     — planning|execution|validation|done
                      /task step <текст>               — текущий шаг (прежний — в выполненные)
                      /task expect <текст>             — ожидаемое действие
                      /task pause · /task resume       — пауза и продолжение
                      /task block · /task unblock      — ожидание внешних данных
                      /task · /task status             — состояние
                      /task clear                      — очистить

                    Примеры
                      /task start подготовить отчёт к среде
                      /task stage execution
                      /task stage execution не сошлись итоговые цифры

                    Эффекты
                      этапы — только вперёд planning → execution → validation → done;
                      возврат validation → execution — с причиной; DONE → planning
                      нельзя (это новая задача). Пауза замораживает этап, шаг и
                      выполненные шаги; возобновление — только /task resume.
                      Состояние подставляется в каждый запрос блоком
                      «СОСТОЯНИЕ ЗАДАЧИ» и живёт до /task clear или /clear
                      (текущий запуск). Не вызывает API.

                    Связано: /facts, /memory.""";
            case "/profile" -> """
                    /profile — профиль пользователя (обращение, стиль, формат, ограничения)

                    Использование
                      /profile
                      /profile name <обращение>
                      /profile style <стиль>
                      /profile format <формат>
                      /profile constraint <ограничение>
                      /profile constraint clear
                      /profile clear

                    Примеры
                      /profile name Алексей
                      /profile style кратко, по делу
                      /profile constraint не используй смайлики

                    Эффекты
                      профиль подставляется в каждый запрос блоком
                      «ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ»; переживает /clear, /reset
                      и перезапуск (отдельный файл profile.json).
                      /profile clear сбрасывает целиком (с подтверждением).
                      Не вызывает API.

                    Это не /mode: /mode меняет лимит генерации.

                    Связано: /remember, /skill, /pipeline.""";
            case "/skill" -> """
                    /skill — скиллы профиля: инструкции для типовых задач

                    Использование
                      /skill add <имя> <описание>
                      /skill list
                      /skill remove <имя>

                    Примеры
                      /skill add "карточка фичи" название, цель, критерии приёмки, шаги

                    Эффекты
                      скиллы хранятся в профиле и подставляются в запрос
                      пайплайном (/pipeline); remove также вычищает скилл
                      из пайплайнов. Не вызывает API.

                    Связано: /profile, /pipeline.""";
            case "/pipeline" -> """
                    /pipeline — пайплайн скиллов для триггера

                    Использование
                      /pipeline <триггер> <скилл1,скилл2,…>
                      /pipeline list
                      /pipeline clear

                    Примеры
                      /pipeline "напиши фичу" "карточка фичи, критерии, шаги"

                    Эффекты
                      если текст запроса содержит слова триггера, в system-
                      сообщение подставляются скиллы в заданном порядке;
                      роли отдельных агентов не вызываются — это подстановка
                      упорядоченных инструкций. Не вызывает API.

                    Связано: /skill, /profile.""";
            case "/clear" -> """
                    /clear — удалить историю текущего диалога

                    Использование
                      /clear

                    Эффекты
                      подтверждение, по умолчанию нет; удаляются история, резюме,
                      задача и факты текущего диалога — в памяти и в файле истории;
                      долговременная память не трогается; статистика сохраняется.

                    Связано: /reset, /memory.""";
            case "/reset" -> """
                    /reset — начать новую беседу

                    Использование
                      /reset

                    Эффекты
                      очищает контекст текущей беседы: пустое состояние сначала
                      записывается на диск, потом очищается память; долговременная
                      память не трогается.

                    Связано: /clear, /branch new.""";
            case "/tokens" -> """
                    /tokens — оценка токенов истории и контекста (без API)

                    Использование
                      /tokens

                    Эффекты
                      локальная оценка (≈) сохранённого архива и контекста
                      следующего запроса; фактический расход — /stats.

                    Связано: /stats, /context.""";
            case "/stats" -> """
                    /stats — фактический расход за сессию (без API)

                    Использование
                      /stats

                    Эффекты
                      попытки API, фактические суммы токенов по назначениям
                      (ответы, факты, суммаризация, сравнение), расчётная
                      стоимость; счётчики сбрасываются при перезапуске.

                    Связано: /tokens, /limit.""";
            case "/limit" -> """
                    /limit — информационный лимит расхода за сессию

                    Использование
                      /limit
                      /limit <число>
                      /limit off

                    Примеры
                      /limit 5000
                      /limit off

                    Эффекты
                      уведомление при превышении; это не жёсткая квота;
                      действует до конца текущего запуска.

                    Связано: /stats.""";
            case "/mode" -> """
                    /mode — профиль ответа

                    Использование
                      /mode
                      /mode fast|balanced|detailed

                    Примеры
                      /mode detailed

                    Эффекты
                      меняет лимит генерации (1024/2048/4096); действует до конца
                      запуска; LLM_MAX_OUTPUT_TOKENS приоритетнее профиля.

                    Связано: /stats.""";
            case "/multiline" -> """
                    /multiline — многострочный ввод

                    Использование
                      /multiline → строки → /send или /cancel

                    Эффекты
                      весь текст уходит одним сообщением; строки внутри режима
                      считаются содержимым, API вызывается только после /send.

                    Связано: /paste.""";
            case "/paste" -> """
                    /paste — вставка длинного текста одним сообщением

                    Использование
                      /paste → вставленный текст → /send или /cancel

                    Эффекты
                      как /multiline, но рассчитан на большой вставленный текст;
                      API не вызывается до /send.

                    Связано: /multiline.""";
            case "/demo" -> """
                    /demo — ручной режим измерения токенов

                    Использование
                      /demo tokens — включить (временная беседа)
                      /demo stats — таблица попыток
                      /demo stop — завершить и вернуться

                    Примеры
                      /demo tokens

                    Эффекты
                      каждый запрос отправляется настоящей модели; авто-сокращение
                      контекста отключено; основная история не изменяется.

                    Связано: /stats, /context compare.""";
            case "/context" -> """
                    /context — режим контекста и сравнение

                    Использование
                      /context
                      /context full|summary
                      /context compare <вопрос>

                    Примеры
                      /context summary
                      /context compare Что было в начале беседы?

                    Эффекты
                      summary создаёт резюме старой части истории служебными
                      запросами; compare — два запроса на одном снимке
                      (API, с подтверждением).

                    Связано: /summary, /strategy, /tokens.""";
            case "/summary" -> """
                    /summary — резюме сжатия истории

                    Использование
                      /summary
                      /summary refresh

                    Эффекты
                      создаётся автоматически по порогу сообщений (режим summary);
                      refresh принудительно обновляет (API); применяется только
                      в стратегии веток при /context summary.

                    Связано: /context, /facts.""";
            case "/strategy" -> """
                    /strategy — стратегии контекста

                    Использование
                      /strategy
                      /strategy sliding-window|facts|branching
                      /strategy compare <вопрос>

                    Примеры
                      /strategy facts
                      /strategy compare Что было в начале беседы?

                    Эффекты
                      переключение не вызывает API; compare — три запроса
                      на одном снимке (API, с подтверждением).

                    Связано: /context, /branch.""";
            case "/facts" -> """
                    /facts — факты рабочей памяти

                    Использование
                      /facts
                      /facts refresh
                      /facts clear

                    Эффекты
                      refresh обновляет факты служебным запросом (API);
                      clear удаляет блок после подтверждения; факты переживают
                      /clear, но стираются /facts clear.

                    Связано: /task, /summary.""";
            case "/branch" -> """
                    /branch — ветки диалога

                    Использование
                      /branch
                      /branch list
                      /branch checkpoint
                      /branch new <имя>
                      /branch switch <имя>
                      /branch delete <имя>

                    Примеры
                      /branch new эксперимент
                      /branch switch main

                    Эффекты
                      checkpoint фиксирует точку возврата; new создаёт ветку
                      от checkpoint; delete необратимо удаляет хвост ветки
                      (с подтверждением).

                    Связано: /strategy, /clear.""";
            case "/exit" -> """
                    /exit — завершение работы

                    Использование
                      /exit (также exit, quit)

                    Примеры
                      /exit

                    Эффекты
                      история беседы сохраняется; Ctrl+D (EOF) делает то же.""";
            default -> null;
        };
    }

    /**
     * Фабрика: интерактивный режим (JLine) — если терминал настоящий и не
     * запрошен --plain; иначе упрощённый режим без цветов и анимации.
     * NO_COLOR и TERM=dumb учитываются автоматически.
     */
    static TerminalUi create(boolean plainRequested) {
        boolean interactive = !plainRequested
                && System.console() != null
                && !"dumb".equalsIgnoreCase(System.getenv("TERM"));
        if (interactive) {
            try {
                return new InteractiveTerminalUi();
            } catch (Exception e) {
                // Не удалось инициализировать терминал — работаем в упрощённом режиме.
                return new PlainTerminalUi();
            }
        }
        return new PlainTerminalUi();
    }

    /**
     * Цвет включается переменной LLM_COLOR: auto (по умолчанию), always, never.
     * NO_COLOR (стандартная переменная) отключает цвета в режиме auto.
     * Неизвестное значение трактуется как auto.
     */
    static boolean colorsEnabled() {
        return colorsEnabled(System.getenv("LLM_COLOR"), System.getenv("NO_COLOR"));
    }

    /** Замена Unicode-служебных глифов на ASCII при LLM_ASCII=1|true. */
    static boolean asciiGlyphs() {
        String value = System.getenv("LLM_ASCII");
        return value != null && (value.strip().equalsIgnoreCase("1")
                || value.strip().equalsIgnoreCase("true"));
    }

    /** Проверка цвета по явным значениям переменных — для тестов. */
    static boolean colorsEnabled(String llmColor, String noColor) {
        if (llmColor != null && !llmColor.isBlank()) {
            return switch (llmColor.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "always" -> true;
                case "never" -> false;
                default -> noColor == null || noColor.isEmpty();
            };
        }
        return noColor == null || noColor.isEmpty();
    }
}
