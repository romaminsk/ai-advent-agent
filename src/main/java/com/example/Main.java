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
        // до конца работы. Настройки модели читаются из окружения в одном
        // месте (ModelSettings.fromEnv) и валидируются до первого запроса.
        // --help обработан выше: он не создаёт и не блокирует историю
        // и не требует API-ключа.
        try (JsonConversationStore store = JsonConversationStore.openDefault()) {
            LlmAgent agent = new LlmAgent(config, ModelSettings.fromEnv(), store);
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
        ui.showSystem(describeMode(agent.currentSettings()));
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
                        showAnswerNotes(ui, agent, model);
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

    /** Краткое описание профиля и лимита генерации (для приветствия и /mode). */
    static String describeMode(ModelSettings settings) {
        return "Профиль: " + modeSummary(settings);
    }

    private static String modeSummary(ModelSettings settings) {
        String summary = settings.profile() + " · лимит генерации: " + settings.maxOutputTokens();
        if (settings.limitOverridden()) {
            summary += " · лимит задан LLM_MAX_OUTPUT_TOKENS и не меняется профилем";
        }
        return summary;
    }

    /**
     * Краткие заметки после ответа: предупреждение об урезанном контексте,
     * о возможном обрезании по лимиту и диагностика (если включена).
     * Не содержит текстов переписки и секретов — только счётчики и метрики.
     */
    private static void showAnswerNotes(TerminalUi ui, LlmAgent agent, String model) {
        RequestDiagnostics diagnostics = agent.getLastDiagnostics();
        if (diagnostics == null) {
            return;
        }
        if (diagnostics.omittedPairs() > 0) {
            ui.showSystem("В запрос включены последние " + diagnostics.includedPairs()
                    + " обменов. Более ранние сообщения сейчас не учитываются.");
        }
        if (diagnostics.limitReached()) {
            // Генерация остановлена по лимиту; ответ уже показан. Повторный
            // запрос не выполняется — решение за пользователем.
            ui.showSystem("Ответ мог быть обрезан по лимиту генерации (finish_reason: length). "
                    + "Для более подробного ответа переключите профиль: /mode detailed, "
                    + "или задайте LLM_MAX_OUTPUT_TOKENS.");
        }
        if (agent.currentSettings().diagnostics()) {
            ui.showSystem(formatDiagnostics(diagnostics, model));
        }
    }

    /** Краткие метрики последнего запроса; отсутствующие значения — «нет данных». */
    static String formatDiagnostics(RequestDiagnostics d, String model) {
        StringBuilder text = new StringBuilder("Диагностика: модель ").append(model)
                .append(" · профиль ").append(d.profile())
                .append(" · лимит генерации ").append(d.maxOutputTokens());
        if (d.temperature() != null) {
            text.append(" · temperature ").append(d.temperature());
        }
        text.append(" · сообщений в запросе ").append(d.sentMessages())
                .append(" (паров истории ").append(d.includedPairs()).append(")")
                .append(" · тело запроса ").append(d.requestBytes()).append(" Б");
        text.append("\n  подготовка ").append(millis(d.prepareNanos()))
                .append(" мс · HTTP до полного ответа ").append(millis(d.httpNanos()))
                .append(" мс · разбор ").append(millis(d.parseNanos()))
                .append(" мс · сохранение истории ").append(millis(d.saveNanos()))
                .append(" мс · всего ").append(millis(d.totalNanos())).append(" мс");
        text.append("\n  finish_reason: ")
                .append(d.finishReason() == null ? "нет данных" : d.finishReason());
        text.append("\n  usage: ").append(formatUsage(d));
        return text.toString();
    }

    /** usage по стандартным полям OpenAI-совместимого ответа; без оценки по символам. */
    private static String formatUsage(RequestDiagnostics d) {
        if (d.promptTokens() == null && d.completionTokens() == null && d.totalTokens() == null) {
            return "нет данных";
        }
        return "prompt_tokens: " + orNoData(d.promptTokens())
                + " · completion_tokens: " + orNoData(d.completionTokens())
                + " · total_tokens: " + orNoData(d.totalTokens());
    }

    private static String orNoData(Integer value) {
        return value == null ? "нет данных" : value.toString();
    }

    private static String millis(long nanos) {
        return String.valueOf(nanos / 1_000_000);
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
            default -> {
                if (normalized.equals("/mode") || normalized.startsWith("/mode ")) {
                    handleModeCommand(ui, agent, normalized);
                } else {
                    ui.showSystem("Неизвестная команда. Введите /help для справки.");
                }
            }
        }
        return false;
    }

    /**
     * /mode — показать профиль и лимит; /mode fast|balanced|detailed — сменить
     * профиль в текущем запуске. Команда не вызывает API и не трогает историю.
     */
    private static void handleModeCommand(TerminalUi ui, LlmAgent agent, String normalized) {
        String prefix = "/mode";
        String argument = normalized.length() > prefix.length()
                ? normalized.substring(prefix.length()).trim()
                : "";
        if (argument.isEmpty()) {
            ui.showSystem(describeMode(agent.currentSettings()));
            return;
        }
        try {
            ModelSettings settings = agent.setProfile(argument);
            ui.showSystem("Профиль изменён: " + modeSummary(settings)
                    + ". Действует до конца текущего запуска.");
        } catch (AgentException e) {
            ui.showError(e.getMessage());
        }
    }

    /** Справка по запуску приложения; не требует API-ключа и не обращается к API. */
    private static void printCliHelp(java.io.PrintStream out) {
        out.println("AI Advent Agent — интерактивный CLI-чат с LLM.");
        out.println();
        out.println("Использование: ai-agent [параметры]");
        out.println("  --help    — эта справка");
        out.println("  --plain   — упрощённый режим: без цветов, спиннера и сложного редактирования");
        out.println();
        out.println("Переменные окружения: LLM_API_KEY, LLM_API_URL, LLM_MODEL,");
        out.println("LLM_RESPONSE_MODE (fast/balanced/detailed), LLM_MAX_OUTPUT_TOKENS,");
        out.println("LLM_TEMPERATURE, LLM_REQUEST_TIMEOUT_SECONDS, LLM_CONTEXT_MAX_TURNS,");
        out.println("LLM_DIAGNOSTICS, LLM_HISTORY_FILE");
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
