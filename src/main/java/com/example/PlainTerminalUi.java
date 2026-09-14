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

    /** Текущая задача в приглашении (null — не задана). */
    private String promptTask;

    /** Лимит длины задачи в приглашении; длиннее — обрезается с многоточием. */
    private static final int PROMPT_TASK_MAX_LENGTH = 24;

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

    /** Компактный старт: две строки, очевидное (новая беседа/контекст) не печатается. */
    @Override
    public void showWelcome(String model) {
        err.println("AI Advent Agent  ·  " + model);
        err.println("/help — команды");
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
        return labels.length() == 0 ? "> " : labels + " > ";
    }

    /** Задача в приглашении обрезается до лимита с многоточием. */
    private static String truncateForPrompt(String task) {
        if (task.length() <= PROMPT_TASK_MAX_LENGTH) {
            return task;
        }
        return task.substring(0, PROMPT_TASK_MAX_LENGTH) + "…";
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

    /** Ответ агента: маркер «◆», пустая строка до/после, тело без префиксов. */
    @Override
    public void showMessage(String answer) {
        // Ответ печатается в stdout в исходном виде (только обезвреживание
        // управляющих последовательностей); переносы, отступы и Markdown сохраняются.
        out.println();
        out.println("◆");
        out.println(AnsiSanitizer.sanitize(answer));
        out.println();
    }

    @Override
    public void showSystem(String text) {
        // Маркер в тексте сообщения (✓/!/?), без ANSI в plain-режиме.
        err.println(text == null ? "" : text);
    }

    @Override
    public void showError(String text) {
        // Первая строка — маркер «×», следующий шаг (если есть) — без префикса.
        String clean = AnsiSanitizer.sanitize(text == null ? "" : text);
        String[] lines = clean.split("\\n", 2);
        err.println("× " + lines[0]);
        if (lines.length > 1) {
            err.println(lines[1]);
        }
    }

    @Override
    public void showHelp() {
        err.print("Команды\n");
        err.print(TerminalUi.chatIndex(80));
        err.println();
    }

    @Override
    public void showCommandHelp(String name) {
        String text = TerminalUi.chatCommandHelp(name);
        if (text == null) {
            err.println("Нет подробной справки по «" + name + "». Индекс команд: /help");
            return;
        }
        err.println();
        err.println(text);
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

    /**
     * Единый формат подтверждения: одна строка «? <вопрос> <область/необратимость>
     * [y/N]». Только y или yes подтверждают; Enter, пустой ввод, EOF и любой
     * другой ответ выбирают «нет».
     */
    private boolean confirm(String question, String consequence) {
        String line = "? " + question.strip() + (consequence.isBlank()
                ? "" : " " + consequence.strip()) + " [y/N] ";
        String answer = readLine(line);
        String trimmed = answer == null ? "" : answer.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.equals("y") || trimmed.equals("yes");
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
