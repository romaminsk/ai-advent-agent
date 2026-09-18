package com.example;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.impl.completer.StringsCompleter;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * Интерактивный терминальный интерфейс на JLine: редактирование строки,
 * история ввода в памяти (стрелки вверх/вниз), автодополнение команд по Tab,
 * корректная обработка Ctrl+C (отмена ввода) и Ctrl+D (выход),
 * индикатор ожидания со спиннером, спокойное цветовое оформление.
 *
 * Не очищает экран при старте и не включает alternate screen —
 * прокрутка истории средствами терминала сохраняется.
 */
final class InteractiveTerminalUi implements TerminalUi {

    private static final String MULTILINE_PROMPT = "… › ";
    private static final String MULTILINE_PROMPT_ASCII = "... > ";

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String DIM = "\u001b[2m";
    private static final String CYAN = "\u001b[36m";
    private static final String BLUE = "\u001b[34m";
    private static final String RED = "\u001b[31m";
    private static final String YELLOW = "\u001b[33m";

    private final Terminal terminal;
    private final LineReader reader;
    private final boolean colors;
    private final boolean ascii;
    private final String promptUser;
    private volatile ProgressSpinner activeSpinner;

    /** Метка активного режима в приглашении (null — обычный режим). */
    private volatile String activeModeLabel;

    /** Текущая задача в приглашении (null — не задана). */
    private volatile String promptTask;

    /** Лимит длины задачи в приглашении; длиннее — обрезается с многоточием. */
    private static final int PROMPT_TASK_MAX_LENGTH = 24;

    InteractiveTerminalUi() throws Exception {
        this.terminal = TerminalBuilder.builder().system(true).build();
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(
                        "/help", "/history", "/tokens", "/stats", "/limit", "/limit off",
                        "/demo", "/demo tokens", "/demo stats", "/demo stop",
                        "/paste", "/reset", "/clear", "/multiline", "/mode",
                        "/mode fast", "/mode balanced", "/mode detailed",
                        "/context", "/context full", "/context summary",
                        "/context compare ", "/summary", "/summary refresh",
                        "/strategy", "/strategy sliding-window", "/strategy facts",
                        "/strategy branching", "/strategy compare ",
                        "/facts", "/facts refresh", "/facts clear",
                        "/branch", "/branch list", "/branch checkpoint",
                        "/branch new ", "/branch switch ", "/branch delete ",
                        "/memory", "/remember ", "/forget ", "/task ", "/task clear",
                        "/invariant", "/invariant add ", "/invariant remove ",
                        "/invariant clear",
                        "/task invariant", "/task invariant add ",
                        "/task invariant remove ", "/task invariant clear",
                        "/exit", "exit", "quit"))
                .build();
        // История ввода хранится только в памяти: файл истории не подключается.
        this.colors = TerminalUi.colorsEnabled();
        this.ascii = TerminalUi.asciiGlyphs();
        this.promptUser = asciiPrompt("Вы ›");
        // При аварийном завершении (например, Ctrl+C во время запроса)
        // возвращаем курсор, чтобы не оставлять терминал «без курсора».
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ProgressSpinner spinner = activeSpinner;
            if (spinner != null) {
                spinner.close();
            }
        }, "agent-ui-cleanup"));
    }

    @Override
    public void setActiveModeLabel(String label) {
        this.activeModeLabel = label;
    }

    @Override
    public void setPromptTask(String task) {
        this.promptTask = task == null || task.isBlank() ? null : task.trim();
    }

    /** Приглашение обычного ввода: режим и задача показываются только когда есть. */
    private String inputPrompt() {
        StringBuilder labels = new StringBuilder();
        if (activeModeLabel != null && !activeModeLabel.isEmpty()) {
            labels.append('[').append(activeModeLabel).append(']');
        }
        if (promptTask != null) {
            if (labels.length() > 0) {
                labels.append(' ');
            }
            labels.append('[').append(truncateForPrompt(promptTask)).append(']');
        }
        if (labels.length() == 0) {
            return promptUser;
        }
        String text = glyph("Вы " + labels + " ›");
        return colors ? CYAN + text + RESET + " " : text + " ";
    }

    /**
     * Усечение по видимой ширине: сначала сокращается задача,
     * затем имя ветки; «›» не переносится отдельно.
     */
    private String truncateForPrompt(String task) {
        int width = terminal.getWidth();
        if (width <= 0) {
            return task.length() <= PROMPT_TASK_MAX_LENGTH
                    ? task : UiText.truncate(task, PROMPT_TASK_MAX_LENGTH);
        }
        // Резерв: «Вы [» + «] ›» + место для ввода — prompt остаётся коротким якорем.
        int maxHeight = Math.max(6, width - 10);
        int branchWidth = activeModeLabel == null ? 0 : UiText.visibleWidth(activeModeLabel);
        int taskWidth = UiText.visibleWidth(task);
        if (branchWidth + 3 + taskWidth <= maxHeight) {
            return task;
        }
        // Ширина 40: иероглифы считаются как 2; минимум даёт читаемые 6 символов.
        return UiText.truncate(task, Math.max(6, maxHeight - branchWidth - 3));
    }

    /**
     * Компактный старт: две строки до приглашения; декоративная линия
     * и очевидное («Начата новая беседа», «Контекст … включён») не печатаются.
     * Статус восстановления — отдельной строкой через showSystem (Main).
     */
    @Override
    public void showWelcome(String model) {
        PrintWriter out = terminal.writer();
        out.println();
        out.println(colors ? BOLD + "AI Advent Agent" + RESET + accent("  ·  " + model)
                : "AI Advent Agent  ·  " + model);
        out.println(dim("/help — команды"));
        out.flush();
    }

    /** Первый запуск: приветствие плюс короткий онбординг (4–5 строк). */
    @Override
    public void showWelcome(String model, boolean firstLaunch) {
        showWelcome(model);
        if (firstLaunch) {
            PrintWriter out = terminal.writer();
            out.println(TerminalUi.firstRunOnboarding());
            out.flush();
        }
    }

    @Override
    public boolean interactiveMenus() {
        return true;
    }

    @Override
    public Input nextInput() {
        while (true) {
            String line;
            try {
                line = reader.readLine(inputPrompt());
            } catch (UserInterruptException e) {
                // Ctrl+C на строке ввода: отменяем текущий ввод, показываем новое приглашение.
                showSystem("Ввод отменён (Ctrl+C).");
                continue;
            } catch (EndOfFileException e) {
                // Ctrl+D: завершение приложения.
                return Input.eof();
            }
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("/multiline")) {
                Input composed = readMultiline("Многострочный режим: введите текст сообщения. "
                        + "/send — отправить, /cancel — отмена.");
                if (composed.type() == InputType.MESSAGE || composed.type() == InputType.EOF) {
                    return composed;
                }
                continue; // /cancel — новое обычное приглашение
            }
            if (trimmed.equalsIgnoreCase("/paste")) {
                Input composed = readMultiline("Вставка длинного текста: вставьте текст построчно. "
                        + "Весь текст уйдёт одним сообщением; отдельная строка /send — отправить, "
                        + "отдельная строка /cancel — отменить ввод без запроса к API.");
                if (composed.type() == InputType.MESSAGE || composed.type() == InputType.EOF) {
                    return composed;
                }
                continue;
            }
            if (trimmed.startsWith("/")) {
                return Input.command(trimmed);
            }
            if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
                return Input.command(trimmed.toLowerCase(Locale.ROOT));
            }
            return Input.message(trimmed);
        }
    }

    /** Многострочный режим (/multiline и /paste): строки собираются до /send или /cancel. */
    private Input readMultiline(String intro) {
        showSystem(intro);
        StringBuilder text = new StringBuilder();
        String multilinePrompt = colors ? DIM + glyph(MULTILINE_PROMPT) + RESET : glyph(MULTILINE_PROMPT);
        while (true) {
            String line;
            try {
                line = reader.readLine(multilinePrompt);
            } catch (UserInterruptException e) {
                showSystem("Набор отменён (Ctrl+C).");
                return Input.command("/cancel");
            } catch (EndOfFileException e) {
                return Input.eof();
            }
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.equals("/send")) {
                if (text.length() == 0 || text.toString().isBlank()) {
                    showSystem("Пустой текст не отправляется. Продолжайте набор или /cancel.");
                    continue;
                }
                return Input.message(text.toString());
            }
            if (trimmed.equals("/cancel")) {
                showSystem("Набор отменён.");
                return Input.command("/cancel");
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
        }
    }

    /** Ответ агента: маркер «◆», пустая строка до/после, тело без префиксов. */
    @Override
    public void showMessage(String answer) {
        PrintWriter out = terminal.writer();
        // Пустая строка до ответа отделяет его от ввода пользователя.
        out.println();
        out.println(colors ? BOLD + glyph("◆") + RESET : glyph("◆"));
        // Исходное содержание ответа: переносы, отступы, Markdown; только обезвреживание.
        // С цветом Markdown отображается структурно (заголовки, списки, код),
        // без цвета — как есть, чтобы не терять читаемость.
        String sanitized = AnsiSanitizer.sanitize(answer);
        out.println(colors ? MarkdownTerminal.render(sanitized) : sanitized);
        out.println();
        out.flush();
    }

    /** Служебные сообщения: семантика маркера, цвет — добавочный канал. */
    @Override
    public void showSystem(String text) {
        String clean = text == null ? "" : text;
        terminal.writer().println(colors ? TerminalUi.categoryColor(glyph(clean)) : glyph(clean));
        terminal.writer().flush();
    }

    /** Первый маркер строки задаёт цвет: ! → жёлтый, ✓ → зелёный, ? → акцент. */
    private String colorByMarker(String text, String marker, String color) {
        if (!colors || !text.startsWith(marker)) {
            return dim(text);
        }
        return color + text + RESET;
    }

    @Override
    public void showError(String text) {
        PrintWriter out = terminal.writer();
        // Первая строка — красный маркер «×»; следующий шаг (если есть) — dim.
        String clean = AnsiSanitizer.sanitize(text == null ? "" : text);
        String[] lines = clean.split("\n", 2);
        out.println(colors ? RED + BOLD + glyph("×") + " " + lines[0] + RESET
                : glyph("×") + " " + lines[0]);
        if (lines.length > 1) {
            out.println(dim(lines[1]));
        }
        out.flush();
    }

    @Override
    public void showHelp() {
        PrintWriter out = terminal.writer();
        out.print(TerminalUi.shortHelp());
        out.println();
        out.flush();
    }

    @Override
    public void showFullHelp() {
        PrintWriter out = terminal.writer();
        // Индекс: заголовки групп — акцент, команды — обычный текст.
        out.print(colors ? "Команды\n" : "Команды\n");
        String index = TerminalUi.chatIndex(Math.max(terminal.getWidth(), 40));
        for (String line : index.split("\n", -1)) {
            out.println(groupHeadingsStyled(line));
        }
        out.flush();
    }

    /** Заголовки групп справки (не начинаются с пробела или [/↑T/<) — accent. */
    private String groupHeadingsStyled(String line) {
        boolean heading = !line.isBlank() && !line.startsWith(" ")
                && !line.startsWith("/") && !line.startsWith("Tab")
                && !line.startsWith("/help <");
        return heading ? accent(line) : line;
    }

    /** /help <команда>: заголовок bold, разметка «Связано:» приглушена. */
    @Override
    public void showCommandHelp(String name) {
        String text = TerminalUi.chatCommandHelp(name);
        PrintWriter out = terminal.writer();
        if (text == null) {
            out.println(dim("Нет подробной справки по «" + name
                    + "». Индекс команд: /help"));
            out.flush();
            return;
        }
        String[] lines = AnsiSanitizer.sanitize(text).split("\n", -1);
        out.println();
        out.println(colors ? BOLD + lines[0] + RESET : lines[0]);
        for (int i = 1; i < lines.length; i++) {
            out.println(lines[i].startsWith("Связано") ? dim(lines[i]) : lines[i]);
        }
        out.flush();
    }

    @Override
    public void showHistory(List<ChatMessage> history) {
        PrintWriter out = terminal.writer();
        if (history.isEmpty()) {
            out.println(dim("История диалога пуста."));
            out.flush();
            return;
        }
        out.println(dim("Сохранённые сообщения (" + history.size() / 2 + " пар):"));
        for (ChatMessage message : history) {
            String role = "user".equals(message.role())
                    ? (colors ? CYAN + "Вы      " + RESET : "Вы      ")
                    : (colors ? BLUE + "Агент   " + RESET : "Агент   ");
            out.println("  " + role + " " + AnsiSanitizer.sanitize(message.content()));
        }
        out.flush();
    }

    /**
     * Единый формат подтверждения: одна строка «? <вопрос> <область/необратимость>
     * [y/N]». Только y или yes (без учёта регистра) подтверждают; Enter,
     * пустой ввод, EOF и любой другой ответ выбирают «нет». Детали
     * о постоянно хранимых данных — в /help <команда>.
     */
    private boolean confirm(String question, String consequence) {
        String line = glyph("? " + question.strip() + (consequence.isBlank()
                ? "" : " " + consequence.strip()) + " [y/N]");
        try {
            String answer = reader.readLine(colors ? DIM + line + RESET : line);
            String trimmed = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
            return trimmed.equals("y") || trimmed.equals("yes");
        } catch (UserInterruptException | EndOfFileException e) {
            // Ctrl+C и EOF безопасно отменяют (по умолчанию No).
            return false;
        }
    }

    @Override
    public boolean confirmReset() {
        return confirm("Начать новую беседу и очистить историю текущей?",
                "Это нельзя отменить.");
    }

    /** Подтверждение удаления истории (/clear). */
    @Override
    public boolean confirmHistoryClear(String subject) {
        return confirm("Очистить историю " + subject + "?", "Это нельзя отменить.");
    }

    /** Подтверждение сравнения (/context compare). */
    @Override
    public boolean confirmCompare() {
        return confirm("Выполнить два API-запроса (расход токенов или квоты)?", null);
    }

    /** Подтверждение сравнения стратегий (/strategy compare). */
    @Override
    public boolean confirmStrategyCompare() {
        return confirm("Выполнить API-запросы для каждой стратегии? Это расход токенов.",
                null);
    }

    /** Подтверждение очистки фактов (/facts clear). */
    @Override
    public boolean confirmFactsClear() {
        return confirm("Очистить весь блок фактов текущего диалога?",
                "Это нельзя отменить.");
    }

    /** Подтверждение удаления ветки (/branch delete <имя>). */
    @Override
    public boolean confirmBranchDelete(String name) {
        return confirm("Ветка «" + name + "» необратимо удаляется.", "Продолжить?");
    }

    /** Подтверждение необратимого удаления /profile clear, /pipeline clear. */
    @Override
    public boolean confirmProfileClear(String subject) {
        return confirm("Сбросить " + subject + "?", "Это нельзя отменить.");
    }

    @Override
    public ProgressIndicator startProgress() {
        ProgressSpinner spinner = new ProgressSpinner(terminal.writer(), true);
        activeSpinner = spinner;
        return spinner;
    }

    @Override
    public void clearScreen() {
        PrintWriter out = terminal.writer();
        out.print("\u001b[2J\u001b[H");
        out.flush();
    }

    @Override
    public void close() {
        try {
            terminal.close();
        } catch (Exception e) {
            // Терминал мог быть уже закрыт — это не ошибка приложения.
        }
    }

    /** Общий UI-слой: при LLM_ASCII все служебные глифы переводятся в ASCII. */
    private String glyph(String text) {
        return UiText.asciify(text, ascii);
    }

    private String asciiPrompt(String text) {
        String plain = ascii ? text.replace("›", ">") : text;
        return colors ? CYAN + plain + RESET + " " : plain + " ";
    }

    private String dim(String text) {
        return colors ? DIM + text + RESET : text;
    }

    /** Акцент: только для коротких ключей — prompt, маркер ответа, заголовки. */
    private String accent(String text) {
        return colors ? CYAN + text + RESET : text;
    }
}
