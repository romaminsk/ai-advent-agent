package com.example;

import java.math.BigDecimal;
import java.util.List;

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
     *  Возвращает код завершения: 0 — обычное окончание, 1 — сбой сохранения контекста.
     *  Пока активен демо-режим измерений (/demo tokens), сообщения и просмотр
     *  статистики относятся к демонстрационной беседе, а не к основной. */
    static int runLoop(TerminalUi ui, LlmAgent agent, String model) {
        DemoRef demoRef = new DemoRef();
        try {
            ui.showWelcome(model);
            ui.showSystem(describeMode(agent.currentSettings()));
            ui.showSystem(describeSessionLimit(agent.currentSettings()));
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
                        String normalized = input.text().toLowerCase(java.util.Locale.ROOT);
                        if (normalized.equals("/demo") || normalized.startsWith("/demo ")) {
                            handleDemoCommand(ui, agent, model, normalized, demoRef);
                        } else {
                            // Пока демо активно, команды относятся к демо-беседе.
                            LlmAgent activeAgent = activeAgent(demoRef, agent);
                            if (demoRef.demo != null && normalized.equals("/reset")) {
                                demoRef.demo.clearLog(); // новая демонстрационная беседа
                            }
                            if (handleCommand(ui, activeAgent, model, input.text(), demoRef)) {
                                return 0;
                            }
                        }
                    }
                    case MESSAGE -> {
                        LlmAgent activeAgent = activeAgent(demoRef, agent);
                        // Предупреждение о прогнозируемом превышении контекстного
                        // бюджета (только политика warn; block блокирует внутри агента).
                        String budgetWarning = activeAgent.predictContextBudgetWarning(input.text());
                        if (budgetWarning != null) {
                            ui.showSystem(budgetWarning);
                        }
                        try (TerminalUi.ProgressIndicator progress = ui.startProgress()) {
                            String answer = activeAgent.ask(input.text());
                            ui.showMessage(answer);
                            if (demoRef.demo != null) {
                                demoRef.demo.logSuccess();
                                ui.showSystem(demoRef.demo.metricsAfterAnswer());
                                showSessionLimitNoticeIfAny(ui, activeAgent);
                            } else {
                                showAnswerNotes(ui, agent, model);
                            }
                        } catch (ConversationSaveException e) {
                            // Ответ уже получен и показывается; повторный платный
                            // запрос не выполняется. Продолжать чат нельзя: контекст
                            // остался бы неполным, поэтому завершаем с ошибкой.
                            ui.showMessage(e.getAnswer());
                            ui.showError(e.getMessage());
                            // Расход по usage этого запроса учтён — проверяем лимит
                            // даже при сбое записи, а не только после успешной пары.
                            if (demoRef.demo != null) {
                                demoRef.demo.logFailure(e);
                                ui.showSystem(demoRef.demo.errorDetails(e));
                            }
                            showSessionLimitNoticeIfAny(ui, activeAgent);
                            return 1;
                        } catch (AgentException e) {
                            // Обычную ошибку запроса показываем; чат можно продолжить.
                            // При прерывании корректно завершаем работу.
                            ui.showError(e.getMessage());
                            if (demoRef.demo != null) {
                                demoRef.demo.logFailure(e);
                                ui.showSystem(demoRef.demo.errorDetails(e));
                            }
                            // Пустой или обрезанный ответ с usage тоже учтён —
                            // информационное сообщение о лимите показываем и здесь.
                            showSessionLimitNoticeIfAny(ui, activeAgent);
                            if (Thread.currentThread().isInterrupted()) {
                                ui.showSystem("Работа завершена. История беседы сохранена.");
                                return 0;
                            }
                        }
                    }
                }
            }
        } finally {
            // Явный выход во время демо: временные файлы закрываются тихо,
            // основная история и её счётчики не изменялись.
            if (demoRef.demo != null) {
                demoRef.demo.close();
                ui.showSystem("Демонстрационная беседа закрыта; основная история не изменялась.");
            }
        }
    }

    /** Активный агент: демо-беседа, если режим включён, иначе основная. */
    private static LlmAgent activeAgent(DemoRef demoRef, LlmAgent mainAgent) {
        return demoRef.demo != null ? demoRef.demo.agent() : mainAgent;
    }

    /** Изменяемая ссылка на активный демо-режим (null — обычный режим). */
    private static final class DemoRef {
        TokenDemoSession demo;
    }

    /**
     * Команды ручного режима измерения токенов: /demo tokens — включить,
     * /demo stats — таблица попыток, /demo stop — завершить и вернуться
     * к основной беседе. Команды не вызывают API.
     */
    private static void handleDemoCommand(TerminalUi ui, LlmAgent agent, String model,
                                          String normalized, DemoRef demoRef) {
        String prefix = "/demo";
        String argument = normalized.length() > prefix.length()
                ? normalized.substring(prefix.length()).trim()
                : "";
        switch (argument) {
            case "tokens" -> {
                if (demoRef.demo != null) {
                    ui.showSystem("Режим измерения токенов уже включён. Таблица: /demo stats, "
                            + "завершение: /demo stop.");
                    return;
                }
                TokenDemoSession demo;
                try {
                    demo = TokenDemoSession.start(agent);
                } catch (ConversationStoreException | java.io.IOException e) {
                    ui.showError("Не удалось создать демонстрационную беседу: " + e.getMessage());
                    return;
                }
                demoRef.demo = demo;
                ui.setActiveModeLabel("демо");
                ui.showSystem("""
                                Режим измерения токенов включён.
                                Вводите сообщения как обычно — каждый запрос отправляется настоящей модели.

                                В этом режиме вся история демонстрационной беседы повторно отправляется \
                                с каждым сообщением. Автоматическое сокращение контекста отключено.

                                Большие запросы могут расходовать значительную квоту или средства. \
                                Токены отображаются по данным API. Таблица результатов: /demo stats.
                                Завершить и вернуться к основной беседе: /demo stop""");
            }
            case "stats" -> {
                if (demoRef.demo == null) {
                    ui.showSystem("Режим измерения токенов не включён. Введите /demo tokens.");
                    return;
                }
                ui.showSystem(demoRef.demo.table());
            }
            case "stop" -> {
                if (demoRef.demo == null) {
                    ui.showSystem("Режим измерения токенов не включён. Введите /demo tokens.");
                    return;
                }
                ui.showSystem(demoRef.demo.table());
                demoRef.demo.close();
                demoRef.demo = null;
                ui.setActiveModeLabel(null);
                ui.showSystem("Режим измерения токенов завершён. Возвращена основная беседа: "
                        + "её история и счётчики не изменялись.");
            }
            default -> ui.showSystem("Использование: /demo tokens — включить измерения, "
                    + "/demo stats — таблица попыток, /demo stop — завершить режим.");
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

    /** Краткое описание лимита расхода за сессию при запуске. */
    static String describeSessionLimit(ModelSettings settings) {
        Long limit = settings.sessionTokenLimit();
        if (limit == null) {
            return "Лимит расхода токенов за сессию: не задан (LLM_SESSION_TOKEN_LIMIT).";
        }
        return "Лимит расхода токенов за сессию: " + limit
                + " (LLM_SESSION_TOKEN_LIMIT) — информационное уведомление при превышении.";
    }

    /**
     * Краткие заметки после ответа: предупреждение об урезанном контексте,
     * о возможном обрезании по лимиту, информационное сообщение о превышении
     * лимита сессии (независимо от LLM_DIAGNOSTICS) и диагностика.
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
        // Информационный лимит сессии: показывается независимо от диагностики.
        showSessionLimitNoticeIfAny(ui, agent);
        if (agent.currentSettings().diagnostics()) {
            ui.showSystem(formatDiagnostics(diagnostics, model, agent.sessionStats(),
                    agent.currentSettings()));
        }
    }

    /** Показ отложенного уведомления о лимите сессии, если оно есть. */
    private static void showSessionLimitNoticeIfAny(TerminalUi ui, LlmAgent agent) {
        String notice = agent.consumeSessionLimitNotice();
        if (notice != null) {
            ui.showSystem(notice);
        }
    }

    /** Краткие метрики последнего запроса; отсутствующие значения — «нет данных». */
    static String formatDiagnostics(RequestDiagnostics d, String model,
                                    SessionTokenStats.Snapshot stats, ModelSettings settings) {
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
        appendTokenEstimates(text, d);
        appendSessionTokens(text, stats, settings);
        return text.toString();
    }

    /** Оценочные (≈) токен-поля запроса: отдельно от фактических значений usage. */
    private static void appendTokenEstimates(StringBuilder text, RequestDiagnostics d) {
        text.append("\n  оценка (≈ локальная, не точный подсчёт): сообщение ≈")
                .append(orNoData(d.estimatedUserMessageTokens()))
                .append(" · отправленный запрос ≈").append(orNoData(d.estimatedRequestTokens()))
                .append(" (накладные ≈").append(orNoData(d.estimatedRequestOverheadTokens()))
                .append(")")
                .append(" · видимый ответ ≈").append(orNoData(d.estimatedAnswerTokens()))
                .append(" · история после ≈").append(orNoData(d.estimatedHistoryTokensAfter()))
                .append(" (").append(d.savedMessagesAfter() == null
                        ? "нет данных" : d.savedMessagesAfter() + " сообщений")
                .append(")");
        if (d.contextBudgetExceeded()) {
            text.append("\n  внимание: локальная оценка превысила настроенный контекстный ")
                    .append("бюджет (LLM_CONTEXT_WINDOW_TOKENS); запрос отправлен — политика ")
                    .append("warn. Это оценка, поведение API может отличаться");
        }
    }

    /** Накопленные фактические расходы сессии и расчётная стоимость. */
    private static void appendSessionTokens(StringBuilder text, SessionTokenStats.Snapshot stats,
                                            ModelSettings settings) {
        text.append("\n  сессия: попыток API ").append(stats.apiAttempts())
                .append(" · с usage ").append(stats.requestsWithUsage())
                .append(" · вход ").append(usageTotals(stats.totalPromptTokens(), stats))
                .append(" · выход ").append(usageTotals(stats.totalCompletionTokens(), stats));
        if (stats.apiAttempts() > 0 && !stats.complete()) {
            text.append(" · итог неполный (есть запросы без usage или с частичным usage)");
        }
        text.append("\n  ").append(costSummaryLine(settings, stats));
    }

    private static String usageTotals(long value, SessionTokenStats.Snapshot stats) {
        return stats.requestsWithUsage() > 0 ? String.valueOf(value) : "нет данных";
    }

    /** usage по стандартным полям OpenAI-совместимого ответа; без оценки по символам. */
    private static String formatUsage(RequestDiagnostics d) {
        if (d.promptTokens() == null && d.completionTokens() == null && d.totalTokens() == null) {
            return "нет данных";
        }
        return "prompt_tokens: " + orNoData(d.promptTokens())
                + " · completion_tokens: " + orNoData(d.completionTokens())
                + " (может включать служебные категории, например reasoning — "
                + "это не токены видимого текста)"
                + " · total_tokens: " + orNoData(d.totalTokens());
    }

    private static String orNoData(Integer value) {
        return value == null ? "нет данных" : value.toString();
    }

    private static String millis(long nanos) {
        return String.valueOf(nanos / 1_000_000);
    }

    // ---------- Токены и статистика сессии (команды без вызова API) ----------

    /**
     * /tokens — локальная оценка (≈) без вызова API: сохранённая история,
     * контекст следующего запроса с текущим ограничением отправки (без ещё
     * не введённого сообщения), резерв выхода и контекстное окно.
     * Прогноз не выдаётся за измеренный запрос.
     */
    static String formatTokens(LlmAgent agent, String model) {
        ModelSettings settings = agent.currentSettings();
        List<ChatMessage> history = agent.getHistory();
        int pairLimit = agent.nextContextPairLimit();
        int includedPairs = Math.min(history.size() / 2, pairLimit);
        int omittedPairs = history.size() / 2 - includedPairs;

        StringBuilder text = new StringBuilder("Токены (без вызова API); источник подсчёта: ")
                .append(agent.tokenCounter().description());
        text.append("\n  модель ").append(model)
                .append(" · профиль ").append(settings.profile())
                .append(" · резерв выхода (max_tokens): ").append(settings.maxOutputTokens());
        text.append("\n  сохранённая история: ").append(history.size()).append(" сообщений (")
                .append(history.size() / 2).append(" пар) · ≈")
                .append(agent.estimateHistoryTokens())
                .append(" токенов — system-инструкция не входит (не хранится в истории), ")
                .append("метаданные JSON-файла не учитываются");
        text.append("\n  контекст следующего запроса (без нового сообщения): system + ")
                .append(includedPairs).append(" пар · ≈")
                .append(agent.estimateNextContextTokens()).append(" токенов");
        if (omittedPairs > 0) {
            text.append(" · без ").append(omittedPairs)
                    .append(" более ранних пар (ограничение LLM_CONTEXT_MAX_TURNS=")
                    .append(pairLimit).append(")");
        } else if (settings.contextMaxTurns() != null) {
            text.append(" · ограничение LLM_CONTEXT_MAX_TURNS=").append(pairLimit);
        } else {
            text.append(" · лимит пар не задан (по умолчанию ").append(pairLimit).append(")");
        }
        if (settings.contextWindowTokens() != null) {
            text.append("\n  контекстное окно: ").append(settings.contextWindowTokens())
                    .append(" токенов (источник: ручная настройка LLM_CONTEXT_WINDOW_TOKENS)")
                    .append(" · бюджет проверки: оценка входа + резерв выхода");
        } else {
            text.append("\n  контекстное окно: лимит контекста неизвестен ")
                    .append("(LLM_CONTEXT_WINDOW_TOKENS не задана) — процент заполнения ")
                    .append("не вычисляется, попадание не гарантируется");
        }
        text.append("\n  это прогноз без вызова API, а не измеренный запрос; ")
                .append("фактический расход показывает usage после ответа");
        return text.toString();
    }

    /**
     * /stats — статистика сессии без вызова API: попытки API, фактические
     * суммы usage, запросы с неизвестным расходом, расчётная стоимость.
     */
    static String formatStats(LlmAgent agent) {
        SessionTokenStats.Snapshot stats = agent.sessionStats();
        ModelSettings settings = agent.currentSettings();
        StringBuilder text = new StringBuilder(
                "Статистика сессии (счётчики сбрасываются при перезапуске; история при этом сохраняется)");
        text.append("\n  попыток обращения к API: ").append(stats.apiAttempts())
                .append(" · с usage: ").append(stats.requestsWithUsage())
                .append(" · с неизвестным расходом (без usage): ")
                .append(stats.requestsWithoutUsage());
        boolean anyUsage = stats.requestsWithUsage() > 0;
        text.append("\n  входные токены (фактические, сумма prompt_tokens): ")
                .append(usageTotals(stats.totalPromptTokens(), stats))
                .append("\n  выходные токены (фактические, сумма completion_tokens): ")
                .append(usageTotals(stats.totalCompletionTokens(), stats));
        if (stats.apiAttempts() == 0) {
            text.append("\n  итог: запросов ещё не было");
        } else if (stats.complete()) {
            text.append("\n  итог: полный (все запросы с полным usage)");
        } else {
            text.append("\n  итог: НЕПОЛНЫЙ (есть запросы без usage или с частичным usage; ")
                    .append("расход таких запросов не учтён)");
        }
        text.append("\n  ").append(limitStatsLine(agent));
        text.append("\n  ").append(costLine(settings, stats, anyUsage));
        return text.toString();
    }

    /**
     * Строка лимита сессии для /stats: включён/отключён, значение,
     * учтённый расход, полнота данных и статус.
     */
    private static String limitStatsLine(LlmAgent agent) {
        Long limit = agent.currentSettings().sessionTokenLimit();
        SessionTokenStats.Snapshot stats = agent.sessionStats();
        long known = stats.knownTotal();
        if (limit == null) {
            return "лимит сессии: отключён (LLM_SESSION_TOKEN_LIMIT не задана)"
                    + " · учтённый расход: " + known;
        }
        String line = "лимит сессии: " + limit + " · учтённый расход: " + known
                + " · статус: " + limitStatus(stats, limit);
        if (stats.complete()) {
            if (known < limit) {
                line += " · остаток до лимита: " + (limit - known);
            } else if (known > limit) {
                line += " · превышение: " + (known - limit);
            }
        } else if (known <= limit) {
            // Неполные данные: не утверждаем, что фактический расход в пределах.
            line += " · учтено не менее " + known
                    + " токенов; фактический расход может быть выше";
        }
        return line;
    }

    /** Статус лимита: не превышен / достигнут / превышен / неопределён из-за неполных данных. */
    private static String limitStatus(SessionTokenStats.Snapshot stats, long limit) {
        long known = stats.knownTotal();
        if (!stats.complete()) {
            if (known > limit) {
                return "превышен как минимум (данные неполные)";
            }
            return "нельзя достоверно определить из-за неполных данных";
        }
        if (known > limit) {
            return "превышен";
        }
        if (known == limit) {
            return "достигнут";
        }
        return "не превышен";
    }

    /**
     * /limit — текущий лимит сессии: значение, учтённый расход, статус
     * и напоминание, что это уведомление, а не жёсткая квота.
     */
    static String formatLimit(LlmAgent agent) {
        Long limit = agent.currentSettings().sessionTokenLimit();
        SessionTokenStats.Snapshot stats = agent.sessionStats();
        long known = stats.knownTotal();
        StringBuilder text = new StringBuilder();
        if (limit == null) {
            text.append("Лимит расхода токенов за сессию: отключён ")
                    .append("(LLM_SESSION_TOKEN_LIMIT не задана).");
        } else {
            text.append("Лимит расхода токенов за сессию: ").append(limit)
                    .append(" (LLM_SESSION_TOKEN_LIMIT).");
        }
        if (stats.complete()) {
            text.append("\n  учтённый расход: ").append(known)
                    .append(" (полные данные, запросов с usage: ")
                    .append(stats.requestsWithUsage()).append(")");
        } else {
            text.append("\n  учтённый расход (неполные данные): не менее ").append(known)
                    .append(" — для части запросов usage неполный или отсутствует; ")
                    .append("фактический расход может быть выше");
        }
        if (limit != null) {
            text.append("\n  статус: ").append(limitStatus(stats, limit));
            if (stats.complete()) {
                if (known < limit) {
                    text.append(" · остаток до лимита: ").append(limit - known);
                } else if (known > limit) {
                    text.append(" · превышение: ").append(known - limit).append(" токенов");
                }
            }
            text.append("\n  это уведомление о расходе, а не жёсткая квота; изменить: /limit <число>,")
                    .append(" отключить: /limit off");
        }
        return text.toString();
    }

    /**
     * /limit — показать статус; /limit <число> — установить информационный
     * лимит расхода за сессию до конца текущего запуска; /limit off —
     * отключить уведомления. Команды не вызывают API, не меняют историю,
     * не сбрасывают накопленный расход и не изменяют .env. При некорректном
     * вводе — подсказка, прежняя настройка сохраняется.
     */
    private static void handleLimitCommand(TerminalUi ui, LlmAgent agent, String normalized) {
        String prefix = "/limit";
        String argument = normalized.length() > prefix.length()
                ? normalized.substring(prefix.length()).trim()
                : "";
        if (argument.isEmpty()) {
            ui.showSystem(formatLimit(agent));
            return;
        }
        if ("off".equals(argument)) {
            agent.setSessionTokenLimit(null);
            ui.showSystem("Уведомление по лимиту сессии отключено. "
                    + "Накопленный расход сохранён: "
                    + agent.sessionStats().knownTotal() + ".");
            return;
        }
        long value;
        try {
            value = Long.parseLong(argument);
        } catch (NumberFormatException e) {
            // В том числе переполнение диапазона.
            ui.showError("Лимит должен быть положительным целым числом или off. "
                    + "Примеры: /limit 5000, /limit off. Прежняя настройка сохранена.");
            return;
        }
        if (value <= 0) {
            ui.showError("Лимит должен быть положительным целым числом или off. "
                    + "Примеры: /limit 5000, /limit off. Прежняя настройка сохранена.");
            return;
        }
        // Установка лимита не сбрасывает расход. Если учтённый расход уже
        // превышает новый порог и об этом ещё не сообщали — сообщение сразу.
        String immediateNotice = agent.setSessionTokenLimit(value);
        ui.showSystem("Лимит сессии установлен: " + value
                + ". Действует до конца текущего запуска; накопленный расход сохранён.");
        if (immediateNotice != null) {
            ui.showSystem(immediateNotice);
        }
    }

    /** Полная строка стоимости для /stats; без тарифа — «нет данных», не 0. */
    private static String costLine(ModelSettings settings, SessionTokenStats.Snapshot stats,
                                   boolean anyUsage) {
        BigDecimal inputPrice = settings.inputPricePer1M();
        BigDecimal outputPrice = settings.outputPricePer1M();
        if (inputPrice == null && outputPrice == null) {
            return "Стоимость: нет данных — тариф не настроен "
                    + "(необязательные LLM_INPUT_PRICE_PER_1M, LLM_OUTPUT_PRICE_PER_1M: "
                    + "USD за 1 000 000 входных/выходных токенов)";
        }
        if (inputPrice == null || outputPrice == null) {
            return "Стоимость: нет данных — задана только одна из переменных "
                    + "LLM_INPUT_PRICE_PER_1M, LLM_OUTPUT_PRICE_PER_1M; для расчёта нужны обе";
        }
        if (!anyUsage) {
            return "Стоимость: нет данных — не было запросов с usage (тариф задан)";
        }
        BigDecimal inputCost = TokenCost.perMillion(inputPrice, stats.totalPromptTokens());
        BigDecimal outputCost = TokenCost.perMillion(outputPrice, stats.totalCompletionTokens());
        String cost = TokenCost.formatUsd(TokenCost.total(inputCost, outputCost));
        String note = " (расчётная стоимость учтённых запросов по тарифу вход "
                + inputPrice.toPlainString() + " / выход " + outputPrice.toPlainString()
                + " USD за 1 000 000 токенов; это расчёт, а не фактическое списание "
                + "провайдера; кэширование, reasoning-тарифы и ценовые ступени, "
                + "если они есть, простой моделью не учитываются)";
        if (!stats.complete()) {
            note += " · итог неполный: стоимость рассчитана только по запросам с usage";
        }
        return "Стоимость: ≈" + cost + note;
    }

    /** Краткая строка стоимости для диагностики после ответа. */
    private static String costSummaryLine(ModelSettings settings, SessionTokenStats.Snapshot stats) {
        if (settings.inputPricePer1M() == null || settings.outputPricePer1M() == null) {
            return "стоимость: нет данных — тариф не настроен";
        }
        if (stats.requestsWithUsage() == 0) {
            return "стоимость: нет данных — нет запросов с usage (тариф задан)";
        }
        BigDecimal total = TokenCost.total(
                TokenCost.perMillion(settings.inputPricePer1M(), stats.totalPromptTokens()),
                TokenCost.perMillion(settings.outputPricePer1M(), stats.totalCompletionTokens()));
        return "стоимость: ≈" + TokenCost.formatUsd(total) + " (расчётная, не списание)";
    }

    /**
     * Выполняет служебную команду; возвращает true, если приложение должно завершиться.
     * Служебные команды не вызывают API.
     */
    private static boolean handleCommand(TerminalUi ui, LlmAgent agent, String model,
                                         String command, DemoRef demoRef) {
        String normalized = command.toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "/exit", "exit", "quit" -> {
                ui.showSystem("Работа завершена. История беседы сохранена.");
                return true;
            }
            case "/help" -> ui.showHelp();
            case "/history" -> ui.showHistory(agent.getHistory());
            case "/tokens" -> ui.showSystem(formatTokens(agent, model));
            case "/stats" -> ui.showSystem(formatStats(agent));
            case "/limit" -> handleLimitCommand(ui, agent, "/limit");
            case "/clear" -> handleClearCommand(ui, agent, demoRef);
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
            default -> {
                if (normalized.equals("/mode") || normalized.startsWith("/mode ")) {
                    handleModeCommand(ui, agent, normalized);
                } else if (normalized.equals("/limit") || normalized.startsWith("/limit ")) {
                    handleLimitCommand(ui, agent, normalized);
                } else {
                    ui.showSystem("Неизвестная команда. Введите /help для справки.");
                }
            }
        }
        return false;
    }

    /**
     * /clear — удаление всей истории текущего диалога после явного
     * подтверждения (только y/yes). Пустая беседа сначала атомарно
     * записывается на диск тем же механизмом, что и /reset (новая сессия
     * x-opencode-session), затем очищается память. Статистика сессии,
     * лимиты и тарифы не сбрасываются — удаление не отменяет потраченные
     * токены. В демо-режиме очищается только история демонстрационного
     * диалога. Команда не вызывает API; при ошибке записи история
     * в памяти сохраняется, CLI продолжает работать.
     */
    private static void handleClearCommand(TerminalUi ui, LlmAgent agent, DemoRef demoRef) {
        boolean demoMode = demoRef.demo != null;
        String subject = demoMode ? "демонстрационного диалога" : "текущего диалога";
        if (!ui.confirmHistoryClear(subject)) {
            ui.showSystem("Удаление отменено. История сохранена.");
            return;
        }
        try {
            agent.resetConversation();
            if (demoMode) {
                // Журнал попыток демо сохраняется: между попытками появляется
                // пометка «история очищена (/clear)».
                demoRef.demo.markHistoryCleared();
                ui.showSystem("История демонстрационного диалога удалена.\n"
                        + "Расход и таблица демонстрационного режима сохранены.");
            } else {
                ui.showSystem("История текущего диалога удалена. Можно начать новую беседу.\n"
                        + "Статистика расхода токенов за сессию сохранена.");
            }
        } catch (ConversationStoreException e) {
            ui.showError("Очистка не выполнена, история не изменена: " + e.getMessage());
        }
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
        out.println("LLM_CONTEXT_WINDOW_TOKENS, LLM_CONTEXT_OVERFLOW_POLICY (warn/block),");
        out.println("LLM_INPUT_PRICE_PER_1M, LLM_OUTPUT_PRICE_PER_1M (USD за 1M токенов),");
        out.println("LLM_SESSION_TOKEN_LIMIT (информационный лимит сессии),");
        out.println("LLM_DIAGNOSTICS, LLM_HISTORY_FILE");
        out.println("(при запуске через launcher загружаются из локального .env проекта).");
        out.println();
        out.println("История беседы хранится в JSON (~/.ai-advent-agent/conversation.json");
        out.println("по умолчанию) и восстанавливается при следующем запуске;");
        out.println("переменная LLM_HISTORY_FILE задаёт другой абсолютный путь.");
        out.println();
        out.println("Команды чата: /help, /history, /tokens, /stats, /limit, /reset, /clear,");
        out.println("/multiline, /exit (также exit, quit).");
    }

    private Main() {
    }
}
