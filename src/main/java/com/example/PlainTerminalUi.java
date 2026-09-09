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

    /** Метка активного режима в приглашении (null — обычный режим). */
    private String activeModeLabel;

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
    public void setActiveModeLabel(String label) {
        this.activeModeLabel = label;
    }

    /** Приглашение обычного ввода с меткой активного режима, если она есть. */
    private String inputPrompt() {
        return activeModeLabel == null ? "> " : "[" + activeModeLabel + "] > ";
    }

    @Override
    public Input nextInput() {
        while (true) {
            String line = readLine(inputPrompt());
            if (line == null) {
                return Input.eof();
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("/multiline")) {
                Input composed = readMultiline("Многострочный режим: введите текст сообщения. "
                        + "/send — отправить, /cancel — отмена.");
                if (composed.type() == InputType.EOF) {
                    return composed;
                }
                if (composed.type() == InputType.MESSAGE) {
                    return composed;
                }
                continue; // /cancel — возвращаемся к обычному приглашению
            }
            if (trimmed.equalsIgnoreCase("/paste")) {
                Input composed = readMultiline("Вставка длинного текста: вставьте текст построчно. "
                        + "Весь текст уйдёт одним сообщением; отдельная строка /send — отправить, "
                        + "отдельная строка /cancel — отменить ввод без запроса к API.");
                if (composed.type() == InputType.EOF) {
                    return composed;
                }
                if (composed.type() == InputType.MESSAGE) {
                    return composed;
                }
                continue;
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
     * Многострочный режим (и /multiline, и /paste): строки собираются
     * до /send или /cancel. Все строки, кроме этих двух, — содержимое
     * сообщения; API не вызывается, пока не пришёл /send. Пустой накопленный
     * текст не отправляется: вставленный текст не разбивается на запросы.
     */
    private Input readMultiline(String intro) {
        err.println(intro);
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
        err.println("  /tokens    — оценка токенов истории, контекста и резервов (без вызова API)");
        err.println("  /stats     — фактический расход токенов и стоимость за сессию (без вызова API)");
        err.println("  /limit     — лимит расхода за сессию: /limit показать, /limit <число>, /limit off");
        err.println("  /reset     — очистить контекст и начать новую беседу");
        err.println("  /clear     — удалить историю текущего диалога (подтверждение y/yes; статистика сессии сохраняется)");
        err.println("  /multiline — многострочный ввод (/send — отправить, /cancel — отмена)");
        err.println("  /paste     — вставка длинного текста одним сообщением (/send, /cancel)");
        err.println("  /demo      — режим измерения токенов: /demo tokens, /demo stats, /demo stop");
        err.println("  /mode      — профиль ответа: /mode показать, /mode fast|balanced|detailed");
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

    /**
     * Подтверждение удаления истории (/clear). Только y или yes (без учёта
     * регистра) подтверждают; пустой ввод, EOF и любой другой ответ отменяют.
     */
    @Override
    public boolean confirmHistoryClear(String subject) {
        err.println("Удалить всю историю " + subject + "?");
        err.println("Она будет очищена в памяти и в файле хранения.");
        err.println("Отменить удаление после подтверждения нельзя.");
        String answer = readLine("Продолжить? [y/N] ");
        String trimmed = answer == null ? "" : answer.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.equals("y") || trimmed.equals("yes");
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
