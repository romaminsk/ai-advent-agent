package com.example;

/**
 * Консольное приложение агента. Координирует работу: создаёт конфигурацию,
 * хранилище контекста, агента и терминальный интерфейс; вся работа
 * с терминалом — в TerminalUi, всё общение с LLM — в LlmAgent, вся работа
 * с файлами истории — в ConversationStore.
 *
 * Параметры запуска:
 *   --help   — справка (без API-ключа, без создания и блокировки истории);
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

        int exitCode = 0;
        // Хранилище открывается один раз и удерживает блокировку истории
        // до конца работы. --help обработан выше: он не создаёт и не
        // блокирует историю и не требует API-ключа.
        try (JsonConversationStore store = JsonConversationStore.openDefault()) {
            LlmAgent agent = new LlmAgent(config, store);
            TerminalUi ui = TerminalUi.create(plainRequested);
            try {
                exitCode = runLoop(ui, agent, config.model());
            } finally {
                ui.close();
            }
        } catch (AgentException e) {
            // Ошибки хранилища (занятая другим экземпляром история, повреждённый
            // файл, недопустимый путь) останавливают запуск с понятным сообщением:
            // молча начинать новую беседу вместо существующей нельзя.
            System.err.println("Ошибка: " + e.getMessage());
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Основной цикл чата: команды обрабатываются локально, сообщения уходят агенту.
     *  Возвращает код завершения: 0 — обычное окончание, 1 — сбой сохранения контекста. */
    static int runLoop(TerminalUi ui, LlmAgent agent, String model) {
        ui.showWelcome(model);
        if (agent.hasRestoredContext()) {
            ui.showSystem("Контекст восстановлен: "
                    + agent.getHistory().size() / 2 + " завершённых обменов.");
        } else {
            ui.showSystem("Начата новая беседа.");
        }
        while (true) {
            TerminalUi.Input input = ui.nextInput();
            switch (input.type()) {
                case EOF -> {
                    ui.showSystem("Работа завершена. История беседы сохранена.");
                    return 0;
                }
                case COMMAND -> {
                    if (handleCommand(ui, agent, model, input.text())) {
                        return 0;
                    }
                }
                case MESSAGE -> {
                    try (TerminalUi.ProgressIndicator progress = ui.startProgress()) {
                        String answer = agent.ask(input.text());
                        ui.showMessage(answer);
                    } catch (ConversationSaveException e) {
                        // Ответ уже получен и показывается; повторный платный
                        // запрос не выполняется. Продолжать чат нельзя: контекст
                        // остался бы неполным, поэтому завершаем с ошибкой.
                        ui.showMessage(e.getAnswer());
                        ui.showError(e.getMessage());
                        return 1;
                    } catch (AgentException e) {
                        // Обычную ошибку запроса показываем; чат можно продолжить.
                        // При прерывании корректно завершаем работу.
                        ui.showError(e.getMessage());
                        if (Thread.currentThread().isInterrupted()) {
                            ui.showSystem("Работа завершена. История беседы сохранена.");
                            return 0;
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
                ui.showSystem("Работа завершена. История беседы сохранена.");
                return true;
            }
            case "/help" -> ui.showHelp();
            case "/history" -> ui.showHistory(agent.getHistory());
            case "/reset" -> {
                if (agent.getHistory().isEmpty() || ui.confirmReset()) {
                    try {
                        // Сначала пустая беседа записывается на диск, затем
                        // очищается память; при ошибке записи старое состояние
                        // остаётся неизменным в обоих местах.
                        agent.resetConversation();
                        ui.showSystem("Начата новая беседа. История очищена.");
                    } catch (ConversationStoreException e) {
                        ui.showError("Сброс не выполнен, история не изменена: " + e.getMessage());
                    }
                } else {
                    ui.showSystem("Сброс отменён. История сохранена.");
                }
            }
            case "/clear" -> {
                // Очистка экрана не трогает историю диалога (ни память, ни файл).
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
        out.println("Переменные окружения: LLM_API_KEY, LLM_API_URL, LLM_MODEL, LLM_HISTORY_FILE");
        out.println("(при запуске через launcher загружаются из локального .env проекта).");
        out.println();
        out.println("История беседы хранится в JSON (~/.ai-advent-agent/conversation.json");
        out.println("по умолчанию) и восстанавливается при следующем запуске;");
        out.println("переменная LLM_HISTORY_FILE задаёт другой абсолютный путь.");
        out.println();
        out.println("Команды чата: /help, /history, /reset, /clear, /multiline, /exit (также exit, quit).");
    }

    private Main() {
    }
}
