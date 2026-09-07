package com.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Упрощённый терминальный интерфейс: без цветов, спиннера и сложного
 * редактирования. Используется при --plain, TERM=dumb и при
 * перенаправленном вводе/выводе (в том числе в автоматических тестах).
 *
 * При перенаправленном stdout весь вывод, кроме ответов агента,
 * направляется в stderr, чтобы не загрязнять программный вывод.
 */
final class PlainTerminalUi implements TerminalUi {

    private static final String MULTILINE_PROMPT = "… › ";

    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;

    /** Обычный режим: stdin/stdout/stderr процесса. */
    PlainTerminalUi() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out, System.err);
    }

    /** Режим для тестов: ввод и вывод подставляются. */
    PlainTerminalUi(BufferedReader in, PrintStream out, PrintStream err) {
        this.in = in;
        this.out = out;
        this.err = err;
    }

    @Override
    public void showWelcome(String model) {
        err.println("AI Advent Agent · модель: " + model);
        err.println("Контекст текущей беседы включён · /help — команды · /exit — выход");
    }

    @Override
    public Input nextInput() {
        while (true) {
            String line = readLine("> ");
            if (line == null) {
                return Input.eof();
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("/multiline")) {
                Input composed = readMultiline();
                if (composed.type() == InputType.EOF) {
                    return composed;
                }
                if (composed.type() == InputType.MESSAGE) {
                    return composed;
                }
                continue; // /cancel — возвращаемся к обычному приглашению
            }
            if (trimmed.startsWith("/")) {
                return Input.command(trimmed);
            }
            if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
                return Input.command(trimmed.toLowerCase(java.util.Locale.ROOT));
            }
            return Input.message(trimmed);
        }
    }

    /**
     * Многострочный режим: строки собираются до /send или /cancel.
     * Все строки, кроме этих двух, — содержимое сообщения; API не вызывается,
     * пока не пришёл /send. Пустой накопленный текст не отправляется.
     */
    private Input readMultiline() {
        err.println("Многострочный режим: введите текст сообщения. /send — отправить, /cancel — отмена.");
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = readLine(MULTILINE_PROMPT);
            if (line == null) {
                return Input.eof();
            }
            String trimmed = line.trim();
            if (trimmed.equals("/send")) {
                if (lines.isEmpty() || String.join("\n", lines).isBlank()) {
                    err.println("Пустой текст не отправляется. Продолжайте набор или /cancel.");
                    continue;
                }
                return Input.message(String.join("\n", lines));
            }
            if (trimmed.equals("/cancel")) {
                err.println("Набор отменён.");
                return Input.command("/cancel");
            }
            lines.add(line);
        }
    }

    @Override
    public void showMessage(String answer) {
        // Ответ печатается в stdout в исходном виде (только обезвреживание управляющих
        // последовательностей); переносы, отступы и Markdown сохраняются.
        out.println("Агент:");
        out.println(AnsiSanitizer.sanitize(answer));
        out.println();
    }

    @Override
    public void showSystem(String text) {
        err.println(text);
    }

    @Override
    public void showError(String text) {
        err.println("Ошибка: " + text);
    }

    @Override
    public void showHelp() {
        err.println("Команды:");
        err.println("  /help      — справка");
        err.println("  /history   — история текущего диалога");
        err.println("  /reset     — очистить контекст и начать новую беседу");
        err.println("  /clear     — очистить экран, не удаляя историю диалога");
        err.println("  /multiline — многострочный ввод (/send — отправить, /cancel — отмена)");
        err.println("  /exit      — завершение (также exit, quit)");
    }

    @Override
    public void showHistory(List<ChatMessage> history) {
        if (history.isEmpty()) {
            err.println("История диалога пуста.");
            return;
        }
        err.println("Сохранённые сообщения (" + history.size() / 2 + " пар):");
        for (ChatMessage message : history) {
            err.println("[" + message.role() + "] " + AnsiSanitizer.sanitize(message.content()));
        }
    }

    @Override
    public boolean confirmReset() {
        String answer = readLine("Удалить историю текущей беседы? [y/N] ");
        return answer != null && (answer.trim().equalsIgnoreCase("y")
                || answer.trim().equalsIgnoreCase("yes")
                || answer.trim().equalsIgnoreCase("да"));
    }

    @Override
    public ProgressIndicator startProgress() {
        err.println("Ожидаем ответ…");
        return new ProgressIndicator() {
            @Override
            public void close() {
            }
        };
    }

    @Override
    public void clearScreen() {
        // Без поддержки очистки не печатаем управляющие последовательности.
    }

    @Override
    public void close() {
        // Нечего освобождать.
    }

    private String readLine(String prompt) {
        err.print(prompt);
        err.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
