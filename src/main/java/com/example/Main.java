package com.example;

import java.util.List;

/**
 * Консольное приложение агента. Координирует работу: создаёт конфигурацию,
 * агента и терминальный интерфейс; вся работа с терминалом — в TerminalUi,
 * всё общение с LLM — в LlmAgent.
 *
 * Параметры запуска:
 *   --help   — справка (без API-ключа и обращения к API);
 *   --plain  — упрощённый режим без цветов, спиннера и сложного редактирования.
 */
public final class Main {

    public static void main(String[] args) {
        boolean plainRequested = false;
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printCliHelp(System.out);
                return;
            }
            if ("--plain".equals(arg)) {
                plainRequested = true;
            }
        }

        Config config;
        try {
            config = Config.fromEnv();
        } catch (AgentException e) {
            System.err.println("Ошибка конфигурации: " + e.getMessage());
            System.exit(1);
            return;
        }

        LlmAgent agent = new LlmAgent(config);
        TerminalUi ui = TerminalUi.create(plainRequested);
        try {
            runLoop(ui, agent, config.model());
        } finally {
            ui.close();
        }
    }

    /** Основной цикл чата: команды обрабатываются локально, сообщения уходят агенту. */
    static void runLoop(TerminalUi ui, LlmAgent agent, String model) {
        ui.showWelcome(model);
        while (true) {
            TerminalUi.Input input = ui.nextInput();
            switch (input.type()) {
                case EOF -> {
                    ui.showSystem("Работа завершена. История диалога не сохраняется.");
                    return;
                }
                case COMMAND -> {
                    if (handleCommand(ui, agent, model, input.text())) {
                        return;
                    }
                }
                case MESSAGE -> {
                    try (TerminalUi.ProgressIndicator progress = ui.startProgress()) {
                        String answer = agent.ask(input.text());
                        ui.showMessage(answer);
                    } catch (AgentException e) {
                        // Обычную ошибку запроса показываем; чат можно продолжить.
                        // При прерывании корректно завершаем работу.
                        ui.showError(e.getMessage());
                        if (Thread.currentThread().isInterrupted()) {
                            ui.showSystem("Работа завершена. История диалога не сохраняется.");
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Выполняет служебную команду; возвращает true, если приложение должно завершиться.
     * Служебные команды не вызывают API.
     */
    private static boolean handleCommand(TerminalUi ui, LlmAgent agent, String model, String command) {
        String normalized = command.toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "/exit", "exit", "quit" -> {
                ui.showSystem("Работа завершена. История диалога не сохраняется.");
                return true;
            }
            case "/help" -> ui.showHelp();
            case "/history" -> ui.showHistory(agent.getHistory());
            case "/reset" -> {
                if (agent.getHistory().isEmpty() || ui.confirmReset()) {
                    agent.resetConversation();
                    ui.showSystem("Начата новая беседа. История очищена.");
                } else {
                    ui.showSystem("Сброс отменён. История сохранена.");
                }
            }
            case "/clear" -> {
                // Очистка экрана не трогает историю диалога.
                ui.clearScreen();
                ui.showWelcome(model);
            }
            default -> ui.showSystem("Неизвестная команда. Введите /help для справки.");
        }
        return false;
    }

    /** Справка по запуску приложения; не требует API-ключа и не обращается к API. */
    private static void printCliHelp(java.io.PrintStream out) {
        out.println("AI Advent Agent — интерактивный CLI-чат с LLM.");
        out.println();
        out.println("Использование: ai-agent [параметры]");
        out.println("  --help    — эта справка");
        out.println("  --plain   — упрощённый режим: без цветов, спиннера и сложного редактирования");
        out.println();
        out.println("Переменные окружения: LLM_API_KEY, LLM_API_URL, LLM_MODEL");
        out.println("(при запуске через launcher загружаются из локального .env проекта).");
        out.println();
        out.println("Команды чата: /help, /history, /reset, /clear, /multiline, /exit (также exit, quit).");
    }

    private Main() {
    }
}
