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

    InteractiveTerminalUi() throws Exception {
        this.terminal = TerminalBuilder.builder().system(true).build();
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(
                        "/help", "/history", "/reset", "/clear", "/multiline", "/exit", "exit", "quit"))
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
                line = reader.readLine(promptUser);
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
                Input composed = readMultiline();
                if (composed.type() == InputType.MESSAGE || composed.type() == InputType.EOF) {
                    return composed;
                }
                continue; // /cancel — новое обычное приглашение
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

    /** Многострочный режим: подсказка видна постоянно, /send отправляет, /cancel отменяет. */
    private Input readMultiline() {
        showSystem("Многострочный режим: введите текст сообщения. /send — отправить, /cancel — отмена.");
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
        out.println("  /reset     — очистить контекст и начать новую беседу");
        out.println("  /clear     — очистить экран, не удаляя историю диалога");
        out.println("  /multiline — многострочный ввод (/send — отправить, /cancel — отмена)");
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
