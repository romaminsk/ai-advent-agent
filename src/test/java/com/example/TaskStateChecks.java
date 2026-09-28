package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.index.ChunkMeta;
import com.example.index.DocumentLoader;
import com.example.index.IndexCommands;
import com.example.index.IndexSearch;
import com.example.index.IndexStore;
import com.example.index.OpenAiEmbedder;
import io.modelcontextprotocol.spec.McpSchema;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArraySet;

final class TaskStateChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkTaskStateMachineModel();
        checkTaskStateEdgeCases();
        checkTaskControlledTransitions();
        checkTaskTransitionsNoApiOnRefusal();
        checkBlockedMessageBarrier();
        checkTaskCommands();
        checkTaskStateCommands();
        checkTaskStateBlockInSystemMessage();
        checkTaskStatePauseResumeInRequest();
    }

     static void checkTaskStateMachineModel() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        TaskState started = TaskState.start("подготовить отчёт к среде", now);
        expect("start создаёт задачу: planning, active, шаг и ожидаемое действие заданы",
                started.stage() == TaskStage.PLANNING
                        && started.status() == TaskStatus.ACTIVE
                        && "сформулировать план".equals(started.currentStep())
                        && "агент предлагает план".equals(started.expectedAction())
                        && started.completedSteps().isEmpty()
                        && "подготовить отчёт к среде".equals(started.description())
                        && !started.updatedAt().isBlank());

        expect("переходы вперёд planning → execution → validation → done допустимы",
                TaskStage.PLANNING.canTransitionTo(TaskStage.EXECUTION)
                        && TaskStage.EXECUTION.canTransitionTo(TaskStage.VALIDATION)
                        && TaskStage.VALIDATION.canTransitionTo(TaskStage.DONE));
        expect("пропуск этапов запрещён (planning → validation/done, execution → done)",
                !TaskStage.PLANNING.canTransitionTo(TaskStage.VALIDATION)
                        && !TaskStage.PLANNING.canTransitionTo(TaskStage.DONE)
                        && !TaskStage.EXECUTION.canTransitionTo(TaskStage.DONE)
                        && !TaskStage.VALIDATION.canTransitionTo(TaskStage.PLANNING));
        expect("DONE → planning запрещён: это новая задача, а не продолжение",
                !TaskStage.DONE.canTransitionTo(TaskStage.PLANNING)
                        && !TaskStage.DONE.canTransitionTo(TaskStage.EXECUTION));
        expect("возврат validation → execution разрешён как исключение и помечен обратным",
                TaskStage.VALIDATION.canTransitionTo(TaskStage.EXECUTION)
                        && TaskStage.VALIDATION.isBackwardTransitionTo(TaskStage.EXECUTION)
                        && !TaskStage.EXECUTION.isBackwardTransitionTo(TaskStage.VALIDATION));

        expect("planning → execution без утверждённого плана отклоняется",
                expectError(() -> started.withStage(TaskStage.EXECUTION, null, now))
                        .contains("утверждённого плана"));
        TaskState planned = started.withPlan("сверить цифры по двум источникам", now);
        expect("план зафиксирован: не утверждён, отметки валидации пусты",
                "сверить цифры по двум источникам".equals(planned.plan())
                        && !planned.planApproved()
                        && planned.validationResult() == null && !planned.validationPassed());
        expect("утверждение без плана отклоняется",
                expectError(() -> started.approvePlan(now)).contains("План не зафиксирован"));
        expect("утверждение на этапе planning остаётся на этапе planning",
                planned.approvePlan(now).stage() == TaskStage.PLANNING
                        && planned.approvePlan(now).planApproved());
        expect("повторное утверждение отклоняется без изменения состояния",
                expectError(() -> planned.approvePlan(now).approvePlan(now))
                        .contains("План уже утверждён"));
        expect("пустой или пробельный план отклоняется",
                expectError(() -> started.withPlan("   ", now)).contains("Текст плана обязателен"));
        expect("замена плана сбрасывает утверждение",
                planned.approvePlan(now).withPlan("другой план", now)
                        instanceof TaskState replaced
                        && !replaced.planApproved()
                        && "другой план".equals(replaced.plan()));
        expect("повторная установка того же текста плана сохраняет утверждение",
                expectError(() -> planned.approvePlan(now)
                        .withPlan("сверить цифры по двум источникам", now))
                        .contains("План уже зафиксирован"));

        TaskState execution = planned.approvePlan(now)
                .withStage(TaskStage.EXECUTION, null, now);
        expect("переход на execution после утверждения плана переносит плановый шаг в выполненные",
                execution.stage() == TaskStage.EXECUTION
                        && execution.currentStep() == null
                        && execution.completedSteps().contains("сформулировать план")
                        && "сверить цифры по двум источникам".equals(execution.plan())
                        && execution.planApproved());
        expect("исходное состояние не изменяется (record)", started.stage() == TaskStage.PLANNING
                && started.currentStep() != null);

        TaskState validation = execution.withStage(TaskStage.VALIDATION, null, now);
        expect("возврат validation → execution без причины отклоняется",
                expectError(() -> validation.withStage(TaskStage.EXECUTION, null, now))
                        .contains("причины"));
        TaskState back = validation.withStage(TaskStage.EXECUTION,
                "итоговые цифры не сошлись", now);
        expect("возврат validation → execution с причиной разрешён",
                back.stage() == TaskStage.EXECUTION);
        expect("возврат validation → execution сбрасывает результат проверки",
                back.validationResult() == null && !back.validationPassed());
        TaskState redone = back.withStage(TaskStage.VALIDATION, null, now);
        expect("повторный вход в validation требует нового результата",
                expectError(() -> redone.withStage(TaskStage.DONE, null, now))
                        .contains("/task validate pass"));
        TaskState validated = redone.withValidationResult(true,
                "расхождений нет", now);
        expect("результат проверки зафиксирован как успешный",
                "расхождений нет".equals(validated.validationResult())
                        && validated.validationPassed());
        expect("pass, затем fail: снова запрещает done",
                expectError(() -> validated.withValidationResult(false, "нашли ошибку", now)
                        .withStage(TaskStage.DONE, null, now))
                        .contains("/task validate pass"));
        expect("неуспешная проверка сама по себе запрещает done",
                expectError(() -> redone.withValidationResult(false, "нашли ошибку", now)
                        .withStage(TaskStage.DONE, null, now))
                        .contains("/task validate pass"));
        expect("пустой результат проверки не принимается",
                expectError(() -> redone.withValidationResult(true, "  ", now))
                        .contains("Текст результата проверки обязателен"));
        String executionDoneRefusal = expectError(() -> execution.withStage(
                TaskStage.DONE, null, now));
        expect("пропуск проверки execution → done: отказ с ближайшим переходом, "
                        + "без предложения validate pass на текущем этапе",
                executionDoneRefusal.contains("пропускать нельзя")
                        && executionDoneRefusal.contains("/task stage validation")
                        && !executionDoneRefusal.contains("/task validate pass"));
        String validationDoneRefusal = expectError(() -> redone.withStage(
                TaskStage.DONE, null, now));
        expect("done без результата: отказ предлагает сначала фактическую проверку, "
                        + "затем фиксацию успеха",
                validationDoneRefusal.contains("фактически проверьте результат")
                        && validationDoneRefusal.contains("/task validate pass"));
        expect("repeat: неуспешная проверка DONE без успеха — сообщение не предлагает pass сразу",
                expectError(() -> redone.withValidationResult(false, "нашли ошибку", now)
                        .withStage(TaskStage.DONE, null, now))
                        .contains("фактически проверьте результат"));
        expect("фиксация результата вне validation отклоняется",
                expectError(() -> execution.withValidationResult(true, "успех", now))
                        .contains("validation"));
        String executionValidationRefusal = expectError(() ->
                execution.withValidationResult(true, "успех", now));
        expect("validate в EXECUTION: подсказка /task stage validation, без утверждения «работа готова»",
                executionValidationRefusal.contains("/task stage validation")
                        && !executionValidationRefusal.contains("работа выполнена"));
        expect("resume повторной активации незавершённой ACTIVE задачи — «уже активна»",
                expectError(() -> execution.withStatus(TaskStatus.ACTIVE, now))
                        .contains("Задача уже активна"));
        TaskState done = validation.withValidationResult(true, "расхождений нет", now)
                .withStage(TaskStage.DONE, null, now);
        expect("done после зафиксированного успешного результата разрешён",
                done.stage() == TaskStage.DONE);
        expect("из DONE нет никаких переходов этапов",
                expectError(() -> done.withStage(TaskStage.PLANNING, null, now))
                        .contains("/task start")
                        && expectError(() -> done.withStage(TaskStage.EXECUTION, null, now))
                        .contains("/task start")
                        && expectError(() -> done.withStage(TaskStage.VALIDATION, null, now))
                        .contains("/task start"));

        // Смена этапа и подтверждения только в ACTIVE.
        TaskState steppedBeforePause = validation.withValidationResult(true, "ок", now)
                .withLocalInvariant(new Invariant("id-pause-1", "только Java 21",
                        "stack", List.of(), java.time.Instant.now()), now);
        TaskState pausedStage = steppedBeforePause.withStatus(TaskStatus.PAUSED, now);
        expect("пауза/resume сохраняют план, утверждение, результат проверки и локальные инварианты",
                resumedEqualsPaused(pausedStage.withStatus(TaskStatus.ACTIVE, now),
                        steppedBeforePause));
        expect("смена этапа на паузе отклоняется (PAUSED нельзя обойти сменой этапа)",
                expectError(() -> pausedStage.withStage(TaskStage.EXECUTION, "доработка", now))
                        .contains("только для активной задачи")
                        && expectError(() -> pausedStage.withStage(TaskStage.DONE, null, now))
                        .contains("только для активной задачи"));
        TaskState pausedSnapshot = pausedStage;
        expect("утверждение плана на паузе отклоняется без изменения всего TaskState",
                expectError(() -> pausedStage.approvePlan(now))
                        .contains("только для активной задачи"));
        expect("фиксация результата проверки на паузе отклоняется без изменения всего TaskState",
                expectError(() -> pausedStage.withValidationResult(true, "ок", now))
                        .contains("только для активной задачи"));
        expect("замена плана на паузе отклоняется без изменения всего TaskState",
                expectError(() -> pausedStage.withPlan("новый план", now))
                        .contains("только для активной задачи"));
        expect("недопустимые действия на паузе не меняют весь TaskState",
                pausedStage.equals(pausedSnapshot));
        TaskState blockedStage = steppedBeforePause.withStatus(TaskStatus.BLOCKED, now);
        expect("завершение задачи при блокировке отклоняется",
                expectError(() -> blockedStage.withStage(TaskStage.DONE, null, now))
                        .contains("только для активной задачи"));
        expect("локальные инварианты сохраняются при блокировке и разблокировке",
                blockedStage.localInvariantsView().size() == 1
                        && blockedStage.withStatus(TaskStatus.ACTIVE, now)
                        .localInvariantsView().size() == 1);

        TaskState stepped = execution.withStep("собрать цифры", now);
        TaskState paused = stepped.withStatus(TaskStatus.PAUSED, now);
        expect("пауза сохраняет этап, текущий шаг и выполненные шаги",
                paused.status() == TaskStatus.PAUSED
                        && paused.stage() == TaskStage.EXECUTION
                        && "собрать цифры".equals(paused.currentStep())
                        && paused.completedSteps().contains("сформулировать план"));
        TaskState resumed = paused.withStatus(TaskStatus.ACTIVE, now);
        expect("resume восстанавливает состояние без потерь",
                resumedEqualsPaused(resumed, paused));
        TaskState blocked = stepped.withStatus(TaskStatus.BLOCKED, now);
        expect("блокировка возможна из active", blocked.status() == TaskStatus.BLOCKED);
        expect("переход пауза → блокировка напрямую запрещён",
                expectError(() -> paused.withStatus(TaskStatus.BLOCKED, now))
                        .contains("не разрешён"));
        expect("повторная пауза отклоняется с подсказкой /task resume",
                expectError(() -> paused.withStatus(TaskStatus.PAUSED, now))
                        .contains("/task resume"));
        expect("unblock возвращает активный статус",
                blocked.withStatus(TaskStatus.ACTIVE, now).status() == TaskStatus.ACTIVE);
    }

     static void checkTaskStateCommands() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            FakeUi startUi = new FakeUi(
                    TerminalUi.Input.command("/task start подготовить отчёт к среде"),
                    TerminalUi.Input.command("/task status"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(startUi, agent, "glm-5.3-flash");
            TaskState started = agent.taskState();
            expect("start создаёт состояние planning/active",
                    started.stage() == TaskStage.PLANNING
                            && started.status() == TaskStatus.ACTIVE
                            && "подготовить отчёт к среде".equals(started.description()));
            expect("подтверждение start в едином стиле",
                    startUi.systems.stream().anyMatch(t -> t.contains("✓ Задача задана")));
            expect("/task status показывает этап, статус, шаг и ожидаемое действие",
                    startUi.systems.stream().anyMatch(t -> t.contains("Состояние задачи")
                            && t.contains("planning") && t.contains("active")
                            && t.contains("сформулировать план")
                            && t.contains("агент предлагает план")));
            expect("короткий /task показывает описание задачи",
                    startUi.systems.stream().anyMatch(t ->
                            t.contains("Задача: подготовить отчёт к среде")));
            expect("команды /task не вызывают API", hitCounter.get() == 0);

            FakeUi stageUi = new FakeUi(
                    TerminalUi.Input.command("/task stage validation"),
                    TerminalUi.Input.command("/task stage execution"),
                    TerminalUi.Input.command("/task approve"),
                    TerminalUi.Input.command("/task plan сверить цифры бюджетов"),
                    TerminalUi.Input.command("/task approve"),
                    TerminalUi.Input.command("/task stage execution"),
                    TerminalUi.Input.command("/task approve"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(stageUi, agent, "glm-5.3-flash");
            expect("пропуск этапа planning → validation отклоняется",
                    stageUi.errors.stream().anyMatch(t -> t.contains("planning → validation")
                            && t.contains("не разрешён")));
            expect("переход execution без утверждённого плана отклоняется с подсказкой",
                    stageUi.errors.stream().anyMatch(t -> t.contains("утверждённого плана")
                            && t.contains("/task approve")));
            expect("утверждение без плана отклоняется, план без утверждения тоже не пускает",
                    stageUi.errors.stream().anyMatch(t -> t.contains("План не зафиксирован")));
            expect("план зафиксирован и утверждён командами (без объединения с переходом)",
                    stageUi.systems.stream().anyMatch(t -> t.contains("✓ План зафиксирован")
                            && t.contains("/task approve"))
                            && stageUi.systems.stream().anyMatch(t -> t.contains("✓ План утверждён")
                            && t.contains("по-прежнему planning")));
            expect("ошибочный переход не меняет состояние, допустимый после утверждения проходит",
                    agent.taskState().stage() == TaskStage.EXECUTION
                            && agent.taskState().completedSteps().contains("сформулировать план")
                            && stageUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Задача переведена на этап execution")));
            expect("повторное утверждение на этапе execution отклоняется",
                    stageUi.errors.stream().anyMatch(t -> t.contains("Утверждение плана — на этапе planning")));

            FakeUi stepsUi = new FakeUi(
                    TerminalUi.Input.command("/task step собрать цифры"),
                    TerminalUi.Input.command("/task expect агент готовит таблицу"),
                    TerminalUi.Input.command("/task step свести таблицу"),
                    TerminalUi.Input.command("/task status"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(stepsUi, agent, "glm-5.3-flash");
            TaskState stepped = agent.taskState();
            expect("текущий шаг задан, прежний перенесён в выполненные",
                    "свести таблицу".equals(stepped.currentStep())
                            && stepped.completedSteps().contains("сформулировать план")
                            && stepped.completedSteps().contains("собрать цифры"));
            expect("ожидаемое действие задано",
                    "агент готовит таблицу".equals(stepped.expectedAction())
                            && stepsUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Ожидаемое действие: «агент готовит таблицу»")));
            expect("/task status показывает выполненные шаги",
                    stepsUi.systems.stream().anyMatch(t -> t.contains("выполненные шаги (2)")
                            && t.contains("- собрать цифры")));

            FakeUi pauseUi = new FakeUi(
                    TerminalUi.Input.command("/task pause"),
                    TerminalUi.Input.command("/task resume"),
                    TerminalUi.Input.command("/task block"),
                    TerminalUi.Input.command("/task unblock"),
                    TerminalUi.Input.command("/task pause"),
                    TerminalUi.Input.command("/task resume"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(pauseUi, agent, "glm-5.3-flash");
            TaskState resumed = agent.taskState();
            expect("пауза и resume сохраняют и восстанавливают состояние",
                    "ACTIVE".equals(resumed.status().name())
                            && "свести таблицу".equals(resumed.currentStep())
                            && "агент готовит таблицу".equals(resumed.expectedAction())
                            && resumed.completedSteps().size() == 2
                            && "подготовить отчёт к среде".equals(resumed.description()));
            expect("подтверждение паузы даёт подсказку /task resume",
                    pauseUi.systems.stream().anyMatch(t -> t.contains("на паузе")
                            && t.contains("/task resume")));
            expect("подтверждение блокировки даёт подсказку /task unblock",
                    pauseUi.systems.stream().anyMatch(t -> t.contains("blocked")
                            && t.contains("/task unblock")));
            String systemsJoined = String.join("\n", pauseUi.systems);
            expect("blocked-сообщение: предупреждение с заметками и подсказкой unblock",
                    systemsJoined.contains("Задача помечена как blocked")
                            && systemsJoined.contains(
                            "Сообщения сохраняются как заметки без вызова модели")
                            && !systemsJoined.contains("запрашивать недостающее"));

            FakeUi stage2Ui = new FakeUi(
                    TerminalUi.Input.command("/task stage validation"),
                    TerminalUi.Input.command("/task stage execution"),
                    TerminalUi.Input.command("/task stage execution проверка не прошла"),
                    TerminalUi.Input.command("/task status"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(stage2Ui, agent, "glm-5.3-flash");
            TaskState state2 = agent.taskState();
            expect("возврат validation → execution с причиной разрешён явно",
                    "EXECUTION".equals(state2.stage().name())
                            && state2.expectedAction().contains("устранить: проверка не прошла"));
            expect("возврат без причины отклонён (причина обязательна)",
                    stage2Ui.errors.stream().anyMatch(t -> t.contains("причины")));

            FakeUi doneUi = new FakeUi(
                    TerminalUi.Input.command("/task stage validation"),
                    TerminalUi.Input.command("/task stage done"),
                    TerminalUi.Input.command("/task validate pass расхождений нет"),
                    TerminalUi.Input.command("/task stage done"),
                    TerminalUi.Input.command("/task stage planning"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(doneUi, agent, "glm-5.3-flash");
            expect("done без результата проверки отклоняется с подсказкой /task validate pass",
                    doneUi.errors.stream().anyMatch(t -> t.contains("/task validate pass")
                            && t.contains("Сообщение модели «всё проверено»")));
            expect("результат проверки зафиксирован командой (оставаясь на validation)",
                    doneUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Результат проверки зафиксирован (успешная)")
                                    && t.contains("Завершение отдельной командой: /task stage done.")));
            expect("этап done подтверждается",
                    agent.taskState().stage() == TaskStage.DONE
                            && doneUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Задача переведена на этап done")));
            expect("DONE → planning отклоняется: это новая задача",
                    agent.taskState().stage() == TaskStage.DONE
                            && doneUi.errors.stream().anyMatch(t ->
                            t.contains("завершённая задача не продолжается")));

            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/task clear"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(clearUi, agent, "glm-5.3-flash");
            expect("/task clear стирает состояние задачи",
                    agent.taskState() == null && agent.currentTask() == null
                            && clearUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Задача очищена")));
            expect("после очистки /task сообщает об отсутствии задачи",
                    clearUi.systems.stream().anyMatch(t -> t.contains("Задача не задана")));
            expect("команды состояния задачи по-прежнему не вызывают API",
                    hitCounter.get() == 0);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkTaskStateBlockInSystemMessage() {
        ModelSettings settings = ModelSettings.defaults();
        UserProfile emptyProfile = UserProfile.empty();
        Map<String, MemoryEntry> emptyMemory = new LinkedHashMap<>();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        ChatMessage withoutTask = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory, null, Map.of(), null, null);
        expect("пустое состояние (задачи нет) — блока «СОСТОЯНИЕ ЗАДАЧИ» нет",
                !withoutTask.content().contains("СОСТОЯНИЕ ЗАДАЧИ"));

        TaskState active = TaskState
                .start("подготовить отчёт к среде", now)
                .withPlan("сверить цифры бюджетов", now)
                .approvePlan(now)
                .withStage(TaskStage.EXECUTION, null, now)
                .withStep("собрать цифры", now)
                .withExpectedAction("агент готовит таблицу", now);
        ChatMessage withTask = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory, active, Map.of(), null, null);
        String block = withTask.content();
        expect("заданное состояние подставлено блоком с заголовком",
                block.contains("<<<СОСТОЯНИЕ ЗАДАЧИ"));
        expect("в блоке этап и статус как инструкции",
                block.contains("Сейчас этап EXECUTION") && block.contains("статус ACTIVE"));
        expect("в блоке текущий шаг и ожидаемое действие",
                block.contains("Текущий шаг: собрать цифры")
                        && block.contains("Ожидаемое действие: агент готовит таблицу"));
        expect("в блоке выполненные шаги", block.contains("сформулировать план"));
        expect("правило неповторения выполненных шагов после resume в блоке",
                block.contains("выполненные шаги не повторяй"));
        expect("правило этапа execution: завершённость запрещена до проверки",
                block.contains("Этап execution")
                        && block.contains("Задачу завершённой не объявляй")
                        && block.contains("/task stage validation"));
        expect("в блоке план с пометкой утверждения",
                block.contains("План (утверждён): сверить цифры бюджетов"));

        // Правило этапа planning: реализация запрещена до EXECUTION; план/утверждение.
        TaskState planning = TaskState.start("подготовить отчёт к среде", now);
        ChatMessage planningSystem = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory, planning, Map.of(), null, null);
        String planningBlock = planningSystem.content();
        expect("правило этапа planning: реализацию не начинать, пропуск плана отсеивается",
                planningBlock.contains("Этап planning")
                        && planningBlock.contains("Реализацию задачи")
                        && planningBlock.contains("пропустить план")
                        && planningBlock.contains("/task plan")
                        && planningBlock.contains("/task approve"));

        // Правило этапа validation: доработка и завершение — только через команды.
        TaskState inValidation = active.withStage(TaskStage.VALIDATION, null, now);
        ChatMessage validationSystem = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory, inValidation, Map.of(), null, null);
        String validationBlock = validationSystem.content();
        expect("правило этапа validation: необъявление done и правила возврата",
                validationBlock.contains("Этап validation")
                        && validationBlock.contains("Исправление реализации не выполняй")
                        && validationBlock.contains("/task validate pass")
                        && validationBlock.contains("/task stage execution <причина>"));
        TaskState withResult = inValidation.withValidationResult(true, "расхождений нет", now);
        ChatMessage resultSystem = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory, withResult, Map.of(), null, null);
        expect("в блоке зафиксированный результат проверки с пометкой",
                resultSystem.content().contains("Результат проверки (успешная): расхождений нет"));

        // DONE: прежняя задача не возобновляется.
        TaskState doneTask = withResult.withStage(TaskStage.DONE, null, now);
        ChatMessage doneSystem = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory, doneTask, Map.of(), null, null);
        expect("правило этапа done: задачу не возобновлять, новая — через /task start",
                doneSystem.content().contains("Задача завершена")
                        && doneSystem.content().contains("не возобновляй")
                        && doneSystem.content().contains("/task start <описание>"));

        ChatMessage pausedSystem = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory,
                active.withStatus(TaskStatus.PAUSED, now), Map.of(), null, null);
        String pausedBlock = pausedSystem.content();
        expect("на паузе блок требует ждать /task resume и не продолжать выполнение",
                pausedBlock.contains("статус PAUSED")
                        && pausedBlock.contains("НЕ продолжай выполнение задачи")
                        && pausedBlock.contains("/task resume"));
        expect("формулировка паузы прежняя (подтверждена живым прогоном), не задет",
                pausedBlock.contains("не решай сам, что")
                        && pausedBlock.contains("задача на паузе"));

        ChatMessage blockedSystem = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory,
                active.withStatus(TaskStatus.BLOCKED, now), Map.of(), null, null);
        String blockedBlock = blockedSystem.content();
        expect("в блокировке блок требует ТОЛЬКО запросить недостающее",
                blockedBlock.contains("статус BLOCKED")
                        && blockedBlock.contains("ТОЛЬКО запрос недостающих")
                        && blockedBlock.contains("В ответе"));
        expect("в блокировке явный запрет продолжать: не выполняй другие шаги, "
                        + "не пиши код, не помечай шаги выполненными",
                blockedBlock.contains("НЕ выполняй другие шаги задачи")
                        && blockedBlock.contains("НЕ пиши код")
                        && blockedBlock.contains("НЕ помечай шаги выполненными")
                        && blockedBlock.contains("только после снятия блокировки"));
        expect("в блокировке нет прежней слабой формулировки «запрашивай … в каждом ответе»",
                !blockedBlock.contains("запрашивай недостающие сведения в каждом ответе"));
    }

     static void checkTaskStatePauseResumeInRequest() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            agent.taskStart("подготовить отчёт к среде");
            agent.taskPlan("сверить цифры бюджетов");
            agent.taskApprove();
            agent.taskStage("execution", null);
            agent.taskStep("собрать цифры продаж");
            agent.taskPause();
            agent.ask("какая погода?");
            String pausedSystem = MAPPER.readTree(lastBody.get()).path("messages")
                    .get(0).path("content").asText();
            expect("на паузе запрос содержит правила ожидания /task resume",
                    pausedSystem.contains("<<<СОСТОЯНИЕ ЗАДАЧИ")
                            && pausedSystem.contains("статус PAUSED")
                            && pausedSystem.contains("/task resume"));
            expect("этап и шаг задачи подставлены в запрос",
                    pausedSystem.contains("этап EXECUTION")
                            && pausedSystem.contains("Текущий шаг: собрать цифры продаж"));

            agent.taskResume();
            agent.ask("продолжаем работу");
            String resumedSystem = MAPPER.readTree(lastBody.get()).path("messages")
                    .get(0).path("content").asText();
            expect("после resume запрос содержит активный статус и текущий шаг",
                    resumedSystem.contains("статус ACTIVE")
                            && resumedSystem.contains("Текущий шаг: собрать цифры продаж")
                            && resumedSystem.contains("Выполнено ранее"));

            agent.resetConversation();
            expect("/clear стирает состояние задачи", agent.taskState() == null);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkTaskStateEdgeCases() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        // Пустое описание — ошибка без создания состояния.
        expect("start без описания отклоняется",
                expectError(() -> TaskState.start("   ", now))
                        .contains("Описание задачи обязательно"));
        expect("withDescription пустым текстом отклоняется",
                expectError(() -> TaskState.start("задача", now)
                        .withDescription("  ", now))
                        .contains("не может быть пустым"));

        // Очень длинный шаг: сохраняется целиком (лимиты живут в командном слое).
        String longStep = "шаг-" + "х".repeat(500) + "-42";
        TaskState longState = TaskState.start("задача", now).withStep(longStep, now);
        expect("шаг длиной 500+ символов сохраняется целиком",
                longStep.equals(longState.currentStep()));

        // Спецсимволы, кавычки, переносы и emoji не ломают состояние record;
        // краевые пробельные символы нормализуются, как во всём коде.
        String trickyDescription = "описание с \"кавычками\", \\backslash, «ёлочки» 🚀";
        String trickyStep = "шаг с \n переносом строки и табом\tвнутри";
        TaskState special = TaskState.start(trickyDescription, now)
                .withStep(trickyStep, now);
        expect("спецсимволы в описании и шаге сохраняются без потерь",
                trickyDescription.equals(special.description())
                        && trickyStep.equals(special.currentStep()));

        // statuses: прямой переход блокировка → пауза запрещён (как пауза → блокировка).
        TaskState blocked = TaskState.start("задача", now)
                .withStatus(TaskStatus.BLOCKED, now);
        expect("прямой переход блокировка → пауза запрещён",
                expectError(() -> blocked.withStatus(TaskStatus.PAUSED, now))
                        .contains("не разрешён"));
    }

     static void checkTaskControlledTransitions() throws IOException {
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        TaskState base = TaskState.start("задача", now)
                .withPlan("план", now).approvePlan(now)
                .withStage(TaskStage.EXECUTION, null, now);
        TaskState snapshot = base;
        expect("замена плана вне planning отклоняется и не меняет состояние",
                expectError(() -> base.withPlan("другой", now))
                        .contains("План фиксируется на этапе planning")
                        && base.equals(snapshot));
        snapshot = base;
        expect("утверждение вне planning отклоняется без изменения состояния",
                expectError(() -> base.approvePlan(now)).contains("Утверждение плана — на этапе planning")
                        && base.equals(snapshot));
        snapshot = base;
        expect("фиксация результата вне validation отклоняется без изменения состояния",
                expectError(() -> base.withValidationResult(true, "ок", now))
                        .contains("validation") && base.equals(snapshot));
        TaskState inValidation = base.withStage(TaskStage.VALIDATION, null, now);
        snapshot = inValidation;
        expect("возврат с пробельной причиной отклоняется без изменения состояния",
                expectError(() -> inValidation.withStage(TaskStage.EXECUTION, "   ", now))
                        .contains("причины") && inValidation.equals(snapshot));
        snapshot = inValidation;
        expect("повторная установка текущего этапа отклоняется без изменения состояния",
                expectError(() -> inValidation.withStage(TaskStage.VALIDATION, null, now))
                        .contains("Задача уже на этапе") && inValidation.equals(snapshot));

        // Локальные инварианты переживают переходы, подтверждения и паузу.
        Invariant local = new Invariant("id-local-1", "только Java 21", "stack",
                List.of(), java.time.Instant.now());
        TaskState withLocal = base.withLocalInvariant(local, now);
        TaskState survived = withLocal.withStage(TaskStage.VALIDATION, null, now)
                .withValidationResult(true, "проверено", now)
                .withStatus(TaskStatus.PAUSED, now)
                .withStatus(TaskStatus.ACTIVE, now);
        expect("локальные инварианты сохраняются при переходах, валидации, паузе и resume",
                survived.localInvariantsView().size() == 1
                        && survived.localInvariantsView().get(0).text().equals("только Java 21")
                        && survived.planApproved()
                        && "проверено".equals(survived.validationResult()));

        // Команды плана/утверждения/валидации без начатой задачи — понятные ошибки.
        expect("план без начатой задачи отклоняется",
                expectError(() -> agent.taskPlan("план")).contains("Задача не начата"));
        expect("утверждение без начатой задачи отклоняется",
                expectError(agent::taskApprove).contains("Задача не начата"));
        expect("валидация без начатой задачи отклоняется",
                expectError(() -> agent.taskValidate(true, "результат"))
                        .contains("Задача не начата"));

        FakeUi syntaxUi = new FakeUi(
                TerminalUi.Input.command("/task validate ok что-то там"),
                TerminalUi.Input.command("/task validate pass"),
                TerminalUi.Input.command("/task план текст"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(syntaxUi, agent, "glm-5.3-flash");
        String syntaxSystems = String.join("\n", syntaxUi.systems);
        String syntaxErrors = String.join("\n", syntaxUi.errors);
        expect("неизвестный результат проверки даст «Использование»",
                syntaxErrors.contains("Использование: /task validate pass <результат>"));
        expect("validate pass без задачи даёт существующее сообщение об отсутствии задачи",
                syntaxErrors.contains("Задача не начата"));
        expect("короткая форма /task <текст> задаёт описание, план не фиксирует",
                agent.taskState() != null
                        && "план текст".equals(agent.taskState().description())
                        && agent.taskState().plan() == null
                        && !agent.taskState().planApproved());

        // Подсказки при отказе validate зависят от фактического состояния
        // (обработчик-команда, полная цепочка, FakeUi). Без прямого перехода
        // в VALIDATION из PLANNING.
        FakeUi hintsUi = new FakeUi(
                TerminalUi.Input.command("/task validate pass р1"),
                TerminalUi.Input.command("/task plan список из плана"),
                TerminalUi.Input.command("/task validate fail р2"),
                TerminalUi.Input.command("/task approve"),
                TerminalUi.Input.command("/task validate pass р3"),
                TerminalUi.Input.command("/task stage execution"),
                TerminalUi.Input.command("/task validate fail р4"),
                TerminalUi.Input.command("/task pause"),
                TerminalUi.Input.command("/task validate pass р5"),
                TerminalUi.Input.command("/task resume"),
                TerminalUi.Input.command("/task block"),
                TerminalUi.Input.command("/task validate pass р6"),
                TerminalUi.Input.command("/task unblock"),
                TerminalUi.Input.command("/task stage validation"),
                TerminalUi.Input.command("/task validate pass"),
                TerminalUi.Input.command("/task validate pass проверка выполнена"),
                TerminalUi.Input.command("/task stage done"),
                TerminalUi.Input.command("/task validate pass р8"),
                TerminalUi.Input.command("/task resume"),
                TerminalUi.Input.command("/task status"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(hintsUi, agent, "glm-5.3-flash");
        List<String> hintErrors = hintsUi.errors;
        expect("validate в PLANNING без плана: подсказка /task plan, без прямого входа в validation",
                expectError(() -> TaskState.start("з", now)
                        .withValidationResult(true, "р", now))
                        .contains("/task plan <текст>")
                        && !expectError(() -> TaskState.start("з", now)
                        .withValidationResult(true, "р", now))
                        .contains("/task stage validation"));
        expect("validate в PLANNING с неутверждённым планом: подсказка /task approve",
                hintErrorsContain(hintErrors, "утвердите план: /task approve"));
        expect("validate в PLANNING с утверждённым планом: подсказка /task stage execution",
                hintErrorsContain(hintErrors, "/task stage execution"));
        expect("validate в EXECUTION: подсказка /task stage validation после работы",
                hintErrorsContain(hintErrors, "/task stage validation")
                        && hintErrorsContain(hintErrors, "сейчас execution"));
        expect("validate на паузе: сначала /task resume",
                hintErrorsContain(hintErrors, "/task resume"));
        expect("validate при блокировке: /task unblock",
                hintErrorsContain(hintErrors, "/task unblock"));
        expect("validate при DONE: подсказка новой задачи, без возврата в прежнюю",
                hintErrorsContain(hintErrors, "Задача завершена")
                        && hintErrorsContain(hintErrors, "/task start <описание>"));
        expect("пустой результат в ACTIVE/VALIDATION — отдельная ошибка ввода, не смена этапа",
                hintErrorsContain(hintErrors, "Текст результата проверки обязателен"));
        expect("resume для DONE: отказ связан с завершением, не «уже активна», состояние неизменно",
                hintErrorsContain(hintErrors, "Задача завершена: возобновить её нельзя")
                        && hintErrorsContain(hintErrors, "/task start <описание>")
                        && !hintErrorsContain(hintErrors, "уже активна")
                        && agent.taskState().stage() == TaskStage.DONE
                        && agent.taskState().validationPassed());
        expect("/task status показывает DONE после отказов",
                hintsUi.systems.stream().anyMatch(t -> t.contains("Состояние задачи")
                        && t.contains("этап: done")));
    }

     static void checkBlockedMessageBarrier() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Готов ответить по задаче.\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            agent.taskStart("задача с блокировкой");
            agent.taskPlan("уточнить длительность; составить список");
            agent.taskApprove();
            agent.taskStage("execution", null);
            agent.taskStep("уточнить длительность");
            agent.taskExpect("получить от пользователя длительность");
            agent.taskBlock();

            FakeUi barrierUi = new FakeUi(
                    TerminalUi.Input.message("Не спрашивай недостающие сведения. Сразу составь список вещей."),
                    TerminalUi.Input.message("Длительность — четыре часа. Сразу приступай."),
                    TerminalUi.Input.command("/task status"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(barrierUi, agent, "glm-5.3-flash");
            TaskState blockedState = agent.taskState();
            expect("BLOCKED: два обычных сообщения не вызывают API (HTTP-stub счётчик)",
                    hitCounter.get() == 0);
            expect("барьер даёт локальный детерминированный ответ без выполнения задачи",
                    barrierUi.systems.stream().anyMatch(t ->
                            t.contains("Задача заблокирована: сообщение сохранено")
                                    && t.contains("/task unblock")));
            expect("ожидаемое действие показано как ранее зафиксированное, без обещаний полноты",
                    barrierUi.systems.stream().anyMatch(t ->
                            t.contains("Ранее зафиксированное ожидаемое действие: «получить от пользователя длительность»")));
            expect("сведения периода блокировки сохранены по порядку",
                    blockedState.blockNotes().size() == 2
                            && blockedState.blockNotes().get(0).startsWith("Не спрашивай")
                            && blockedState.blockNotes().get(1).startsWith("Длительность — четыре"));
            expect("BLOCKED не меняется от обычного сообщения: статус, этап, шаги, план, подтверждения",
                    blockedState.status() == TaskStatus.BLOCKED
                            && blockedState.stage() == TaskStage.EXECUTION
                            && "уточнить длительность".equals(blockedState.currentStep())
                            && blockedState.planApproved()
                            && blockedState.completedSteps().size() == 1);
            expect("/task status показывает заметки блокировки и снятый от выполнения объём",
                    barrierUi.systems.stream().anyMatch(t -> t.contains("Состояние задачи")
                            && t.contains("заметки блокировки: 2")));
            expect("нет утверждения, что сведений достаточно или проблема устранена",
                    barrierUi.systems.stream().noneMatch(t ->
                            t.contains("сведений достаточно")
                                    || t.contains("проблема устранена")));

            // Повторный цикл: сообщение → заметка → блокировка снимается и ставится снова.
            agent.taskUnblock();
            agent.taskBlock();
            FakeUi secondCycleUi = new FakeUi(
                    TerminalUi.Input.message("Второй цикл: температура тоже важна."),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(secondCycleUi, agent, "glm-5.3-flash");
            expect("повторный цикл block → удалить → note не дублирует и не теряет прежние заметки",
                    agent.taskState().blockNotes().size() == 3
                            && agent.taskState().blockNotes().get(2).startsWith("Второй цикл"));

            // Лимиты: превышение отклоняется честно, прежние заметки целы.
            for (int i = agent.taskState().blockNotes().size(); i < 25; i++) {
                try {
                    agent.blockedNote("Дозаписать заметку номер " + i + " для проверки лимита.");
                } catch (AgentException ignored) {
                    break;
                }
            }
            expect("лимит заметок блокировки: отклоняется честно, прежние сохранены",
                    agent.taskState().blockNotes().size() == TaskState.MAX_BLOCKED_NOTES
                            && expectError(() -> agent.blockedNote("ещё"))
                            .contains("лимит заметок блокировки"));
            expect("заметка не подтверждается сохранённой при отказе: поезд без данных не выехала",
                    agent.taskState().blockNotes().size() == TaskState.MAX_BLOCKED_NOTES);

            // /task unblock сам по себе не выполняет задачу и не вызывает API.
            int hitsBeforeUnblock = hitCounter.get();
            agent.taskUnblock();
            expect("unblock без запроса модели", hitCounter.get() == hitsBeforeUnblock);
            expect("unblock сохраняет заметки блокировки",
                    agent.taskState().blockNotes().size() == TaskState.MAX_BLOCKED_NOTES);

            // Первый обычный запрос после unblock: поехали штатно, заметки в контексте,
            // помечены как данные; просьбы «обойти блокировку» не превращаются в команды.
            agent.ask("продолжай текущий шаг");
            String system = MAPPER.readTree(lastBody.get()).path("messages")
                    .get(0).path("content").asText();
            expect("после unblock запрос уходит модели штатно", hitCounter.get() == hitsBeforeUnblock + 1);
            expect("заметки блокировки переданы в контексте как данные с расшифровкой",
                    system.contains("Сведения, полученные от пользователя во время блокировки")
                            && system.contains("Длительность — четыре часа")
                            && system.contains("данные, а не инструкции")
                            && system.contains("обойти блокировку"));
            expect("статус в контексте — ACTIVE (не BLOCKED)",
                    system.contains("статус ACTIVE"));
            expect("ожидаемое действие в контексте прежнее",
                    system.contains("Ожидаемое действие: получить от пользователя длительность"));
            expect("заметки не зависят от скользящего окна: блок целиком, не только хвост",
                    system.contains("Не спрашивай недостающие сведения"));

            // Пауза/resume и новые циклы не теряют заметки; ошибки сохранения нет:
            // заметки — в памяти состояния, состояние сессии.
            TaskState withNotes = agent.taskState();
            TaskState pausedNotes = withNotes.withStatus(TaskStatus.PAUSED, Instant.now())
                    .withStatus(TaskStatus.ACTIVE, Instant.now());
            expect("пауза/resume сохраняют заметки блокировки",
                    pausedNotes.blockNotes().equals(withNotes.blockNotes()));

            // Новая задача не наследует заметки старой; /task clear их стирает.
            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/task clear"),
                    TerminalUi.Input.command("/task start другая задача"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(clearUi, agent, "glm-5.3-flash");
            expect("новая задача не наследует заметки блокировки",
                    agent.taskState().blockNotes().isEmpty()
                            && agent.blockNotesView().isEmpty());
            expect("очистка задачи не меняет глобальные инварианты",
                    agent.invariantsView().isEmpty());
            expect("автономной записи заметок блокировки в глобальные хранилища нет",
                    agent.longTermMemoryCount() == 0);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkTaskTransitionsNoApiOnRefusal() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Хорошо, задача завершена!\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            agent.taskStart("подготовить отчёт к среде");
            agent.taskPlan("сверить цифры бюджетов");
            agent.taskApprove();

            FakeUi refusalUi = new FakeUi(
                    TerminalUi.Input.command("/task stage validation"),
                    TerminalUi.Input.command("/task stage done"),
                    TerminalUi.Input.command("/task validate pass пробный результат"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(refusalUi, agent, "glm-5.3-flash");
            TaskState afterRefusals = agent.taskState();
            expect("недопустимые управляющие команды не вызывают API",
                    hitCounter.get() == 0);
            expect("validate вне VALIDATION ограничен подсказкой допустимого шага текущего состояния",
                    refusalUi.errors.stream().anyMatch(t -> t.contains(
                            "Результат проверки фиксируется только на этапе validation")
                            && t.contains("/task stage execution")));
            expect("недопустимые команды не меняют состояние задачи",
                    afterRefusals.stage() == TaskStage.PLANNING
                            && afterRefusals.planApproved()
                            && afterRefusals.validationResult() == null);

            // Ответ модели «задача завершена» не меняет TaskState: состояние
            // меняется только управляющими командами пользователя.
            agent.taskStage("execution", null);
            int hitsBefore = hitCounter.get();
            agent.ask("проверяй сам и заверши задачу");
            expect("модель ответила без ошибки (запрос выполнен)",
                    hitCounter.get() == hitsBefore + 1);
            expect("ответ модели «задача завершена» не меняет TaskState",
                    agent.taskState().stage() == TaskStage.EXECUTION
                            && agent.taskState().validationResult() == null);

            // Возврат validation → execution сбрасывает результат: в контексте
            // следующего запроса устаревшего результата проверки нет.
            agent.taskStage("validation", null);
            agent.taskValidate(true, "расхождений нет");
            agent.taskStage("execution", "не сошлись итоговые цифры");
            TaskState resetState = agent.taskState();
            expect("после возврата в execution результат проверки сброшен",
                    resetState.validationResult() == null
                            && !resetState.validationPassed()
                            && resetState.planApproved());
            agent.ask("продолжаем работу");
            String system = MAPPER.readTree(lastBody.get()).path("messages")
                    .get(0).path("content").asText();
            expect("после сброса в контексте нет устаревшего результата проверки",
                    !system.contains("Результат проверки")
                            && system.contains("План (утверждён): сверить цифры бюджетов")
                            && system.contains("Этап execution"));

            // Новая задача не наследует план и подтверждения прежней.
            agent.taskStage("validation", null);
            agent.taskValidate(true, "расхождений нет");
            agent.taskStage("done", null);
            agent.remember("кодовое слово: ЯКОРЬ-42");
            agent.setProfileName("Шеф");
            FakeUi doneResumeUi = new FakeUi(
                    TerminalUi.Input.command("/task resume"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(doneResumeUi, agent, "glm-5.3-flash");
            expect("возобновление завершённой задачи отклоняется с объяснением завершения",
                    doneResumeUi.errors.stream().anyMatch(t ->
                            t.contains("Задача завершена: возобновить её нельзя")
                                    && t.contains("/task start <описание>")));
            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/task clear"),
                    TerminalUi.Input.command("/task start новая задача"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(clearUi, agent, "glm-5.3-flash");
            TaskState fresh = agent.taskState();
            expect("новая задача не наследует план и подтверждения прежней",
                    fresh.stage() == TaskStage.PLANNING
                            && fresh.plan() == null
                            && !fresh.planApproved()
                            && fresh.validationResult() == null
                            && !fresh.validationPassed());
            expect("после /task clear локальных инвариантов нет",
                    agent.taskInvariantsView().isEmpty()
                            && agent.invariantsView().isEmpty());
            expect("очистка задачи не меняет долговременную память и профиль",
                    agent.longTermMemoryCount() == 1
                            && "Шеф".equals(agent.userProfile().name()));

            FakeUi invariantUi = new FakeUi(
                    TerminalUi.Input.command("/task invariant add только Java 21 stack"),
                    TerminalUi.Input.command("/task invariant"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(invariantUi, agent, "glm-5.3-flash");
            expect("/task invariant add работает для новой задачи",
                    agent.taskInvariantsView().size() == 1);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkTaskCommands() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/task подготовить отчёт к среде"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/task без аргумента сообщает об отсутствии задачи",
                    ui.systems.stream().anyMatch(t -> t.contains("Задача не задана")));
            expect("/task устанавливает задачу рабочей памяти",
                    agent.currentTask() != null
                            && agent.currentTask().contains("отчёт к среде"));
            expect("установленная задача отображается", agent.currentTask() != null);
            expect("установленная задача видна в выводе /task",
                    ui.systems.stream().anyMatch(t -> t.contains("Задача: подготовить отчёт к среде")));
            expect("подтверждение установки показано",
                    ui.systems.stream().anyMatch(t -> t.contains("Задача задана")));

            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/task clear"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(clearUi, agent, "glm-5.3-flash");
            expect("/task clear очищает задачу, не трогая факты",
                    agent.currentTask() == null
                            && clearUi.systems.stream().anyMatch(t ->
                            t.contains("Задача очищена")));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }
}
