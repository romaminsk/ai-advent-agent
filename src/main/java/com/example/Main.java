package com.example;

import java.math.BigDecimal;
import java.util.ArrayList;
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
            // Боевая точка создания агента: долговременная память и профиль
            // подключаются к реальным файлам (~/.ai-advent-agent/memory.json
            // и ~/.ai-advent-agent/profile.json, переменные LLM_MEMORY_FILE и
            // LLM_PROFILE_FILE) и переживают /clear, /reset и перезапуск.
            // Тестовые конструкторы LlmAgent (временные файлы) боевым
            // кодом не используются.
            LlmAgent agent = new LlmAgent(config, ModelSettings.fromEnv(), store,
                    MemoryStore.openDefault(), ProfileStore.openDefault());
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
     *  Пока активен режим измерения токенов (/demo tokens), сообщения и просмотр
     *  статистики относятся к временной беседе измерений, а не к основной. */
    static int runLoop(TerminalUi ui, LlmAgent agent, String model) {
        DemoRef demoRef = new DemoRef();
        try {
            ui.showWelcome(model);
            // Ход долгих операций (суммаризация) — в терминал во время запроса.
            agent.setProgressListener(ui::showSystem);
            // Краткий старт: приветствие и одна строка статуса. Подробности
            // (профиль, стратегия, режим контекста, лимит, слои памяти) —
            // при LLM_DIAGNOSTICS=true или по явным командам.
            if (agent.currentSettings().diagnostics()) {
                ui.showSystem(describeMode(agent.currentSettings()));
                ui.showSystem(describeStrategy(agent));
                ui.showSystem(describeContextMode(agent));
                ui.showSystem(describeSessionLimit(agent.currentSettings()));
                ui.showSystem(describeMemory(agent));
            }
            updatePromptLabels(ui, agent, demoRef);
            if (agent.hasRestoredContext()) {
                // Необычное состояние — одна компактная строка; очевидное
                // «начата новая беседа» не печатается.
                StringBuilder restored = new StringBuilder("Восстановлено: ")
                        .append(agent.getHistory().size() / 2).append(" ")
                        .append(plural(agent.getHistory().size() / 2, "обмен", "обмена", "обменов"));
                if (agent.currentSettings().contextStrategy() == ContextStrategy.BRANCHING) {
                    restored.append(" · ветка ").append(agent.activeBranchName());
                }
                if (agent.currentTask() != null) {
                    restored.append(" · задача «").append(agent.currentTask()).append("»");
                }
                ui.showSystem(restored + ".");
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
                        } else if (normalized.equals("/strategy")
                                || normalized.startsWith("/strategy ")
                                || normalized.equals("/facts")
                                || normalized.startsWith("/facts ")
                                || normalized.equals("/branch")
                                || normalized.startsWith("/branch ")) {
                            LlmAgent activeAgent = activeAgent(demoRef, agent);
                            handleContextDay10Command(ui, activeAgent, input.text(), normalized,
                                    demoRef);
                        } else if (normalized.equals("/context") || normalized.startsWith("/context ")
                                || normalized.equals("/summary")
                                || normalized.startsWith("/summary ")) {
                            // В режиме измерений команды относятся к её беседе.
                            LlmAgent activeAgent = activeAgent(demoRef, agent);
                            handleContextCommand(ui, activeAgent, input.text(), normalized, demoRef);
                        } else if (normalized.equals("/memory")
                                || normalized.equals("/task")
                                || normalized.startsWith("/task ")
                                || normalized.equals("/remember")
                                || normalized.startsWith("/remember ")
                                || normalized.equals("/forget")
                                || normalized.startsWith("/forget ")) {
                            LlmAgent activeAgent = activeAgent(demoRef, agent);
                            handleMemoryCommand(ui, activeAgent, input.text(), demoRef);
                        } else if (normalized.equals("/profile")
                                || normalized.startsWith("/profile ")
                                || normalized.equals("/skill")
                                || normalized.startsWith("/skill ")
                                || normalized.equals("/pipeline")
                                || normalized.startsWith("/pipeline ")) {
                            LlmAgent activeAgent = activeAgent(demoRef, agent);
                            handleProfileCommands(ui, activeAgent, input.text(), normalized,
                                    demoRef);
                        } else {
                            // В режиме измерений команды относятся к её беседе.
                            LlmAgent activeAgent = activeAgent(demoRef, agent);
                            if (demoRef.demo != null && normalized.equals("/reset")) {
                                demoRef.demo.clearLog(); // новая беседа измерений
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
                            ui.showSystem(warn(budgetWarning));
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
                            }                        } catch (ConversationSaveException e) {
                            // Ответ уже получен и показывается; повторный платный
                            // запрос не выполняется. Продолжать чат нельзя: контекст
                            // остался бы неполным, поэтому завершаем с ошибкой.
                            for (String note : activeAgent.consumeContextNotes()) {
                                ui.showSystem(note);
                            }
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
                            for (String note : activeAgent.consumeContextNotes()) {
                                ui.showSystem(note);
                            }
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
            // Явный выход во время измерений: временные файлы закрываются тихо,
            // основная история и её счётчики не изменялись.
            if (demoRef.demo != null) {
                demoRef.demo.close();
                ui.showSystem("Временная беседа измерений закрыта; основная история не изменялась.");
            }
        }
    }

    /** Активный агент: беседа измерений, если режим включён, иначе основная. */
    private static LlmAgent activeAgent(DemoRef demoRef, LlmAgent mainAgent) {
        return demoRef.demo != null ? demoRef.demo.agent() : mainAgent;
    }

    /**
     * Диспетчер команд стратегий: /strategy (показ, переключение, compare),
     * /facts (показ, refresh, clear), /branch (list, checkpoint, new, switch,
     * delete). Команды не попадают в историю.
     */
    private static void handleContextDay10Command(TerminalUi ui, LlmAgent agent, String raw,
                                                  String normalized, DemoRef demoRef) {
        if (normalized.equals("/strategy") || normalized.startsWith("/strategy ")) {
            handleStrategyCommand(ui, agent, raw, normalized, demoRef);
        } else if (normalized.equals("/facts") || normalized.startsWith("/facts ")) {
            handleFactsCommand(ui, agent, normalized, demoRef);
        } else {
            handleBranchCommand(ui, agent, normalized, demoRef);
        }
    }

    /**
     * /strategy: показать текущую стратегию и параметры; /strategy
     * sliding-window|facts|branching — переключить без вызова API;
     * /strategy compare <вопрос> — сравнение трёх стратегий (только после
     * подтверждения).
     */
    private static void handleStrategyCommand(TerminalUi ui, LlmAgent agent, String raw,
                                              String normalized, DemoRef demoRef) {
        String argument = normalized.length() > "/strategy".length()
                ? normalized.substring("/strategy".length()).trim()
                : "";
        if (argument.isEmpty()) {
            ui.showSystem(formatStrategy(agent));
            return;
        }
        if (argument.startsWith("compare ")) {
            String question = raw.substring(raw.toLowerCase(java.util.Locale.ROOT)
                    .indexOf("compare ") + "compare ".length()).trim();
            handleStrategyCompareCommand(ui, agent, question, demoRef);
            return;
        }
        switch (argument) {
            case "sliding-window", "facts", "branching" -> {
                try {
                    ModelSettings settings = agent.setStrategy(argument);
                    ui.showSystem("✓ Стратегия: " + settings.contextStrategy().title()
                            + ". Действует до конца текущего запуска; история, факты и ветки "
                            + "не изменены."
                            + (settings.contextStrategy() != ContextStrategy.BRANCHING
                            && settings.contextMode() == ContextMode.SUMMARY
                            ? "\n  режим контекства summary настроен, но в этой стратегии "
                            + "не применяется — это отмечается в /context и /tokens."
                            : ""));
                    updatePromptLabels(ui, agent, demoRef);
                } catch (AgentException e) {
                    ui.showError(e.getMessage());
                }
            }
            default -> {
                try {
                    agent.setStrategy(argument);
                } catch (AgentException e) {
                    ui.showError(e.getMessage());
                }
                ui.showSystem("Использование: /strategy [sliding-window|facts|branching], "
                        + "/strategy compare <вопрос>.");
            }
        }
    }

    /** Описание стратегии для приветствия и /strategy. */
    static String describeStrategy(LlmAgent agent) {
        return formatStrategy(agent);
    }

    /** Текст /strategy: стратегия, её параметры и подсказки по командам. */
    static String formatStrategy(LlmAgent agent) {
        ModelSettings s = agent.currentSettings();
        StringBuilder text = new StringBuilder("Стратегия контекста: ")
                .append(s.contextStrategy().title());
        switch (s.contextStrategy()) {
            case SLIDING_WINDOW -> text
                    .append("\n  окно: ").append(s.slidingWindowMessages())
                    .append(" сообщений (LLM_SLIDING_WINDOW_MESSAGES)")
                    .append("\n  архив диалога хранится полностью; старые сообщения ")
                    .append("не отправляются в запрос");
            case FACTS -> text
                    .append("\n  окно сообщений: ").append(s.factsWindowMessages())
                    .append(" (LLM_FACTS_WINDOW_MESSAGES)")
                    .append(" · пар фактов сейчас: ").append(agent.factsView().size())
                    .append("\n  лимит генерации фактов: ").append(s.factsMaxOutputTokens())
                    .append(" (LLM_FACTS_MAX_OUTPUT_TOKENS) · режим обновления: ")
                    .append(s.factsUpdateMode().title())
                    .append("\n  команды: /facts, /facts refresh, /facts clear");
            case BRANCHING -> text
                    .append("\n  активная ветка: «").append(agent.activeBranchName())
                    .append("»")
                    .append("\n  команды: /branch list, /branch checkpoint, ")
                    .append("/branch new <имя>, /branch switch <имя>, ")
                    .append("/branch delete <имя>");
        }
        if (s.contextStrategy() != ContextStrategy.BRANCHING
                && s.contextMode() == ContextMode.SUMMARY) {
            text.append("\n  режим контекства summary настроен, но в этой стратегии ")
                    .append("не применяется; см. /context и README");
        }
        text.append("\n  переключение стратегии не вызывает API: меняется способ ")
                .append("формирования следующего запроса");
        return text.toString();
    }

    /** /facts: показать блок фактов без вызова API; /facts refresh; /facts clear. */
    private static void handleFactsCommand(TerminalUi ui, LlmAgent agent, String normalized,
                                           DemoRef demoRef) {
        String argument = normalized.length() > "/facts".length()
                ? normalized.substring("/facts".length()).trim()
                : "";
        switch (argument) {
            case "" -> ui.showSystem(formatFacts(agent));
            case "refresh" -> {
                if (demoRef.demo != null) {
                    ui.showSystem("В режиме измерения токенов обновление фактов отключено. "
                            + "Завершите режим (/demo stop) и повторите.");
                    return;
                }
                ui.showSystem(agent.refreshFacts());
            }
            case "clear" -> {
                if (!ui.confirmFactsClear()) {
                    ui.showSystem("Удаление отменено.");
                    return;
                }
                try {
                    agent.factsClear();
                    ui.showSystem("✓ Блок фактов очищен (в памяти и в файле истории). "
                            + "Расход не возвращается.");
                } catch (ConversationStoreException e) {
                    ui.showError("Очистка фактов не выполнена, блок не изменён: "
                            + e.getMessage());
                }
            }
            default -> ui.showSystem("Использование: /facts — показать блок фактов, "
                    + "/facts refresh (API), /facts clear (с подтверждением).");
        }
    }

    /** Текст /facts: текущие факты, параметры и время последнего обновления. */
    static String formatFacts(LlmAgent agent) {
        ModelSettings s = agent.currentSettings();
        var facts = agent.factsView();
        StringBuilder text = new StringBuilder("Блок фактов (без вызова API): ");
        if (facts.isEmpty()) {
            text.append("пуст. Заполняется служебными вызовами после ответов (режим: ")
                    .append(s.factsUpdateMode().title()).append(")");
        } else {
            text.append(facts.size()).append(" пар · режим обновления: ")
                    .append(s.factsUpdateMode().title());
            for (var entry : facts.entrySet()) {
                text.append("\n  ").append(entry.getKey()).append(": ")
                        .append(entry.getValue());
            }
        }
        Long lastNanos = agent.lastFactsCallNanos();
        text.append("\n  последний служебный вызов: ")
                .append(lastNanos == null ? "не выполнялся"
                        : (lastNanos / 1_000_000) + " мс");
        return text.toString();
    }

    /**
     * Команды долговременной и рабочей памяти (без вызова API):
     * - /remember <текст> — сохранить в долговременную память (отдельный файл);
     * - /forget <ключ> — удалить запись долговременной памяти;
     * - /memory — показать долговременную память;
     * - /task <текст> — задать задачу рабочей памяти;
     * - /task clear — очистить задачу (факты рабочей памяти не трогает).
     * Краткосрочная память (история) этими командами не изменяется.
     */
    private static void handleMemoryCommand(TerminalUi ui, LlmAgent agent, String raw,
                                            DemoRef demoRef) {
        if (demoRef.demo != null) {
            ui.showSystem("В режиме измерения токенов команды памяти работают "
                    + "с основной беседой. Завершите режим (/demo stop) и повторите.");
            return;
        }
        if (raw.startsWith("/memory")) {
            ui.showSystem(formatMemory(agent));
            return;
        }
        if (raw.startsWith("/task")) {
            handleTaskCommand(ui, agent, raw, demoRef);
            return;
        }
        if (raw.equalsIgnoreCase("/remember") || "/remember".equals(raw.toLowerCase(java.util.Locale.ROOT).trim())) {
            ui.showSystem("Использование: /remember <текст>. Формат: «ключ: значение» "
                    + "или «ключ = значение» — часть до первого «:»/«=» (не более трёх "
                    + "слов) становится ключом. Без разделителя ключом станет первое "
                    + "слово или фраза до первой запятой, остальное — значение. "
                    + "Храните короткие однозначные факты: по одному факту на запись, "
                    + "не перечислением через запятую.");
            return;
        }
        if (raw.startsWith("/forget")) {
            String argument = raw.length() > "/forget".length()
                    ? raw.substring("/forget".length()).trim() : "";
            if (argument.isEmpty()) {
                ui.showSystem("Использование: /forget <ключ> — удалить запись "
                        + "долговременной памяти; поддерживается частичное совпадение "
                        + "(подстрока ключа), при нескольких совпадениях покажем список. "
                        + "Список ключей: /memory.");
                return;
            }
            try {
                LlmAgent.ForgetResult result = agent.forget(argument);
                if (result.matches() > 1) {
                    // Неоднозначность — короткий список вариантов, не ошибка.
                    ui.showSystem(result.message());
                } else if (result.removed()) {
                    ui.showSystem(result.message());
                } else {
                    // Не найдено или сбой записи — ошибка с одним шагом.
                    ui.showError(result.message());
                }
            } catch (AgentException e) {
                ui.showError(e.getMessage());
            }
            return;
        }
        // /remember <текст>
        String argument = raw.length() > "/remember".length()
                ? raw.substring("/remember".length()).trim() : "";
        try {
            agent.remember(argument);
            ui.showSystem("✓ Сохранено: " + LlmAgent.deriveMemoryKey(argument).key()
                    + ". Список записей: /memory.");
        } catch (AgentException e) {
            ui.showError(e.getMessage());
        }
    }

    // ================= Состояние задачи (Task State Machine) =================

    /**
     * Диспетчер /task: формализованное состояние задачи (без вызова API).
     * Субкоманды: start, stage, step, expect, pause, resume, block,
     * unblock, status, clear; прочий текст — короткая форма задания
     * описания (как раньше). Тексты описания и шагов сохраняют регистр.
     */
    private static void handleTaskCommand(TerminalUi ui, LlmAgent agent, String raw,
                                          DemoRef demoRef) {
        String argument = raw.length() > "/task".length()
                ? raw.substring("/task".length()).trim() : "";
        String head = argument.isEmpty() ? ""
                : argument.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        String rest = argument.length() > head.length()
                ? argument.substring(head.length()).trim() : "";
        try {
            switch (head) {
                case "" -> ui.showSystem(formatTaskShort(agent));
                case "status" -> ui.showSystem(formatTaskStatus(agent));
                case "start" -> {
                    if (rest.isEmpty()) {
                        ui.showSystem("Использование: /task start <описание>.");
                        return;
                    }
                    TaskState state = agent.taskStart(rest);
                    updatePromptLabels(ui, agent, demoRef);
                    ui.showSystem("✓ Задача задана: «" + state.description()
                            + "». Этап planning, статус active. Действует до /task clear.");
                }
                case "stage" -> {
                    if (rest.isEmpty()) {
                        ui.showSystem("Использование: /task stage <planning|execution|"
                                + "validation|done> [причина]. Переходы — только вперёд; "
                                + "возврат validation → execution с причиной.");
                        return;
                    }
                    String[] parts = rest.split("\\s+", 2);
                    String reason = parts.length > 1 ? parts[1] : null;
                    TaskState state = agent.taskStage(parts[0], reason);
                    if (state.stage() == TaskStage.DONE) {
                        ui.showSystem("✓ Задача переведена на этап done. Начать новую: "
                                + "/task start <описание>.");
                    } else {
                        ui.showSystem("✓ Задача переведена на этап "
                                + state.stage().lowerName() + ".");
                    }
                }
                case "step" -> {
                    if (rest.isEmpty()) {
                        ui.showSystem("Использование: /task step <текст>. Прежний шаг "
                                + "переносится в выполненные.");
                        return;
                    }
                    agent.taskStep(rest);
                    ui.showSystem("✓ Текущий шаг: «" + rest + "».");
                }
                case "expect" -> {
                    if (rest.isEmpty()) {
                        ui.showSystem("Использование: /task expect <текст>.");
                        return;
                    }
                    agent.taskExpect(rest);
                    ui.showSystem("✓ Ожидаемое действие: «" + rest + "».");
                }
                case "pause" -> {
                    agent.taskPause();
                    ui.showSystem("✓ Задача на паузе: этап, шаг и выполненные шаги "
                            + "сохранены. Продолжение: /task resume.");
                }
                case "resume" -> {
                    TaskState state = agent.taskResume();
                    ui.showSystem("✓ Задача возобновлена: этап " + state.stage().lowerName()
                            + (state.currentStep() != null
                            ? ", текущий шаг «" + state.currentStep() + "»."
                            : ", текущий шаг не задан (/task step <текст>)."));
                }
                case "block" -> {
                    agent.taskBlock();
                    ui.showSystem("✓ Задача помечена как blocked: ожидание внешних данных. "
                            + "Модель будет запрашивать недостающее. Снять: /task unblock.");
                }
                case "unblock" -> {
                    TaskState state = agent.taskUnblock();
                    ui.showSystem("✓ Блокировка снята: задача снова активна (этап "
                            + state.stage().lowerName() + ").");
                }
                case "clear" -> {
                    agent.clearTask();
                    updatePromptLabels(ui, agent, demoRef);
                    ui.showSystem("✓ Задача очищена. Факты сохранены (очистка фактов: /facts clear).");
                }
                default -> {
                    // Прочий текст — короткая форма задания описания задачи
                    // (как раньше); создаёт либо обновляет описание состояния.
                    agent.setTask(argument);
                    updatePromptLabels(ui, agent, demoRef);
                    ui.showSystem("✓ Задача задана: «" + argument + "». Действует до /task clear.");
                }
            }
        } catch (AgentException e) {
            ui.showError(e.getMessage());
        }
    }

    /** Текст /task без аргумента: короткое текущее состояние задачи. */
    static String formatTaskShort(LlmAgent agent) {
        TaskState state = agent.taskState();
        if (state == null) {
            return "Задача не задана. Начать: /task start <описание> (коротко: /task <текст>).";
        }
        return "Задача: " + state.description() + "\n  этап: " + state.stage().lowerName()
                + " · статус: " + state.status().lowerName()
                + "\n  полное состояние: /task status";
    }

    /** Текст /task status: полное состояние без вызова API. */
    static String formatTaskStatus(LlmAgent agent) {
        TaskState state = agent.taskState();
        if (state == null) {
            return formatTaskShort(agent);
        }
        StringBuilder text = new StringBuilder("Состояние задачи (без вызова API):");
        text.append("\n  описание: ").append(state.description() == null
                ? "не задано" : state.description());
        text.append("\n  этап: ").append(state.stage().lowerName())
                .append(" · статус: ").append(state.status().lowerName());
        text.append("\n  текущий шаг: ").append(state.currentStep() == null
                ? "не задан" : state.currentStep());
        text.append("\n  ожидаемое действие: ").append(state.expectedAction() == null
                ? "не задано" : state.expectedAction());
        if (state.completedSteps().isEmpty()) {
            text.append("\n  выполненные шаги: нет");
        } else {
            text.append("\n  выполненные шаги (").append(state.completedSteps().size())
                    .append("):");
            for (String step : state.completedSteps()) {
                text.append("\n    - ").append(step);
            }
        }
        switch (state.status()) {
            case PAUSED -> text.append("\n  пауза: выполнение заморожено, продолжение — /task resume");
            case BLOCKED -> text.append("\n  блокировка: ожидание внешних данных, снять — /task unblock");
            case ACTIVE -> { }
        }
        text.append("\n  обновлено: ").append(state.updatedAt());
        return text.toString();
    }

    // ================= Профиль пользователя, скиллы, пайплайны =================

    /**
     * Диспетчер команд профиля (без вызова API): /profile (обращение, стиль,
     * формат, ограничения, сброс), /skill (add, list, remove) и /pipeline
     * (задать, list, clear). В режиме измерений команды работают с основной
     * беседой — приём, как у команд памяти.
     */
    private static void handleProfileCommands(TerminalUi ui, LlmAgent agent, String raw,
                                              String normalized, DemoRef demoRef) {
        if (demoRef.demo != null) {
            ui.showSystem("В режиме измерения токенов команды профиля работают "
                    + "с основной беседой. Завершите режим (/demo stop) и повторите.");
            return;
        }
        if (raw.startsWith("/skill")) {
            handleSkillCommand(ui, agent, raw);
            return;
        }
        if (raw.startsWith("/pipeline")) {
            handlePipelineCommand(ui, agent, raw, normalized);
            return;
        }
        handleProfileCommand(ui, agent, raw, normalized);
    }

    /** /profile: показать профиль или задать поле; сброс — с подтверждением. */
    private static void handleProfileCommand(TerminalUi ui, LlmAgent agent, String raw,
                                             String normalized) {
        String argument = normalized.length() > "/profile".length()
                ? valueAfterPrefix(raw, "/profile")
                : "";
        if (argument.isEmpty()) {
            ui.showSystem(formatProfile(agent));
            return;
        }
        try {
            if (argument.startsWith("name ")) {
                String value = argument.substring("name ".length()).trim();
                agent.setProfileName(value);
                ui.showSystem("✓ Профиль обновлён: name → «" + value + "».");
                return;
            }
            if (argument.startsWith("style ")) {
                String value = argument.substring("style ".length()).trim();
                agent.setProfileStyle(value);
                ui.showSystem("✓ Профиль обновлён: style → «" + value + "».");
                return;
            }
            if (argument.startsWith("format ")) {
                String value = argument.substring("format ".length()).trim();
                agent.setProfileFormat(value);
                ui.showSystem("✓ Профиль обновлён: format → «" + value + "».");
                return;
            }
            if (argument.equals("constraint")) {
                ui.showSystem("Использование: /profile constraint <ограничение>, "
                        + "/profile constraint clear — удалить все ограничения.");
                return;
            }
            if (argument.equals("constraint clear")) {
                agent.clearProfileConstraints();
                ui.showSystem("✓ Профиль обновлён: constraint — все ограничения "
                        + "удалены.");
                return;
            }
            if (argument.startsWith("constraint ")) {
                String value = argument.substring("constraint ".length()).trim();
                agent.addProfileConstraint(value);
                ui.showSystem("✓ Профиль обновлён: constraint → «" + value + "».");
                return;
            }
            if (argument.equals("clear")) {
                if (!ui.confirmProfileClear("профиль пользователя")) {
                    ui.showSystem("Удаление отменено.");
                    return;
                }
                agent.clearProfile();
                ui.showSystem("✓ Профиль сброшен. Все поля заданы заново командами "
                        + "/profile. Скиллы и пайплайны тоже сброшены.");
                return;
            }
            ui.showSystem("Использование: /profile [name|style|format|constraint|clear]. "
                    + "Подробности: /help /profile.");
        } catch (AgentException e) {
            ui.showError(e.getMessage());
        }
    }

    /** /skill add <имя> <описание>; /skill list; /skill remove <имя>. */
    private static void handleSkillCommand(TerminalUi ui, LlmAgent agent, String raw) {
        String argument = valueAfterPrefix(raw, "/skill");
        try {
            if (argument.isEmpty() || argument.equals("list")) {
                ui.showSystem(formatSkills(agent));
                return;
            }
            if (argument.startsWith("add ")) {
                String rest = argument.substring("add ".length()).trim();
                if (rest.isEmpty()) {
                    ui.showSystem("Использование: /skill add <имя> <описание>.");
                    return;
                }
                String name;
                String description;
                if (rest.startsWith("\"") || rest.startsWith("«")) {
                    // Имя в кавычках: /skill add "карточка фичи" описание.
                    String closing = rest.startsWith("\"") ? "\"" : "»";
                    int end = rest.indexOf(closing, 1);
                    if (end < 0) {
                        ui.showSystem("Имя скилла без закрывающей кавычки. "
                                + "Использование: /skill add <имя> <описание>.");
                        return;
                    }
                    name = rest.substring(1, end).trim();
                    description = rest.substring(end + 1).trim();
                } else {
                    int nameEnd = rest.indexOf(' ');
                    if (nameEnd <= 0) {
                        ui.showSystem("Использование: /skill add <имя> <описание>.");
                        return;
                    }
                    name = rest.substring(0, nameEnd).trim();
                    description = rest.substring(nameEnd).trim();
                }
                agent.skillAdd(name, stripOptionalQuotes(description));
                ui.showSystem("✓ Скилл «" + name + "» сохранён. Пайплайн для скиллов: "
                        + "/pipeline <триггер> <скиллы>.");
                return;
            }
            if (argument.startsWith("remove ")) {
                String name = stripOptionalQuotes(
                        argument.substring("remove ".length()).trim());
                if (agent.skillRemove(name)) {
                    ui.showSystem("✓ Скилл «" + name + "» удалён. Он также вычищен "
                            + "из пайплайнов.");
                } else {
                    ui.showError("Скилл не найден: «" + name + "». Список: /skill list.");
                }
                return;
            }
            ui.showSystem("Использование: /skill [add <имя> <описание>|list|remove <имя>]. "
                    + "Подробности: /help /skill.");
        } catch (AgentException e) {
            ui.showError(e.getMessage());
        }
    }

    /**
     * /pipeline <триггер> <скилл1,скилл2,…>; /pipeline list; /pipeline clear.
     * Триггер может быть в кавычках и содержать пробелы; скиллы — через запятую.
     */
    private static void handlePipelineCommand(TerminalUi ui, LlmAgent agent, String raw,
                                              String normalized) {
        // Кавычки в значении не снимаем целиком: триггер и список скиллов
        // разбираются отдельно.
        String argument = raw.length() > "/pipeline".length()
                ? raw.substring("/pipeline".length()).trim()
                : "";
        try {
            if (argument.isEmpty() || argument.equals("list")) {
                ui.showSystem(formatPipelines(agent));
                return;
            }
            if (argument.equals("clear")) {
                if (!ui.confirmProfileClear("все пайплайны")) {
                    ui.showSystem("Удаление отменено.");
                    return;
                }
                agent.pipelinesClear();
                ui.showSystem("✓ Пайплайны очищены. Скиллы сохранены (/skill list).");
                return;
            }
            int triggerEnd = argument.lastIndexOf('"');
            if (argument.startsWith("\"")) {
                // Триггер в кавычках: /pipeline "напиши фичу" скилл1,скилл2
                int closing = argument.indexOf('"', 1);
                if (closing < 0) {
                    ui.showSystem("Триггер пайплайна без кавычки. Использование: "
                            + "/pipeline <триггер> <скилл1,скилл2,…>.");
                    return;
                }
                String trigger = argument.substring(1, closing).trim();
                String rest = argument.substring(closing + 1).trim();
                setPipelineFromArgument(ui, agent, trigger, rest);
            } else {
                int comma = argument.indexOf(',');
                if (comma < 0 || comma >= argument.length() - 1) {
                    ui.showSystem("Использование: /pipeline <триггер> <скилл1,скилл2,…> "
                            + "(триггер с пробелами — в кавычках). Подробности: /help /pipeline.");
                    return;
                }
                setPipelineFromArgument(ui, agent,
                        argument.substring(0, comma).trim(),
                        argument.substring(comma + 1).trim());
            }
        } catch (AgentException e) {
            ui.showError(e.getMessage());
        }
    }

    /** Разбор списка скиллов через запятую и установка пайплайна. */
    private static void setPipelineFromArgument(TerminalUi ui, LlmAgent agent,
                                                String trigger, String rest) {
        List<String> skillNames = new ArrayList<>();
        for (String part : rest.split(",", -1)) {
            String name = stripOptionalQuotes(part.trim());
            if (name.isEmpty()) {
                ui.showSystem("Использование: /pipeline <триггер> "
                        + "<скилл1,скилл2,…> (скиллы через запятую). Список: /skill list.");
                return;
            }
            skillNames.add(name);
        }
        agent.setPipeline(trigger, skillNames);
        ui.showSystem("✓ Пайплайн «" + trigger + "»: "
                + String.join(" → ", skillNames)
                + ". Подставляется, когда запрос содержит слова триггера.");
    }

    /** Текст /profile: все поля профиля; пустые помечены как «не задано». */
    static String formatProfile(LlmAgent agent) {
        UserProfile profile = agent.userProfile();
        StringBuilder text = new StringBuilder("Профиль пользователя (переживает /clear, "
                + "/reset и перезапуск; отдельный файл). Это не /mode — /mode меняет "
                + "лимит генерации, а профиль — работу с вами.");
        text.append("\n  обращение: ").append(profile.name() == null
                ? "не задано" : "«" + profile.name() + "»");
        text.append("\n  стиль: ").append(profile.style() == null
                ? "не задано" : profile.style());
        text.append("\n  формат: ").append(profile.format() == null
                ? "не задано" : profile.format());
        text.append("\n  ограничения: ");
        if (profile.constraints().isEmpty()) {
            text.append("не задано");
        } else {
            for (String constraint : profile.constraints()) {
                text.append("\n    - ").append(constraint);
            }
        }
        if (!profile.pipelines().isEmpty()) {
            text.append("\n  пайплайны: см. /pipeline list");
        }
        text.append("\n  задать: /profile name|style|format|constraint <текст>.");
        return text.toString();
    }

    /** Текст /skill list: скиллы профиля. */
    static String formatSkills(LlmAgent agent) {
        var skills = agent.skillsView();
        StringBuilder text = new StringBuilder("Скиллы профиля (назначение — типовые "
                + "задачи; без вызова API):");
        if (skills.isEmpty()) {
            text.append("\n  скиллов нет. Добавить: /skill add <имя> <описание>.");
        } else {
            for (var skill : skills.values()) {
                text.append("\n  «").append(skill.name()).append("»: ")
                        .append(skill.instructions())
                        .append("\n    (изменено: ").append(skill.updatedAt()).append(")");
            }
        }
        return text.toString();
    }

    /** Текст /pipeline list: триггеры и порядок скиллов. */
    static String formatPipelines(LlmAgent agent) {
        var pipelines = agent.userProfile().pipelinesView();
        StringBuilder text = new StringBuilder("Пайплайны профиля (триггер → порядок "
                + "скиллов; без вызова API):");
        if (pipelines.isEmpty()) {
            text.append("\n  пайплайнов нет. Задать: /pipeline <триггер> "
                    + "<скилл1,скилл2,…>.");
        } else {
            for (var entry : pipelines.entrySet()) {
                text.append("\n  «").append(entry.getKey()).append("»: ")
                        .append(String.join(" → ", entry.getValue()));
            }
        }
        return text.toString();
    }

    /** Часть команды после префикса, без учёта кавычек в значении. */
    private static String valueAfterPrefix(String raw, String prefix) {
        String value = raw.length() > prefix.length()
                ? raw.substring(prefix.length()).trim() : "";
        return stripOptionalQuotes(value);
    }

    /** Снимает одну пару кавычек «"…"», если значение в них целиком. */
    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2
                && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        if (value.length() >= 4
                && value.startsWith("«") && value.endsWith("»")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /** Текст /memory: записи долговременной памяти с метками времени. */
    static String formatMemory(LlmAgent agent) {
        var entries = agent.memoryView();
        StringBuilder text = new StringBuilder(
                "Долговременная память (профиль, решения и знания; переживает /clear, "
                        + "/reset и перезапуск):");
        if (entries.isEmpty()) {
            text.append("\n  записей нет. Сохраняйте явно: /remember <текст>.");
        } else {
            text.append("\n  записей: ").append(entries.size());
            for (var entry : entries.entrySet()) {
                // «ключ: значение» — пользователь видит, по какому ключу
                // удалять (/forget). Записи без явного ключа (старый формат)
                // показываются как есть, без дублирования текста.
                text.append("\n  ").append(entry.getValue().renderLine())
                        .append("\n    (изменено: ").append(entry.getValue().updatedAt())
                        .append(", добавлено: ").append(entry.getValue().createdAt()).append(")");
            }
        }
        return text.toString();
    }

    /**
     * Русская форма числа: «1 запись», «2 записи», «5 записей» и т.п.
     * формы: одна, несколько (2–4), много (0, 5–20).
     */
    static String plural(long count, String one, String few, String many) {
        long abs = Math.abs(count);
        long mod10 = abs % 10;
        long mod100 = abs % 100;
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return few;
        }
        return many;
    }


    private static void handleBranchCommand(TerminalUi ui, LlmAgent agent, String normalized,
                                            DemoRef demoRef) {
        String argument = normalized.length() > "/branch".length()
                ? normalized.substring("/branch".length()).trim()
                : "";
        switch (argument) {
            case "", "list" -> ui.showSystem(agent.branchesDescription());
            case "checkpoint" -> {
                try {
                    agent.branchCheckpoint();
                    ui.showSystem("✓ Checkpoint перенесён на конец текущей истории.\n"
                            + agent.branchesDescription());
                } catch (ConversationStoreException e) {
                    ui.showError("Checkpoint не сохранён, история не изменена: "
                            + e.getMessage());
                } catch (AgentException e) {
                    ui.showError(e.getMessage());
                }
            }
            default -> {
                String[] parts = argument.split(" ", 2);
                String sub = parts[0];
                String name = parts.length > 1 ? parts[1].trim() : "";
                switch (sub) {
                    case "new" -> {
                        if (name.isEmpty()) {
                            ui.showSystem("Использование: /branch new <имя>.");
                            return;
                        }
                        try {
                            agent.branchNew(name);
                            ui.showSystem("✓ Ветка «"
                                    + name.trim().toLowerCase(java.util.Locale.ROOT)
                                    + "» создана от checkpoint; диалог продолжается в ней. "
                                    + "История прежней активной ветки сохранена.");
                            updatePromptLabels(ui, agent, demoRef);
                        } catch (ConversationStoreException e) {
                            ui.showError("Ветка не создана: " + e.getMessage());
                        } catch (AgentException e) {
                            ui.showError(e.getMessage());
                        }
                    }
                    case "switch" -> {
                        if (name.isEmpty()) {
                            ui.showSystem("Использование: /branch switch <имя>.");
                            return;
                        }
                        try {
                            agent.branchSwitch(name);
                            ui.showSystem("✓ Ветка «"
                                    + name.trim().toLowerCase(java.util.Locale.ROOT)
                                    + "» активна. Истории всех веток сохранены; "
                                    + "следующее сообщение продолжит выбранную ветку.");
                            showPendingContextNotes(ui, agent);
                            updatePromptLabels(ui, agent, demoRef);
                        } catch (ConversationStoreException e) {
                            ui.showError("Переключение не выполнено, ветки не изменены: "
                                    + e.getMessage());
                        } catch (AgentException e) {
                            ui.showError(e.getMessage());
                        }
                    }
                    case "delete" -> {
                        if (name.isEmpty()) {
                            ui.showSystem("Использование: /branch delete <имя>.");
                            return;
                        }
                        if (!ui.confirmBranchDelete(name)) {
                            ui.showSystem("Удаление отменено.");
                            return;
                        }
                        try {
                            agent.branchDelete(name);
                            ui.showSystem("✓ Ветка «"
                                    + name.trim().toLowerCase(java.util.Locale.ROOT)
                                    + "» удалена; история её хвоста потеряна безвозвратно.");
                            showPendingContextNotes(ui, agent);
                        } catch (ConversationStoreException e) {
                            ui.showError("Удаление не выполнено, ветки не изменены: "
                                    + e.getMessage());
                        } catch (AgentException e) {
                            ui.showError(e.getMessage());
                        }
                    }
                    default -> ui.showSystem("Использование: /branch [list|checkpoint], "
                            + "/branch new <имя>, /branch switch <имя>, "
                            + "/branch delete <имя>.");
                }
            }
        }
    }

    /** Заметки о ветках после операций (однократное чтение). */
    private static void showPendingContextNotes(TerminalUi ui, LlmAgent agent) {
        for (String note : agent.consumeContextNotes()) {
            ui.showSystem(note);
        }
    }

    /**
     * Метки приглашения: активная ветка (в стратегии веток) и текущая
     * задача. В режиме измерений метка занята и не перезаписывается.
     */
    private static void updatePromptLabels(TerminalUi ui, LlmAgent agent, DemoRef demoRef) {
        if (demoRef.demo != null) {
            return;
        }
        if (agent.currentSettings().contextStrategy() == ContextStrategy.BRANCHING) {
            ui.setActiveModeLabel("ветка: " + agent.activeBranchName());
        } else {
            ui.setActiveModeLabel(null);
        }
        ui.setPromptTask(agent.currentTask());
    }

    /**
     * /strategy compare <вопрос>: подтверждение, один снимок истории,
     * три последовательных запроса (по одному на каждую стратегию).
     * Экспериментальные ответы в историю не попадают; расход всех вызовов
     * учитывается в статистике сессии.
     */
    private static void handleStrategyCompareCommand(TerminalUi ui, LlmAgent agent,
                                                     String question, DemoRef demoRef) {
        if (question.isBlank()) {
            ui.showSystem("Использование: /strategy compare <вопрос>.");
            return;
        }
        if (agent.getHistory().isEmpty()) {
            ui.showSystem("История пока пуста: сравнивать не на чем. Введите несколько "
                    + "сообщений с проверяемыми фактами и повторите.");
            return;
        }
        if (demoRef.demo != null) {
            ui.showSystem("В режиме измерения токенов сравнение стратегий отключено. "
                    + "Завершите режим (/demo stop) и повторите.");
            return;
        }
        if (!ui.confirmStrategyCompare()) {
            ui.showSystem("Сравнение отменено. API не вызывался, история сохранена.");
            return;
        }
        ui.showSystem("Снимок истории зафиксирован. Выполняются три запроса "
                + "последовательно…");
        try (TerminalUi.ProgressIndicator progress = ui.startProgress()) {
            LlmAgent.StrategyCompareResult result = agent.strategyCompare(question);
            showStrategyCompareResult(ui, agent, result);
        }
        ui.showSystem("Сравнение стратегий завершено. Экспериментальные ответы "
                + "в историю не добавлялись; расход учтён в статистике сессии.");
    }

    /** Полный вывод сравнения стратегий: ответы, фактический расход и таблица. */
    private static void showStrategyCompareResult(TerminalUi ui, LlmAgent agent,
                                                  LlmAgent.StrategyCompareResult r) {
        ModelSettings settings = agent.currentSettings();
        for (LlmAgent.StrategyRow row : r.rows()) {
            ui.showMessage(row.label() + "\n" + (row.error() != null
                    ? "(недостоверно: " + row.error() + ")" : row.content()));
            if (row.error() == null) {
                ui.showSystem("  расход: вход " + orNoData(row.promptTokens())
                        + " · выход " + orNoData(row.completionTokens())
                        + " · время " + ms(row.callNanos()) + " мс · завершение: "
                        + orNoDataText(row.finishReason()));
            }
        }
        ui.showSystem("Блок фактов на момент сравнения: "
                + (r.factsPrepPerformed() && r.factsPrepError() != null
                ? "подготовка не удалась (" + r.factsPrepError() + ")"
                : r.factsPairs() + " пар")
                + (r.reusedFacts()
                ? " · использован сохранённый блок (подготовка не требовалась)"
                : r.factsPrepPerformed() && r.factsPrepError() == null
                ? " · подготовлен служебным запросом из снимка сравнения"
                : ""));
        long slidingSpend = spend(rowOf(r, ContextStrategy.SLIDING_WINDOW).promptTokens(),
                rowOf(r, ContextStrategy.SLIDING_WINDOW).completionTokens());
        long factsSpend = spend(rowOf(r, ContextStrategy.FACTS).promptTokens(),
                rowOf(r, ContextStrategy.FACTS).completionTokens());
        long branchingSpend = spend(rowOf(r, ContextStrategy.BRANCHING).promptTokens(),
                rowOf(r, ContextStrategy.BRANCHING).completionTokens());
        long prepSpend = spend(r.factsPrepPromptTokens(), r.factsPrepCompletionTokens());
        long totalKnown = slidingSpend + branchingSpend
                + factsSpend + prepSpend;

        StringBuilder table = new StringBuilder(
                "Таблица сравнения стратегий (фактический расход по данным API):");
        table.append("\n  показатель | sliding-window | facts | branching");
        table.append("\n  входные токены | ")
                .append(orNoData(rowOf(r, ContextStrategy.SLIDING_WINDOW).promptTokens()))
                .append(" | ").append(orNoData(rowOf(r, ContextStrategy.FACTS).promptTokens()))
                .append(" | ").append(orNoData(rowOf(r, ContextStrategy.BRANCHING).promptTokens()));
        table.append("\n  выходные токены | ")
                .append(orNoData(rowOf(r, ContextStrategy.SLIDING_WINDOW).completionTokens()))
                .append(" | ").append(orNoData(rowOf(r, ContextStrategy.FACTS).completionTokens()))
                .append(" | ").append(orNoData(rowOf(r, ContextStrategy.BRANCHING).completionTokens()));
        table.append("\n  расход запроса | ").append(formatSpend(rowSpend(r, ContextStrategy.SLIDING_WINDOW)))
                .append(" | ").append(formatSpend(rowSpend(r, ContextStrategy.FACTS)))
                .append(" | ").append(formatSpend(rowSpend(r, ContextStrategy.BRANCHING)));
        table.append("\n  время ответа | ")
                .append(ms(rowOf(r, ContextStrategy.SLIDING_WINDOW).callNanos()))
                .append(" мс | ").append(ms(rowOf(r, ContextStrategy.FACTS).callNanos()))
                .append(" мс | ").append(ms(rowOf(r, ContextStrategy.BRANCHING).callNanos()))
                .append(" мс");
        if (settings.inputPricePer1M() != null && settings.outputPricePer1M() != null) {
            table.append("\n  стоимость (расчётная, не списание): суммарно по входу ")
                    .append("≈")
                    .append(TokenCost.formatUsd(TokenCost.perMillion(
                            settings.inputPricePer1M(), totalKnown)));
            table.append("\n  стоимость каждого варианта считайте по его входу/выходу ")
                    .append("выше; кэширование и reasoning-тарифы расчётом не учитываются");
        } else {
            table.append("\n  стоимость: нет данных — тариф не настроен");
        }
        table.append("\n  расход запроса = вход + выход (total повторно не прибавляется); ")
                .append("частичный usage показан как «не менее»");
        if (r.factsPrepPerformed() && r.factsPrepError() == null) {
            table.append("\n  расход подготовки фактов: ").append(formatSpend(prepSpend))
                    .append(" (вход ").append(orNoData(r.factsPrepPromptTokens()))
                    .append(" · выход ").append(orNoData(r.factsPrepCompletionTokens()))
                    .append(") — учтён отдельно и входит в лимит сессии");
        } else if (r.factsPrepPerformed()) {
            table.append("\n  подготовка фактов не удалась; расход попытки учтён, ")
                    .append("для части данных он может быть неизвестен (см. /stats)");
        }
        table.append("\n  варианты выполнены на одном снимке истории и не попадают ")
                .append("в эту беседу");
        table.append("\n  генерации могут различаться даже при одинаковых настройках; ")
                .append("сравните сохранность фактов и выполнение задачи сами: ")
                .append("автоматической оценки качества нет");
        ui.showSystem(table.toString());
    }

    /** Строка стратегии в результате сравнения. */
    private static LlmAgent.StrategyRow rowOf(LlmAgent.StrategyCompareResult r,
                                              ContextStrategy strategy) {
        for (LlmAgent.StrategyRow row : r.rows()) {
            if (row.label().equals(strategy.title())) {
                return row;
            }
        }
        return new LlmAgent.StrategyRow(strategy.title(), null, "строка отсутствует",
                null, null, null, 0);
    }

    /** Расход строки стратегии; нет usage — «нет данных», а не ноль. */
    private static long rowSpend(LlmAgent.StrategyCompareResult r, ContextStrategy strategy) {
        LlmAgent.StrategyRow row = rowOf(r, strategy);
        if (row.promptTokens() == null && row.completionTokens() == null) {
            return -1;
        }
        return spend(row.promptTokens(), row.completionTokens());
    }

    /** формат расхода; нет данных — честно «нет данных». */
    private static String formatSpend(long known) {
        if (known < 0) {
            return "нет данных";
        }
        return String.valueOf(known);
    }

    /** Изменяемая ссылка на активный режим измерений (null — обычный режим). */
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
                    ui.showError("Не удалось создать временную беседу измерений: " + e.getMessage());
                    return;
                }
                demoRef.demo = demo;
                demo.agent().setProgressListener(ui::showSystem);
                ui.setActiveModeLabel("измерение");
                ui.showSystem("""
                                Режим измерения токенов включён.
                                Вводите сообщения как обычно — каждый запрос отправляется настоящей модели.

                                В этом режиме вся история временной беседы измерений повторно отправляется \
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

    /** Описание режима контекста и параметров сжатия. */
    static String describeContextMode(LlmAgent agent) {
        ModelSettings s = agent.currentSettings();
        StringBuilder text = new StringBuilder("Режим контекста: ").append(s.contextMode().title())
                .append(" · последние ").append(s.keepLastMessages())
                .append(" сообщений сохраняются дословно")
                .append(" · порог обновления резюме: ").append(s.summaryBatchMessages())
                .append(" сообщений")
                .append(" · лимит генерации резюме: ").append(s.summaryMaxOutputTokens());
        if (agent.contextMaxTurnsConfigured()) {
            text.append("\n  LLM_CONTEXT_MAX_TURNS задана, но в режимах контекста full/summary ")
                    .append("не применяется — отмечается и в /tokens");
        }
        return text.toString();
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

    /** Краткое описание слоёв памяти при запуске: где хранится каждый слой. */
    static String describeMemory(LlmAgent agent) {
        return "Память: краткосрочная — история беседы · рабочая — задача и факты · "
                + "долговременная — " + agent.longTermMemoryCount() + " "
                + plural(agent.longTermMemoryCount(), "запись", "записи", "записей")
                + " в отдельном файле " + agent.memoryFile()
                + " (не стирается /clear). Команды: /remember, /forget, /memory, /task.";
    }

    /**
     * Служебные заметки после ответа.
     *
     * По умолчанию (LLM_DIAGNOSTICS=false) вывод минимальный: только ответ
     * агента; из служебного — лишь сообщения, влияющие на решение
     * пользователя: предупреждение об обрезании по лимиту генерации
     * и информационное сообщение о превышении лимита сессии. Вся
     * статистика и диагностика доступны по явным командам (/stats,
     * /tokens, /context) или включаются LLM_DIAGNOSTICS=true.
     *
     * В подробном режиме — полный блок: контекст запроса, заметки о
     * стратегии и сжатии, диагностика (времена этапов, usage, стоимость).
     * Не содержит текстов переписки и секретов — только счётчики и метрики.
     */
    private static void showAnswerNotes(TerminalUi ui, LlmAgent agent, String model) {
        boolean verbose = agent.currentSettings().diagnostics();
        // Подробные служебные заметки однократные: потребление обязательно,
        // чтобы заметки одной операции не «неожиданно» появлялись позже.
        List<String> contextNotes = agent.consumeContextNotes();
        RequestDiagnostics diagnostics = agent.getLastDiagnostics();

        if (!verbose) {
            String limitNotice = agent.consumeSessionLimitNotice();
            if (limitNotice != null) {
                ui.showSystem(warn(limitNotice));
            }
            return;
        }

        // Подробный режим: контекст, сводка, заметки, диагностика.
        String caption = agent.lastRequestContextCaption();
        if (caption != null) {
            ui.showSystem("Контекст: " + caption);
        }
        ui.showSystem(formatShortAnswerNote(agent));
        for (String note : contextNotes) {
            ui.showSystem(note);
        }
        String limitNotice = agent.consumeSessionLimitNotice();
        if (limitNotice != null) {
            ui.showSystem(warn(limitNotice));
        }
        if (diagnostics == null) {
            return;
        }
        if (diagnostics.limitReached()) {
            // Генерация остановлена по лимиту; ответ уже показан. Повторный
            // запрос не выполняется — решение за пользователем.
            ui.showSystem(warn("Ответ мог быть обрезан по лимиту генерации. Для более подробного "
                    + "ответа задайте LLM_MAX_OUTPUT_TOKENS или выберите /mode detailed."));
        }
        ui.showSystem(formatContextNotes(agent));
        ui.showSystem(formatDiagnostics(diagnostics, model, agent.sessionStats(),
                agent.currentSettings()));
    }

    /**
     * Краткая и понятная сводка после ответа (без подробной диагностики):
     * стратегия, входные/выходные токены по данным API, накопленный расход
     * сессии и стоимость при известном тарифе.
     */
    static String formatShortAnswerNote(LlmAgent agent) {
        SessionTokenStats.Snapshot stats = agent.sessionStats();
        ModelSettings settings = agent.currentSettings();
        RequestDiagnostics d = agent.getLastDiagnostics();
        StringBuilder text = new StringBuilder();
        // Слои памяти, подставленные в запрос (кратко; подробно — при
        // LLM_DIAGNOSTICS=true в разрезе(formatDiagnostics)).
        text.append("Память: долговременная ").append(agent.longTermMemoryCount())
                .append(" ").append(plural(agent.longTermMemoryCount(),
                        "запись", "записи", "записей"))
                .append(" · рабочая ").append(agent.factsView().size())
                .append(" ").append(plural(agent.factsView().size(),
                        "пара", "пары", "пар"))
                .append(" · история ").append(agent.getHistory().size())
                .append(" ").append(plural(agent.getHistory().size(),
                        "сообщение", "сообщения", "сообщений"));
        if (agent.currentTask() != null) {
            text.append(" · задача задана");
        }
        if (settings.inputPricePer1M() != null && settings.outputPricePer1M() != null
                && stats.requestsWithUsage() > 0) {
            text.append("\n  Стоимость: ≈")
                    .append(TokenCost.formatUsd(TokenCost.total(
                            TokenCost.perMillion(settings.inputPricePer1M(),
                                    stats.totalPromptTokens()),
                            TokenCost.perMillion(settings.outputPricePer1M(),
                                    stats.totalCompletionTokens()))))
                    .append(" (расчётная)");
        }
        StringBuilder result = new StringBuilder("Расход сессии: ")
                .append(stats.knownTotal())
                .append(stats.complete() ? "" : " (неполные данные)");
        if (text.length() > 0) {
            result.append(" · ").append(text);
        }
        if (d != null && (d.promptTokens() != null || d.completionTokens() != null)) {
            result.append("\\n  последний запрос: вход ")
                    .append(orNoData(d.promptTokens()))
                    .append(" · выход ").append(orNoData(d.completionTokens()))
                    .append(" токенов");
        }
        return result.toString();
    }

    /**
     * Компактный блок после обычного ответа: режим контекста,
     * покрытие резюме, дословные сообщения, фактический расход обычного
     * запроса, отдельный расход суммаризации за сессию и общий расход.
     * Показывается независимо от LLM_DIAGNOSTICS.
     */
    static String formatContextNotes(LlmAgent agent) {
        ModelSettings settings = agent.currentSettings();
        SessionTokenStats.Snapshot stats = agent.sessionStats();
        RequestDiagnostics d = agent.getLastDiagnostics();
        ConversationSummary summary = agent.summary();
        StringBuilder text = new StringBuilder("Режим контекста: ")
                .append(settings.contextMode().title());
        // Покрытие и дословные счётчики уже в подписи контекста по
        // фактически отправленному запросу — здесь не дублируются.
        if (settings.contextMode() == ContextMode.SUMMARY) {
            text.append(summary == null
                    ? " · резюме: нет (или устарело)"
                    : " · резюме действует");
        }
        if (d != null) {
            text.append("\n  вход обычного запроса: ").append(orNoData(d.promptTokens()))
                    .append(" · выход: ").append(orNoData(d.completionTokens()));
            // Оценка выгоды сжатия по фактическому usage и локальной
            // оценке полного входа; метка ≈ — это оценка, не замер обоих
            // состояний.
            Integer estimatedFull = agent.lastEstimatedFullPromptTokens();
            if (estimatedFull != null && d.promptTokens() != null) {
                long delta = (long) estimatedFull - d.promptTokens();
                text.append("\n  вход без сжатия ≈").append(estimatedFull)
                        .append(" · разница: ")
                        .append(delta >= 0 ? "экономия " + delta : "увеличение " + (-delta))
                        .append(" токенов входа (оценка ≈; по одному запросу)");
            } else if (estimatedFull != null) {
                text.append("\n  вход без сжатия ≈").append(estimatedFull)
                        .append(" · разница: недостаточно данных (usage запроса отсутствует)");
            }
        }
        if (stats.summaryAttempts() > 0) {
            text.append("\n  расход суммаризации (за сессию): вход ")
                    .append(usageTotals(stats.summaryPromptTokens(), stats))
                    .append(" · выход ").append(usageTotals(stats.summaryCompletionTokens(), stats))
                    .append(" · попыток ").append(stats.summaryAttempts());
            if (agent.lastSummaryCallNanos() != null) {
                text.append(" · последний вызов ").append(agent.lastSummaryCallNanos() / 1_000_000)
                        .append(" мс");
            }
        }
        text.append("\n  накопленный общий расход сессии (обычные ответы, суммаризация")
                .append(" и сравнение): ").append(stats.knownTotal());
        if (stats.apiAttempts() > 0 && !stats.complete()) {
            text.append(" (неполные данные)");
        }
        text.append("\n  ").append(costSummaryLine(settings, stats));
        return text.toString();
    }

    /** Показ отложенного уведомления о лимите сессии, если оно есть. */
    private static void showSessionLimitNoticeIfAny(TerminalUi ui, LlmAgent agent) {
        String notice = agent.consumeSessionLimitNotice();
        if (notice != null) {
            ui.showSystem(warn(notice));
        }
    }

    /** Предупреждение: единый маркер «!» перед текстом (без дублирования). */
    static String warn(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.startsWith("!") ? trimmed : "! " + trimmed;
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
     * /tokens — локальная оценка (≈) без вызова API: сохранённый архив,
     * отправляемый контекст следующего запроса в режиме контекста (без ещё
     * не введённого сообщения), резерв выхода и контекстное окно.
     * Прогноз не выдаётся за измеренный запрос.
     */
    static String formatTokens(LlmAgent agent, String model) {
        ModelSettings settings = agent.currentSettings();
        List<ChatMessage> history = agent.getHistory();
        ConversationSummary summary = agent.summary();
        int covered = summary != null ? summary.coveredMessages() : 0;
        int sent = history.size() - covered;

        StringBuilder text = new StringBuilder("Токены (без вызова API); источник подсчёта: ")
                .append(agent.tokenCounter().description());
        text.append("\n  модель ").append(model)
                .append(" · профиль ").append(settings.profile())
                .append(" · режим контекста ").append(settings.contextMode().title())
                .append(" · резерв выхода (max_tokens): ").append(settings.maxOutputTokens());
        text.append("\n  сохранённый архив: ").append(history.size()).append(" сообщений (")
                .append(history.size() / 2).append(" пар) · ≈")
                .append(agent.estimateHistoryTokens())
                .append(" токенов — system-инструкция не входит (не хранится в истории), ")
                .append("метаданные JSON-файла не учитываются");
        text.append("\n  отправляемый контекст следующего запроса (без нового сообщения): ")
                .append("system + ").append(sent).append(" сообщений дословно");
        if (settings.contextMode() == ContextMode.SUMMARY) {
            if (summary == null) {
                text.append(" · резюме ещё не создано (или устарело)");
            } else {
                text.append(" + справочное резюме: покрыто ").append(covered)
                        .append(" сообщений, текст ≈")
                        .append(agent.tokenCounter().count(summary.text())).append(" токенов");
            }
        }
        text.append(" · ≈").append(agent.estimateNextContextTokens()).append(" токенов");
        if (agent.contextMaxTurnsConfigured()) {
            text.append("\n  LLM_CONTEXT_MAX_TURNS задана, но в режимах контекста full/summary ")
                    .append("не применяется: full отправляет весь архив, summary — ")
                    .append("резюме и весь несжатый хвост");
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
        // Разбивка по назначениям API-вызовов (без двойного учёта):
        // сумма групп равна общим суммам выше.
        text.append("\n  из них обычные ответы: попыток ").append(stats.regularAttempts())
                .append(" · вход ").append(usageTotals(stats.regularPromptTokens(), stats))
                .append(" · выход ").append(usageTotals(stats.regularCompletionTokens(), stats));
        text.append("\n  обновление памяти (факты и задача): попыток ")
                .append(stats.factsAttempts())
                .append(" · вход ").append(usageTotals(stats.factsPromptTokens(), stats))
                .append(" · выход ").append(usageTotals(stats.factsCompletionTokens(), stats));
        text.append("\n  суммаризация: попыток ").append(stats.summaryAttempts())
                .append(" · вход ").append(usageTotals(stats.summaryPromptTokens(), stats))
                .append(" · выход ").append(usageTotals(stats.summaryCompletionTokens(), stats));
        text.append("\n  сравнение /context compare: попыток ").append(stats.compareAttempts())
                .append(" · вход ").append(usageTotals(stats.comparePromptTokens(), stats))
                .append(" · выход ").append(usageTotals(stats.compareCompletionTokens(), stats));
        if (stats.apiAttempts() == 0) {
            text.append("\n  итог: запросов ещё не было");
        } else if (stats.complete()) {
            text.append("\n  итог: полный (все запросы с полным usage)");
        } else {
            text.append("\n  итог: НЕПОЛНЫЙ (есть запросы без usage или с частичным usage; ")
                    .append("расход таких запросов не учтён)");
        }
        text.append("\n  ").append(limitStatsLine(agent));
        text.append("\n  ").append(compressionBalanceLine(stats));
        text.append("\n  ").append(costLine(settings, stats, anyUsage));
        return text.toString();
    }

    /**
     * Баланс сжатия для /stats — накопленная экономия/перерасход
     * входа обычных запросов (оценка ≈), затраты на суммаризацию и итоговый
     * баланс. Отсутствие данных помечается как «недостаточно данных», а не ноль.
     */
    static String compressionBalanceLine(SessionTokenStats.Snapshot stats) {
        StringBuilder line = new StringBuilder("баланс сжатия (оценка ≈):");
        if (!stats.contextSavingsAvailable()) {
            line.append(" экономия входа: недостаточно данных")
                    .append(" (обычные запросы со сжатием ещё не выполнялись)");
        } else {
            long savings = stats.contextSavingsPromptTokens();
            line.append(" экономия входа: ")
                    .append(stats.contextSavingsKnown()
                            ? String.valueOf(savings) + " prompt_tokens"
                            : "недостаточно данных (у части запросов usage отсутствует)")
                    .append(" (оценка ≈, запросов: ").append(stats.contextSavingsRequests())
                    .append(stats.contextSavingsEstimatedRequests() > 0
                            ? ", из них локальных оценок: " + stats.contextSavingsEstimatedRequests()
                            : "")
                    .append(")")
                    .append(" · накопленный знак: ")
                    .append(savings >= 0 ? "экономия" : "перерасход");
        }
        // Расход суммаризации: те же счётчики назначения SUMMARY.
        long summaryPrompt = stats.summaryPromptTokens();
        long summaryCompletion = stats.summaryCompletionTokens();
        line.append(" · затраты на суммаризацию: ")
                .append(stats.summaryAttempts() == 0
                        ? "недостаточно данных (суммаризация не выполнялась)"
                        : "вход " + summaryPrompt + " · выход " + summaryCompletion
                        + (statisticalCompressionIncomplete(stats)
                        ? " (часть запросов суммаризации без полного usage)"
                        : " (полные данные)"));
        if (stats.contextSavingsRequests() > 0 && stats.contextSavingsKnown()
                && stats.summaryAttempts() > 0 && stats.complete()) {
            long balance = stats.contextSavingsPromptTokens()
                    - (summaryPrompt + summaryCompletion);
            line.append(" · итоговый баланс (≈, экономия минус затраты): ")
                    .append(balance >= 0 ? "+" : "").append(balance);
        } else {
            line.append(" · итоговый баланс: недостаточно данных")
                    .append(stats.summaryAttempts() > 0 && !stats.complete()
                            ? " (у части запросов usage отсутствует)"
                            : "");
        }
        return line.toString();
    }

    private static boolean statisticalCompressionIncomplete(SessionTokenStats.Snapshot stats) {
        // Частичный или отсутствующий usage в сессии может относиться к любым
        // запросам; для суммаризации честно помечаем неопределённость, если итог
        // неполный и суммаризация выполнялась.
        return stats.summaryAttempts() > 0 && !stats.complete();
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
            ui.showSystem("✓ Уведомление по лимиту сессии отключено. "
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
        ui.showSystem("✓ Лимит сессии установлен: " + value
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

    // ================= /context и /summary =================

    /**
     * Команды /context (показ, full, summary, compare) и /summary
     * (показ, refresh). Переключение режима API не вызывает; сравнение —
     * единственная команда, выполняющая API-запросы, и только после явного
     * подтверждения. Команды в историю не попадают. Вопрос сравнения
     * передаётся с сохранением регистра (без приведения к нижнему регистру).
     */
    private static void handleContextCommand(TerminalUi ui, LlmAgent agent, String raw,
                                             String normalized, DemoRef demoRef) {
        String contextArg = normalized.startsWith("/context ")
                ? normalized.substring("/context ".length()).trim()
                : normalized.equals("/context") ? "" : null;
        if (contextArg != null) {
            switch (contextArg) {
                case "" -> ui.showSystem(describeContextMode(agent));
                case "full" -> switchContextMode(ui, agent, "full");
                case "summary" -> switchContextMode(ui, agent, "summary");
                default -> {
                    if (contextArg.startsWith("compare ")) {
                        String question = raw.substring(raw.toLowerCase(java.util.Locale.ROOT)
                                .indexOf("compare ") + "compare ".length()).trim();
                        handleCompareCommand(ui, agent, question, demoRef);
                    } else {
                        ui.showSystem("Использование: /context [full|summary], "
                                + "/context compare <вопрос>.");
                    }
                }
            }
            return;
        }
        if (normalized.equals("/summary")) {
            ui.showSystem(formatSummary(agent));
            return;
        }
        if (normalized.equals("/summary refresh")) {
            if (demoRef.demo != null) {
                ui.showSystem("В режиме измерения токенов сжатие отключено. "
                        + "Выключите его (/demo stop) и повторите.");
                return;
            }
            ui.showSystem(agent.refreshSummary());
        }
    }

    /**
     * /context full|summary: смена режима без вызова API, без изменений
     * истории и резюме. При первом включении summary — предупреждение
     * о расходе служебных запросов; резюме создаётся при следующем обычном
     * запросе или по /summary refresh.
     */
    private static void switchContextMode(TerminalUi ui, LlmAgent agent, String mode) {
        ContextMode before = agent.currentSettings().contextMode();
        try {
            ModelSettings settings = agent.setContextMode(mode);
            ui.showSystem("✓ Режим контекста: " + settings.contextMode().title()
                    + ". Действует до конца текущего запуска; история и резюме не изменены. "
                    + "Summary создаётся при следующем обычном запросе, когда накопится "
                    + "порог, либо по /summary refresh.");
            if (settings.contextMode() == ContextMode.SUMMARY
                    && before != ContextMode.SUMMARY) {
                ui.showSystem("Обновление summary выполняет дополнительные запросы "
                        + "к текущей модели и расходует токены.");
            }
        } catch (AgentException e) {
            ui.showError(e.getMessage());
        }
    }

    /** /summary без вызова API: резюме, граница покрытия и дословный хвост. */
    static String formatSummary(LlmAgent agent) {
        ModelSettings s = agent.currentSettings();
        StringBuilder text = new StringBuilder("Резюме покрытой истории (сжатие · без вызова API):");
        ContextMode mode = s.contextMode();
        ConversationSummary summary = agent.summary();
        text.append("\n  режим контекста: ").append(mode.title());
        if (summary == null) {
            text.append("\n  резюме: нет")
                    .append("\n  покрыто сообщений: 0")
                    .append("\n  дословно сохранено: ").append(agent.getHistory().size())
                    .append(" сообщений из архива");
        } else {
            text.append("\n  покрыто сообщений: ").append(summary.coveredMessages())
                    .append(" · дословно сохранено: ")
                    .append(agent.getHistory().size() - summary.coveredMessages())
                    .append(" сообщений")
                    .append("\n  отпечаток покрытого префикса: ")
                    .append(summary.coveredFingerprint());
            text.append("\n  текст резюме:\n").append(AnsiSanitizer.sanitize(summary.text()));
        }
        int uncovered = agent.uncoveredOldMessagesCount();
        text.append("\n  не покрытых резюме старых сообщений вне последних ")
                .append(s.keepLastMessages()).append(": ").append(uncovered)
                .append(uncovered > 0 ? " (обновление: /summary refresh или накопление порога "
                + s.summaryBatchMessages() + ")" : "");
        return text.toString();
    }

    /**
     * /context compare <вопрос>: два последовательных запроса на одном
     * снимке истории (без сжатия и со сжатием) после явного подтверждения.
     * Экспериментальные ответы в историю не попадают; расход учитывается
     * с отдельными назначениями.
     */
    private static void handleCompareCommand(TerminalUi ui, LlmAgent agent, String question,
                                             DemoRef demoRef) {
        if (question.isBlank()) {
            ui.showSystem("Использование: /context compare <вопрос>.");
            return;
        }
        if (agent.getHistory().isEmpty()) {
            ui.showSystem("История пока пуста: сравнивать не на чем. Введите несколько "
                    + "сообщений с проверяемыми фактами и повторите.");
            return;
        }
        if (!agent.canCompare()) {
            // Нет ни корректного резюме с положительной границей, ни старых
            // сообщений вне последних KEEP — два варианта были бы одинаковыми.
            ui.showSystem("История пока слишком короткая: нет резюме покрытой части "
                    + "и старых сообщений вне последних "
                    + agent.currentSettings().keepLastMessages() + " для сжатия, "
                    + "оба варианта запросов были бы одинаковыми.");
            return;
        }
        if (demoRef.demo != null) {
            ui.showSystem("В режиме измерения токенов сжатие отключено. "
                    + "Выключите его (/demo stop) и повторите сравнение.");
            return;
        }
        if (!ui.confirmCompare()) {
            ui.showSystem("Сравнение отменено. API не вызывался, история сохранена.");
            return;
        }
        // Явное сравнение выполняется независимо от оценки выгоды,
        // но честно предупреждает, если локальная оценка (≈) указывает,
        // что сжатие не уменьшит вход.
        if (!agent.compressionBenefitLikely()) {
            ui.showSystem(warn("Предупреждение (локальная оценка ≈): сжатие сейчас невыгодно — "
                    + "заменяемые сообщения короче ожидаемого резюме. Сравнение всё равно "
                    + "выполняется по явному запросу."));
        }
        ui.showSystem("Снимок истории зафиксирован. Выполняются два запроса "
                + "последовательно…");
        try (TerminalUi.ProgressIndicator progress = ui.startProgress()) {
            LlmAgent.CompareResult result = agent.compare(question);
            showCompareResult(ui, agent, result);
        }
        ui.showSystem("Сравнение завершено. Экспериментальные ответы в историю не "
                + "добавлялись; расход учтён в статистике сессии.");
    }

    /** Полный вывод сравнения: оба ответа, usage и таблица расхода. */
    private static void showCompareResult(TerminalUi ui, LlmAgent agent,
                                          LlmAgent.CompareResult r) {
        ModelSettings settings = agent.currentSettings();
        boolean prices = settings.inputPricePer1M() != null
                && settings.outputPricePer1M() != null;

        ui.showMessage("БЕЗ СЖАТИЯ\n" + (r.fullError() != null
                ? "(ошибка: " + r.fullError() + ")" : r.fullAnswer()));
        if (r.fullError() == null) {
            ui.showSystem("  usage: вход " + orNoData(r.fullPromptTokens())
                    + " · выход " + orNoData(r.fullCompletionTokens())
                    + " · время " + ms(r.fullNanos()) + " мс· finish_reason: "
                    + orNoDataText(r.fullFinishReason()));
        }

        ui.showMessage("СО СЖАТИЕМ\n" + (r.summaryError() != null
                ? "(статус: " + r.summaryError() + ")" : r.summaryAnswer()));
        if (r.summaryError() == null) {
            ui.showSystem("  usage: вход " + orNoData(r.summaryPromptTokens())
                    + " · выход " + orNoData(r.summaryCompletionTokens())
                    + " · время " + ms(r.summaryNanos()) + " мс · finish_reason: "
                    + orNoDataText(r.summaryFinishReason()));
        }

        boolean fullOk = r.fullError() == null;
        boolean compactSkipped = r.summaryError() != null
                && r.summaryError().startsWith("сравнение со сжатием не выполнялось");
        boolean summaryOk = r.summaryError() == null;
        boolean previewFailed = !fullOk || !summaryOk
                || (r.prepPerformed() && r.prepError() != null);
        if (previewFailed) {
            ui.showSystem(warn("Внимание: сравнение НЕ полностью успешное — см. ошибки выше."));
        }
        if (compactSkipped) {
            ui.showSystem("Два одинаковых запроса не выдаются за успешное сравнение: "
                    + "подготовка резюме не удалась. Расход попытки подготовки учтён.");
        }

        StringBuilder table = new StringBuilder("Таблица сравнения (фактические значения usage):");
        table.append("\n  показатель | без сжатия | со сжатием");
        table.append("\n  вход (prompt_tokens) | ")
                .append(orNoData(r.fullPromptTokens())).append(" | ")
                .append(orNoData(r.summaryPromptTokens()));
        table.append("\n  выход (completion_tokens) | ")
                .append(orNoData(r.fullCompletionTokens())).append(" | ")
                .append(orNoData(r.summaryCompletionTokens()));
        long fullSpend = spend(r.fullPromptTokens(), r.fullCompletionTokens());
        long summarySpend = spend(r.summaryPromptTokens(), r.summaryCompletionTokens());
        long prepSpend = spend(r.prepPromptTokens(), r.prepCompletionTokens());
        table.append("\n  расход запроса | ").append(formatSpend(fullSpend, fullOk))
                .append(" | ").append(formatSpend(summarySpend, summaryOk));
        if (r.prepPerformed()) {
            table.append("\n  подготовка summary в этом сравнении: ")
                    .append(r.prepError() != null
                            ? "не удалась: " + r.prepError()
                            : formatSpend(prepSpend, true) + " (вход "
                            + orNoData(r.prepPromptTokens()) + " · выход "
                            + orNoData(r.prepCompletionTokens()) + ")");
        } else {
            table.append("\n  подготовка summary в этом сравнении: ")
                    .append("не выполнялась, использовано сохранённое резюме")
                    .append(" · ранее понесённые затраты суммаризации — отдельно")
                    .append(" (см. /stats, не «бесплатное создание»)");
        }
        table.append("\n  расход FULL | ").append(formatSpend(fullSpend, fullOk));
        table.append("\n  расход SUMMARY | ")
                .append(compactSkipped ? "нет данных (запрос не выполнялся)"
                        : formatSpend(summarySpend, summaryOk));
        table.append("\n  общий расход сравнения | ")
                .append(formatSpend(fullSpend, fullOk))
                .append(" | ")
                .append(compactSkipped ? "нет данных"
                        : formatSpend(summarySpend + (r.prepPerformed()
                        && r.prepError() == null ? prepSpend : 0), summaryOk));
        // Экономия входа — только по фактическим prompt_tokens двух успешных
        // вариантов; если сжатый вариант больше — показываем увеличение.
        String inputDelta;
        if (r.fullPromptTokens() == null || r.summaryPromptTokens() == null) {
            inputDelta = "недостаточно данных (у одного из вариантов usage отсутствует)";
        } else {
            long delta = (long) r.fullPromptTokens() - r.summaryPromptTokens();
            inputDelta = delta >= 0
                    ? "вход меньше без сжатия на " + delta + " prompt_tokens"
                    : "вход со сжатием больше на " + (-delta)
                            + " prompt_tokens (увеличение)";
        }
        table.append("\n  разница входа (по факту prompt_tokens): ").append(inputDelta)
                .append(" · это разница одного запроса, не экономия всей беседы");
        table.append("\n  времена: без сжатия ").append(ms(r.fullNanos()))
                .append(" мс · со сжатием ").append(ms(r.summaryNanos()))
                .append(" мс · подготовка резюме ")
                .append(r.prepPerformed() ? ms(r.prepNanos()) + " мс" : "не выполнялась");
        if (prices) {
            table.append("\n  стоимость (расчётная по тарифу, не списание): без сжатия ")
                    .append(TokenCost.formatUsd(TokenCost.total(
                            TokenCost.perMillion(settings.inputPricePer1M(),
                                    fullSpendKnown(r)),
                            TokenCost.perMillion(settings.outputPricePer1M(),
                                    0))));
            table.append("\n  (стоимость каждого варианта считайте по входу/выходу выше; ")
                    .append("кэширование и reasoning-тарифы простой расчёт не учитывает)");
        } else {
            table.append("\n  стоимость: нет данных — тариф не настроен");
        }
        table.append("\n  экономия входа одного запроса ≠ экономия всего диалога: ")
                .append("подготовка резюме расходует отдельные токены");
        table.append("\n  генерации могут различаться даже при одинаковых настройках; ")
                .append("качество сравнивает пользователь, а не автоматическая оценка");
        ui.showSystem(table.toString());
    }

    private static long fullSpendKnown(LlmAgent.CompareResult r) {
        return spend(r.fullPromptTokens(), r.fullCompletionTokens());
    }

    private static long spend(Integer promptTokens, Integer completionTokens) {
        long known = 0;
        if (promptTokens != null) {
            known += promptTokens;
        }
        if (completionTokens != null) {
            known += completionTokens;
        }
        return known;
    }

    private static String formatSpend(long known, boolean complete) {
        if (complete && known > 0) {
            return String.valueOf(known);
        }
        if (known > 0) {
            return "не менее " + known + " (usage неполный)";
        }
        return "нет данных";
    }

    private static String ms(long nanos) {
        return String.valueOf(nanos / 1_000_000);
    }

    private static String orNoDataText(String value) {
        return value == null ? "нет данных" : value;
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
            case "/help" -> {
                String argument = normalized.length() > "/help".length()
                        ? normalized.substring("/help".length()).trim() : "";
                if (argument.isEmpty() || UiText.lower(argument).startsWith("/help")) {
                    ui.showHelp();
                } else {
                    // «/help task» и «/help /task» — обе формы положены.
                    ui.showCommandHelp(argument.startsWith("/") ? argument : "/" + argument);
                }
            }
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
                        updatePromptLabels(ui, agent, demoRef);
                        ui.showSystem("✓ Начата новая беседа. История очищена.");
                    } catch (ConversationStoreException e) {
                        ui.showError("Сброс не выполнен, история не изменена: " + e.getMessage());
                    }
                } else {
                    ui.showSystem("Сброс отменён.");
                }
            }
            default -> {
                if (normalized.equals("/help") || normalized.startsWith("/help ")) {
                    String argument = normalized.substring("/help".length()).trim();
                    if (argument.isEmpty()) {
                        ui.showHelp();
                    } else {
                        // «/help task» и «/help /task» — обе формы работают.
                        String commandName = argument.startsWith("/")
                                ? argument : "/" + argument;
                        if (TerminalUi.chatCommandHelp(commandName) == null) {
                            ui.showSystem("Нет подробной справки по «" + argument
                                    + "».\n  Попробуйте /help — индекс команд.");
                        } else {
                            ui.showCommandHelp(commandName);
                        }
                    }
                } else if (normalized.equals("/mode") || normalized.startsWith("/mode ")) {
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
     * токены. В режиме измерений очищается только история временной
     * диалога. Команда не вызывает API; при ошибке записи история
     * в памяти сохраняется, CLI продолжает работать.
     */
    private static void handleClearCommand(TerminalUi ui, LlmAgent agent, DemoRef demoRef) {
        boolean demoMode = demoRef.demo != null;
        String subject = demoMode ? "временной беседы измерений" : "текущего диалога";
        if (!ui.confirmHistoryClear(subject)) {
            ui.showSystem("Удаление отменено.");
            return;
        }
        try {
            agent.resetConversation();
            if (demoMode) {
                // Журнал попыток сохраняется: между попытками появляется
                // пометка «история очищена (/clear)».
                demoRef.demo.markHistoryCleared();
                ui.showSystem("✓ История временной беседы измерений удалена. "
                        + "Расход и таблица режима измерений сохранены.");
            } else {
                updatePromptLabels(ui, agent, demoRef);
                ui.showSystem("✓ История текущего диалога удалена. "
                        + "Долговременная память сохранена; статистика сессии сохранена.");
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
            ui.showSystem("✓ Профиль: " + modeSummary(settings)
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
        out.println("LLM_CONTEXT_MODE (full/summary), LLM_CONTEXT_KEEP_LAST_MESSAGES,");
        out.println("LLM_SUMMARY_BATCH_MESSAGES, LLM_SUMMARY_MAX_OUTPUT_TOKENS,");
        out.println("LLM_DIAGNOSTICS, LLM_HISTORY_FILE, LLM_MEMORY_FILE, LLM_PROFILE_FILE");
        out.println("(при запуске через launcher загружаются из локального .env проекта).");
        out.println();
        out.println("История беседы хранится в JSON в ~/.ai-advent-agent/");
        out.println("(по умолчанию — новый файл chat-<дата-время>.json на каждый запуск);");
        out.println("переменная LLM_HISTORY_FILE задаёт фиксированный абсолютный путь, ");
        out.println("LLM_MEMORY_FILE — общий файл долговременной памяти (memory.json).");
        out.println();
        out.println("Команды чата: /help, /history, /tokens, /stats, /limit, /reset, /clear,");
        out.println("/profile, /skill, /pipeline, /memory, /remember, /forget, /task,");
        out.println("/context [full|summary], /context compare <вопрос>, /summary [refresh],");
        out.println("/multiline, /exit (также exit, quit).");
    }

    private Main() {
    }
}
