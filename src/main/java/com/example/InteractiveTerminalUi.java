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

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String DIM = "\u001b[2m";
    private static final String CYAN = "\u001b[36m";
    private static final String BLUE = "\u001b[34m";
    private static final String RED = "\u001b[31m";

    private final Terminal terminal;
    private final LineReader reader;
    private final boolean colors;
    private final String promptUser;
    private volatile ProgressSpinner activeSpinner;

    /** Метка активного режима в приглашении (null — обычный режим). */
    private volatile String activeModeLabel;

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
                        "/exit", "exit", "quit"))
                .build();
        // История ввода хранится только в памяти: файл истории не подключается.
        this.colors = TerminalUi.colorsEnabled();
        this.promptUser = colors ? CYAN + "Вы ›" + RESET + " " : "Вы › ";
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

    /** Приглашение обычного ввода с меткой активного режима, если она есть. */
    private String inputPrompt() {
        if (activeModeLabel == null) {
            return promptUser;
        }
        String text = "Вы [" + activeModeLabel + "] ›";
        return colors ? CYAN + text + RESET + " " : text + " ";
    }

    @Override
    public void showWelcome(String model) {
        PrintWriter out = terminal.writer();
        int width = Math.min(Math.max(terminal.getWidth(), 20), 44);
        out.println();
        out.println(BOLD + "AI Advent Agent" + RESET + dim(" · модель: " + model));
        out.println(dim("Контекст текущей беседы включён · /help — команды · /exit — выход"));
        out.println(dim("─".repeat(width)));
        out.flush();
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
        String multilinePrompt = colors ? DIM + MULTILINE_PROMPT + RESET : MULTILINE_PROMPT;
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

    @Override
    public void showMessage(String answer) {
        PrintWriter out = terminal.writer();
        out.println((colors ? BOLD + BLUE : "") + "Агент" + (colors ? RESET : "") + " ─────");
        // Исходное содержание ответа: переносы, отступы, Markdown; только обезвреживание.
        out.println(AnsiSanitizer.sanitize(answer));
        out.println();
        out.flush();
    }

    @Override
    public void showSystem(String text) {
        terminal.writer().println(dim(text));
        terminal.writer().flush();
    }

    @Override
    public void showError(String text) {
        PrintWriter out = terminal.writer();
        out.println((colors ? RED + BOLD : "") + "Ошибка:" + (colors ? RESET : "") + " " + text);
        out.flush();
    }

    @Override
    public void showHelp() {
        PrintWriter out = terminal.writer();
        out.println("Команды:");
        out.println("  /help      — справка");
        out.println("  /history   — история текущего диалога");
        out.println("  /tokens    — оценка токенов истории, контекста и резервов (без вызова API)");
        out.println("  /stats     — фактический расход токенов и стоимость за сессию (без вызова API)");
        out.println("  /limit     — лимит расхода за сессию: /limit показать, /limit <число>, /limit off");
        out.println("  /reset     — очистить контекст и начать новую беседу");
        out.println("  /clear     — удалить историю текущего диалога (подтверждение y/yes; статистика сессии сохраняется)");
        out.println("  /multiline — многострочный ввод (/send — отправить, /cancel — отмена)");
        out.println("  /paste     — вставка длинного текста одним сообщением (/send, /cancel)");
        out.println("  /demo      — режим измерения токенов: /demo tokens, /demo stats, /demo stop");
        out.println("  /mode      — профиль ответа: /mode показать, /mode fast|balanced|detailed");
        out.println("  /context   — режим контекста: /context показать, /context full|summary,");
        out.println("             /context compare <вопрос> — сравнение двух запросов (API, с подтверждением)");
        out.println("  /summary   — резюме сжатия: /summary показать, /summary refresh — обновить (API)");
        out.println("  /strategy  — стратегии контекста: /strategy показать,");
        out.println("             /strategy sliding-window|facts|branching — переключить (без API),");
        out.println("             /strategy compare <вопрос> — сравнение трёх стратегий (API, с подтверждением)");
        out.println("  /facts     — блок фактов: /facts показать, /facts refresh — обновить (API),");
        out.println("             /facts clear — очистить (подтверждение)");
        out.println("  /branch    — ветки диалога: /branch list, /branch checkpoint,");
        out.println("             /branch new <имя>, /branch switch <имя>, /branch delete <имя> (подтверждение)");
        out.println("  /exit      — завершение (также exit, quit)");
        out.println("Стрелки вверх/вниз — предыдущие сообщения, Tab — автодополнение команд.");
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

    @Override
    public boolean confirmReset() {
        try {
            String answer = reader.readLine(colors ? DIM + "Удалить историю текущей беседы? [y/N] " + RESET
                    : "Удалить историю текущей беседы? [y/N] ");
            String trimmed = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
            return trimmed.equals("y") || trimmed.equals("yes") || trimmed.equals("да");
        } catch (UserInterruptException | EndOfFileException e) {
            return false;
        }
    }

    /**
     * Подтверждение удаления истории (/clear). Правила: только y или yes
     * (без учёта регистра) подтверждают; пустой ввод, EOF и любой другой
     * ответ отменяют операцию.
     */
    @Override
    public boolean confirmHistoryClear(String subject) {
        try {
            PrintWriter out = terminal.writer();
            out.println(dim("Удалить всю историю " + subject + "?"));
            out.println(dim("Она будет очищена в памяти и в файле хранения."));
            out.println(dim("Отменить удаление после подтверждения нельзя."));
            out.flush();
            String answer = reader.readLine(dim("Продолжить? [y/N] "));
            String trimmed = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
            return trimmed.equals("y") || trimmed.equals("yes");
        } catch (UserInterruptException | EndOfFileException e) {
            return false;
        }
    }

    /** Подтверждение сравнения (/context compare); только y или yes. */
    @Override
    public boolean confirmCompare() {
        PrintWriter out = terminal.writer();
        out.println(dim("Будут выполнены два запроса на одной истории: "
                + "без сжатия и со сжатием."));
        out.println(dim("Если резюме ещё не подготовлено, понадобится дополнительный "
                + "запрос для его создания."));
        out.println(dim("Это расходует средства или квоту."));
        out.flush();
        try {
            String answer = reader.readLine("Продолжить? [y/N] ");
            String trimmed = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
            return trimmed.equals("y") || trimmed.equals("yes");
        } catch (UserInterruptException | EndOfFileException e) {
            return false;
        }
    }

    /** Подтверждение сравнения стратегий (/strategy compare); только y или yes. */
    @Override
    public boolean confirmStrategyCompare() {
        PrintWriter out = terminal.writer();
        out.println(dim("Будут выполнены запросы на одной истории для каждой стратегии "
                + "(скользящее окно, факты, ветки)."));
        out.println(dim("Если блок фактов пуст, понадобится дополнительный запрос "
                + "для его подготовки."));
        out.println(dim("Это расходует средства или квоту."));
        out.flush();
        try {
            String answer = reader.readLine("Продолжить? [y/N] ");
            String trimmed = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
            return trimmed.equals("y") || trimmed.equals("yes");
        } catch (UserInterruptException | EndOfFileException e) {
            return false;
        }
    }

    /** Подтверждение очистки фактов (/facts clear); только y или yes. */
    @Override
    public boolean confirmFactsClear() {
        PrintWriter out = terminal.writer();
        out.println(dim("Будет удалён весь блок фактов «ключ: значение» "
                + "(в памяти и в файле истории)."));
        out.println(dim("Уже потраченные токены не возвращаются; факты придётся "
                + "накапливать заново сообщениями диалога."));
        out.flush();
        try {
            String answer = reader.readLine("Продолжить? [y/N] ");
            String trimmed = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
            return trimmed.equals("y") || trimmed.equals("yes");
        } catch (UserInterruptException | EndOfFileException e) {
            return false;
        }
    }

    /** Подтверждение удаления ветки (/branch delete <имя>); только y или yes. */
    @Override
    public boolean confirmBranchDelete(String name) {
        PrintWriter out = terminal.writer();
        out.println(dim("Ветка «" + name + "» будет удалена (в памяти и в файле истории)."));
        out.println(dim("История её хвоста после checkpoint потеряна безвозвратно."));
        out.flush();
        try {
            String answer = reader.readLine("Продолжить? [y/N] ");
            String trimmed = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
            return trimmed.equals("y") || trimmed.equals("yes");
        } catch (UserInterruptException | EndOfFileException e) {
            return false;
        }
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

    private String dim(String text) {
        return colors ? DIM + text + RESET : text;
    }
}
