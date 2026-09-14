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

    /** Справка по командам чата. */
    void showHelp();

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

    /** Освобождает терминальные ресурсы, восстанавливает состояние терминала. */
    @Override
    void close();

    /**
     * Единый текст справки по командам чата для всех терминалов:
     * дублирование строк в разных реализациях не допускается.
     */
    static String chatHelp() {
        return "Команды:\n"
                + "  /help      — справка\n"
                + "  /history   — история текущего диалога (краткосрочная память)\n"
                + "  /memory    — долговременная память: записи «ключ: значение» (без вызова API)\n"
                + "  /remember <текст> — сохранить запись: «ключ: значение» или «ключ = значение»;\n"
                + "             без разделителя ключом станет первое слово или фраза до первой запятой,\n"
                + "             остальное — значение. Храните короткие однозначные факты, по одному на запись\n"
                + "  /forget <ключ>    — удалить запись (поддерживается частичное совпадение;\n"
                + "             при нескольких совпадениях список ключей для уточнения)\n"
                + "  /task <текст>     — задать задачу рабочей памяти; /task clear — очистить\n"
                + "  /tokens    — оценка токенов истории, контекста и резервов (без вызова API)\n"
                + "  /stats     — фактический расход токенов и стоимость за сессию (без вызова API)\n"
                + "  /limit     — лимит расхода за сессию: /limit показать, /limit <число>, /limit off\n"
                + "  /reset     — очистить контекст и начать новую беседу\n"
                + "  /clear     — удалить историю текущего диалога (подтверждение y/yes; статистика сессии сохраняется)\n"
                + "  /multiline — многострочный ввод (/send — отправить, /cancel — отмена)\n"
                + "  /paste     — вставка длинного текста одним сообщением (/send, /cancel)\n"
                + "  /demo      — режим измерения токенов: /demo tokens, /demo stats, /demo stop\n"
                + "  /mode      — профиль ответа: /mode показать, /mode fast|balanced|detailed\n"
                + "  /context   — режим контекста: /context показать, /context full|summary,\n"
                + "             /context compare <вопрос> — сравнение двух запросов (API, с подтверждением)\n"
                + "  /summary   — резюме сжатия: /summary показать, /summary refresh — обновить (API)\n"
                + "  /strategy  — стратегии контекста: /strategy показать,\n"
                + "             /strategy sliding-window|facts|branching — переключить (без API),\n"
                + "             /strategy compare <вопрос> — сравнение трёх стратегий (API, с подтверждением)\n"
                + "  /facts     — факты рабочей памяти: /facts показать, /facts refresh — обновить (API),\n"
                + "             /facts clear — очистить (подтверждение)\n"
                + "  /branch    — ветки диалога: /branch list, /branch checkpoint,\n"
                + "             /branch new <имя>, /branch switch <имя>, /branch delete <имя> (подтверждение)\n"
                + "  /exit      — завершение (также exit, quit)\n"
                + "Стрелки вверх/вниз — предыдущие сообщения, Tab — автодополнение команд.";
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

    /** NO_COLOR отключает цвета (стандартная переменная окружения). */
    static boolean colorsEnabled() {
        String noColor = System.getenv("NO_COLOR");
        return noColor == null || noColor.isEmpty();
    }
}
