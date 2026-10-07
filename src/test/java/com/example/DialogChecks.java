package com.example;

import com.example.rag.CitationValidator;
import com.example.rag.RagService;
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

final class DialogChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkConfigErrors();
        checkEmptyQueryNoApiCall();
        checkDialogOnLocalServer();
        checkRequestParameters();
        checkContextLimit();
        checkErrorClassification();
        checkLocalOllamaConfig();
        checkModelProfileSwitch();
        checkModelProfileStartupRollback();
        checkModelCommandHistoryHints();
        checkRagContextGuardLogic();
        checkRagBenchPureLogic();
        checkOllamaProfileLoopbackGuard();
        checkOllamaContextProbeParsing();
    }

    /** RagContextGuard: пороги чисто, без сети. */
    private static void checkRagContextGuardLogic() {
        Integer numCtx = 8192;
        String proximity = com.example.rag.RagContextGuard.warning(
                7373, 818, numCtx);
        expect("guard: prompt_tokens на границе 90% num_ctx даёт предупреждение",
                proximity != null && proximity.contains("90%") && proximity.contains("8192"));
        String overflow = com.example.rag.RagContextGuard.warning(
                1254, 1024, 2048);
        expect("guard: prompt_tokens + max_tokens > num_ctx даёт предупреждение",
                overflow != null && overflow.contains("превышает")
                        && overflow.contains("num_ctx=2048"));
        String quiet = com.example.rag.RagContextGuard.warning(
                300, 1024, numCtx);
        expect("guard: маленький промпт — предупреждения нет", quiet == null);
        String unknown = com.example.rag.RagContextGuard.warning(
                1500, 1024, null);
        expect("guard: неизвестный num_ctx явно предупреждает, не молчит",
                unknown != null && unknown.contains("не определён"));
        String nullTokens = com.example.rag.RagContextGuard.warning(
                null, 1024, numCtx);
        expect("guard: нет данных usage — предупреждения нет", nullTokens == null);
    }

    /** RagBench: any-of факты, цитаты, медиана/p95, стабильность. */
    private static void checkRagBenchPureLogic() {
        List<List<String>> groups = List.of(
                List.of("800", "восемьсот"),
                List.of("перекрытие", "overlay"));
        expect("RagBench.anyOfHit находит любой вариант группы без учёта регистра",
                com.example.rag.RagBench.anyOfHit("Окно ВосемьСот символов", groups.get(0))
                        && com.example.rag.RagBench.anyOfHit("Перекрытие 100", groups.get(1)));
        expect("RagBench.anyOfHit не находит отсутствующее",
                !com.example.rag.RagBench.anyOfHit("ничего не найдено", groups.get(0)));
        expect("RagBench.factGroupsShare считает долю групп",
                Math.abs(com.example.rag.RagBench.factGroupsShare(
                        "окно 800 и перекрытие", groups) - 1.0) < 1e-9
                        && Math.abs(com.example.rag.RagBench.factGroupsShare(
                        "окно 800", groups) - 0.5) < 1e-9);
        List<Long> values = List.of(300L, 100L, 200L, 400L);
        expect("RagBench.median по отсортированным значениям с интерполяцией",
                Math.abs(com.example.rag.RagBench.median(values) - 250) < 1e-9
                        && Math.abs(com.example.rag.RagBench.median(List.of(7L)) - 7) < 1e-9);
        expect("RagBench.percentile95 возвращает p95 с линейной интерполяцией",
                Math.abs(com.example.rag.RagBench.percentile95(List.of(
                        10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)) - 95.5) < 1e-9);
        // Стабильность: 2 вопроса, у 1 исходы одинаковые, у 2 — разные.
        List<com.example.rag.RagBench.Attempt> attempts = List.of(
                benchAttempt("q1", "ok"), benchAttempt("q1", "ok"),
                benchAttempt("q2", "ok"), benchAttempt("q2", "timeout"));
        expect("RagBench.stableShare: одинаковый исход у половины вопросов",
                Math.abs(com.example.rag.RagBench.stableShare(attempts) - 0.5) < 1e-9);
        // Исходы через RagService.Result поверх предсказуемых AnswerStatus.
        expect("RagBench.correctNoAnswer требует пустых подтверждённых цитат",
                !com.example.rag.RagBench.correctNoAnswer(new RagService.Result(
                        "НЕ ЗНАЮ", List.of(), List.of(), 0, 0,
                        RagService.Status.OK, null, 0, false, false, "q", 0,
                        "off", CitationValidator.AnswerStatus.IDK_MODEL,
                        new CitationValidator.Validation(List.of(), List.of(
                                new CitationValidator.Quote(1, "q")), 1, 0, 0))));
        expect("RagBench.outcome различает пустой и ошибочный статус",
                com.example.rag.RagBench.outcome(new RagService.Result(
                        "", List.of(), List.of(), 0, 0, RagService.Status.EMPTY, "e",
                        0, false, false, "q", 0, "off")).equals("empty"));
    }

    private static com.example.rag.RagBench.Attempt benchAttempt(String id, String outcome) {
        return new com.example.rag.RagBench.Attempt(id, "ollama", 1, outcome,
                0, 0, 0, null, null, 0, 0, 0, false, List.of(), "");
    }

    /** Профиль ollama жёстко ограничен loopback (не-https внешние узлы и https). */
    private static void checkOllamaProfileLoopbackGuard() {
        try {
            ModelProfiles.ollama(Map.of("OLLAMA_API_URL",
                    "https://api.example.com/v1/chat/completions"));
            expect("профиль ollama на не-loopback отклоняется", false);
        } catch (AgentException expected) {
            expect("профиль ollama на не-loopback отклоняется",
                    expected.getMessage().contains("loopback"));
        }
        try {
            Config.requireLoopbackUrl("http://example.com/v1", "EMBEDDING_BASE_URL");
            expect("requireLoopbackUrl отклоняет внешний адрес", false);
        } catch (AgentException expected) {
            expect("requireLoopbackUrl отклоняет внешний адрес",
                    expected.getMessage().contains("loopback"));
        }
        try {
            Config.requireLoopbackUrl("http://localhost:11434/v1", "EMBEDDING_BASE_URL");
            expect("requireLoopbackUrl допускает loopback", true);
        } catch (AgentException unexpected) {
            expect("requireLoopbackUrl допускает loopback", false);
        }
    }

    /** Разбор /api/ps и /api/show без сети (строки для OllamaContextProbe). */
    private static void checkOllamaContextProbeParsing() {
        Integer live = com.example.rag.OllamaContextProbe.fromPs(
                "{\"models\":[{\"name\":\"qwen2.5:3b-rag8k\",\"context_length\":8192}]}",
                "qwen2.5:3b-rag8k");
        expect("Probe.fromPs извлекает context_length загруженного тега",
                live != null && live == 8192);
        Integer parameters = com.example.rag.OllamaContextProbe.fromShowParameters(
                "{\"parameters\":\"seed 0\\nnum_ctx 8192\\nstop <s>\"}");
        expect("Probe.fromShowParameters извлекает num_ctx из Modelfile-параметров",
                parameters != null && parameters == 8192);
        expect("Probe.fromShowParameters не находит num_ctx в пустом ответе",
                com.example.rag.OllamaContextProbe.fromShowParameters(
                        "{\"parameters\":\"\"}") == null);
        expect("Probe.fromPs не находит чужую модель",
                com.example.rag.OllamaContextProbe.fromPs(
                        "{\"models\":[{\"name\":\"other\",\"context_length\":4096}]}",
                        "qwen2.5:3b-rag8k") == null);
    }

     static void checkModelProfileStartupRollback() throws IOException {
        // Изоляция: свойство указывает на файл во временном каталоге suite.
        Path directory = Files.createTempDirectory(baseTempDir, "model-rollback-");
        Path file = directory.resolve("model-profile.json");
        System.setProperty("ai-agent.model-profile-file", file.toString());
        try {
            new ModelProfileStore(file).save("ollama");
            Config cloudConfig = new Config("test-key",
                    "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newAgentWithTempStore(cloudConfig);
            // Закрытый порт: ollama недоступна → откат в cloud.
            ModelProfiles.Profile closedOllama = new ModelProfiles.Profile("ollama",
                    "http://127.0.0.1:1/v1/chat/completions", "qwen2.5:3b", "ollama", 300);
            Main.applySavedModelProfile(agent, closedOllama);
            expect("после старта с недоступной ollama файл профиля откатан на cloud",
                    ModelProfiles.CLOUD.equals(new ModelProfileStore(file).load()));
            expect("после отката сессия действует cloud без смены модели",
                    ModelProfiles.CLOUD.equals(agent.currentProfileName())
                            && "glm-5.3-flash".equals(agent.modelName())
                            && agent.currentSettings().requestTimeoutSeconds() == 180);
        } finally {
            System.clearProperty("ai-agent.model-profile-file");
        }
    }

     static void checkModelCommandHistoryHints() throws Exception {
        // Порог подсказки: 6 обменов — нет строки, 7 — есть.
        Config cloudConfig = new Config("secret-stub-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash");
        LlmAgent agent = newAgentWithTempStore(cloudConfig);
        for (int i = 0; i < 6; i++) {
            agent.recordLocalAnswer("вопрос " + i, "ответ " + i);
        }
        expect("6 обменов в истории — подсказки длинной истории нет",
                Main.modelOllamaLongHistoryHint(agent) == null);
        agent.recordLocalAnswer("вопрос 6", "ответ 6");
        String hint = Main.modelOllamaLongHistoryHint(agent);
        expect("7 обменов — подсказка про длинную историю и /clear",
                hint != null && hint.contains("Длинная история")
                        && hint.contains("/clear"));

        // Переключение на ollama через обработчик с живым stub /api/tags:
        // примечание RAG + подсказка по порогу; ключ не печатается; файл сохранён.
        Path directory = Files.createTempDirectory(baseTempDir, "model-hint-");
        Path file = directory.resolve("model-profile.json");
        System.setProperty("ai-agent.model-profile-file", file.toString());
        try {
            int[] portHolder = startPlainEphemeralServer();
            try {
                ModelProfiles.Profile liveOllama = new ModelProfiles.Profile("ollama",
                        "http://127.0.0.1:" + portHolder[0] + "/v1/chat/completions",
                        "qwen2.5:3b", "stub-key-secret", 300);
                FakeUi ui = new FakeUi();
                Main.handleModelCommand(ui, agent, "/model ollama", null, liveOllama);
                String joined = String.join("\n", ui.systems) + "\n"
                        + String.join("\n", ui.errors);
                expect("/model ollama на живом stub переключает профиль",
                        joined.contains("✓ Профиль: ollama")
                                && "ollama".equals(agent.currentProfileName())
                                && "qwen2.5:3b".equals(agent.modelName())
                                && agent.currentSettings().requestTimeoutSeconds() == 300);
                expect("подсказка ключей истории: RAG-примечание и /clear присутствуют",
                        joined.contains("менее надёжны")
                                && joined.contains("Длинная история"));
                expect("вывод переключения не содержит ключ профиля",
                        !joined.contains("stub-key-secret"));
                expect("профиль сохранён в изолированный файл",
                        "ollama".equals(new ModelProfileStore(file).load()));
            } finally {
                stopPlainEphemeralServer();
            }

            // Короткая история: примечание есть, строки про длинную историю нет.
            int[] secondPort = startPlainEphemeralServer();
            try {
                Config shortCloud = new Config("test-key",
                        "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash");
                LlmAgent shortAgent = newAgentWithTempStore(shortCloud);
                ModelProfiles.Profile liveOllama = new ModelProfiles.Profile("ollama",
                        "http://127.0.0.1:" + secondPort[0] + "/v1/chat/completions",
                        "qwen2.5:3b", "ollama", 300);
                FakeUi stillUi = new FakeUi();
                Main.handleModelCommand(stillUi, shortAgent, "/model ollama", null,
                        liveOllama);
                String stillJoined = String.join("\n", stillUi.systems) + "\n"
                        + String.join("\n", stillUi.errors);
                expect("короткая история: без строки про длинную историю",
                        stillJoined.contains("менее надёжны")
                                && !stillJoined.contains("Длинная история"));
            } finally {
                stopPlainEphemeralServer();
            }
        } finally {
            System.clearProperty("ai-agent.model-profile-file");
        }
    }

     static void checkConfigErrors() {
        expect("отсутствует LLM_API_KEY",
                expectConfigError(null, "https://example.com/v1/chat/completions", "glm-5.3-flash")
                        .contains("LLM_API_KEY"));

        expect("пустой LLM_MODEL",
                expectConfigError("test-key", "https://example.com/v1/chat/completions", "  ")
                        .contains("LLM_MODEL"));

        expect("URL без HTTPS отклоняется",
                expectConfigError("test-key", "http://example.com/v1/chat/completions", "glm-5.3-flash")
                        .contains("HTTPS"));
    }

     static void checkEmptyQueryNoApiCall() {
        Config config = new Config("test-key", "https://127.0.0.1:1/v1/chat/completions", "test-model");
        LlmAgent agent = newAgentWithTempStore(config);
        // Порт 1 закрыт: если бы API вызывался, получили бы сетевую ошибку,
        // а не сообщение о пустом запросе.
        String message = expectAgentError(agent, "   ");
        expect("пустой ввод не вызывает API", message.contains("Пустой запрос"));
    }

     static void checkDialogOnLocalServer() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        // Состояние, наблюдаемое обработчиком сервера.
        AtomicInteger successCounter = new AtomicInteger();
        AtomicInteger hitCounter = new AtomicInteger();
        List<String> sessions = new ArrayList<>();
        AtomicReference<String> lastAuth = new AtomicReference<>();
        AtomicReference<String> lastBody = new AtomicReference<>();
        try {
            HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
                hitCounter.incrementAndGet();
                sessions.add(session);
                lastAuth.set(auth);
                lastBody.set(requestBody);

                int status;
                String body;
                // Сценарий выбираем по маркеру в сообщении пользователя; без маркера —
                // успешный ответ с уникальным текстом «Ответ N».
                if (requestBody.contains("case=bad-json")) {
                    status = 200;
                    body = "это не JSON";
                } else if (requestBody.contains("case=http-500")) {
                    status = 500;
                    body = "{\"error\":{\"message\":\"internal\"}}";
                } else if (requestBody.contains("case=no-choices")) {
                    status = 200;
                    body = "{\"object\":\"chat.completion\"}";
                } else if (requestBody.contains("case=empty-content")) {
                    status = 200;
                    body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
                } else {
                    status = 200;
                    body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Ответ "
                            + successCounter.incrementAndGet() + "\"}}]}";
                }
                return new Response(status, body.getBytes(StandardCharsets.UTF_8));
            });
            try {
                Config config = new Config("test-key",
                        "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                        "glm-5.3-flash");
                JsonConversationStore store = tempStore();
                // Стратегия branching: без веток контекст — вся история дословно
                // (как в режиме full Дня 9), поэтому этот общий диалог проверяет
                // прежние семантики после появления стратегий.
                LlmAgent agent = new LlmAgent(config, ModelSettings.from(branchingEnv()),
                        trustedHttpClient(keyStore), store, tempMemoryStore());
                Path historyFile = store.file();

                // --- Первый запрос: system + user ---
                String question1 = "Ответь одним словом: столица Франции?";
                String answer1 = agent.ask(question1);
                expect("первый ответ получен от тестового сервера", "Ответ 1".equals(answer1));

                // Пара user/assistant сразу сохраняется в файл истории.
                JsonNode savedFile = MAPPER.readTree(
                        Files.readString(historyFile, StandardCharsets.UTF_8));
                expect("после первого ответа пара сохранена в файл истории",
                        savedFile.path("messages").size() == 2
                                && question1.equals(savedFile.path("messages").get(0).path("content").asText())
                                && "Ответ 1".equals(savedFile.path("messages").get(1).path("content").asText()));

                JsonNode firstBody = MAPPER.readTree(lastBody.get());
                expect("первый запрос содержит ровно два сообщения",
                        firstBody.path("messages").size() == 2);
                expect("первое сообщение запроса — system с ожидаемым текстом",
                        "system".equals(firstBody.path("messages").get(0).path("role").asText())
                                && SYSTEM_PROMPT_TEXT.equals(
                                firstBody.path("messages").get(0).path("content").asText()));
                expect("второе сообщение запроса — user с текстом вопроса",
                        "user".equals(firstBody.path("messages").get(1).path("role").asText())
                                && question1.equals(
                                firstBody.path("messages").get(1).path("content").asText()));
                expect("в теле запроса model из конфигурации",
                        "glm-5.3-flash".equals(firstBody.path("model").asText()));
                expect("Authorization имеет вид Bearer <ключ>",
                        "Bearer test-key".equals(lastAuth.get()));

                // --- История после первого запроса ---
                expect("история содержит ровно одну пару user/assistant без system",
                        historyEquals(agent.getHistory(),
                                new ChatMessage("user", question1),
                                new ChatMessage("assistant", "Ответ 1")));

                // --- Второй запрос: system → user → assistant → user ---
                String question2 = "Какой мой второй вопрос?";
                String answer2 = agent.ask(question2);
                expect("второй ответ получен от тестового сервера", "Ответ 2".equals(answer2));

                JsonNode secondBody = MAPPER.readTree(lastBody.get());
                JsonNode secondMessages = secondBody.path("messages");
                expect("второй запрос содержит system, пару user/assistant и нового user",
                        secondMessages.size() == 4
                                && "system".equals(secondMessages.get(0).path("role").asText())
                                && "user".equals(secondMessages.get(1).path("role").asText())
                                && "assistant".equals(secondMessages.get(2).path("role").asText())
                                && "user".equals(secondMessages.get(3).path("role").asText()));
                expect("порядок и текст сообщений второго запроса сохранены",
                        question1.equals(secondMessages.get(1).path("content").asText())
                                && "Ответ 1".equals(secondMessages.get(2).path("content").asText())
                                && question2.equals(secondMessages.get(3).path("content").asText()));

                expect("история содержит обе пары в исходном порядке",
                        historyEquals(agent.getHistory(),
                                new ChatMessage("user", question1),
                                new ChatMessage("assistant", "Ответ 1"),
                                new ChatMessage("user", question2),
                                new ChatMessage("assistant", "Ответ 2")));
                expect("ответ assistant добавлен в историю ровно один раз",
                        agent.getHistory().stream()
                                .filter(m -> "assistant".equals(m.role()) && "Ответ 1".equals(m.content()))
                                .count() == 1);

                // --- Session ID стабилен внутри беседы ---
                expect("session ID стабилен до сброса",
                        sessions.size() == 2 && sessions.get(0) != null
                                && sessions.get(0).equals(sessions.get(1)));

                // --- Ошибки не меняют историю ---
                List<ChatMessage> historyBeforeErrors = agent.getHistory();

                expect("HTTP 500 даёт понятную ошибку",
                        expectAgentError(agent, "case=http-500").contains("HTTP-статус 500"));
                expect("после HTTP-ошибки история неизменна",
                        historyEquals(agent.getHistory(), historyBeforeErrors));

                expect("некорректный JSON распознаётся",
                        expectAgentError(agent, "case=bad-json").contains("корректным JSON"));
                expect("после некорректного JSON история неизменна",
                        historyEquals(agent.getHistory(), historyBeforeErrors));

                expect("пустой content распознаётся",
                        expectAgentError(agent, "case=empty-content").contains("пустой итоговый ответ"));
                expect("после пустого content история неизменна",
                        historyEquals(agent.getHistory(), historyBeforeErrors));

                expect("отсутствие choices распознаётся",
                        expectAgentError(agent, "case=no-choices").contains("choices"));
                expect("после отсутствия choices история неизменна",
                        historyEquals(agent.getHistory(), historyBeforeErrors));
                expect("маркеры ошибочных запросов не попали в историю",
                        agent.getHistory().stream().noneMatch(m -> m.content().startsWith("case=")));

                // --- Сброс беседы ---
                agent.resetConversation();
                expect("после reset история пуста", agent.getHistory().isEmpty());
                expect("после reset пустая беседа записана в файл",
                        MAPPER.readTree(Files.readString(historyFile, StandardCharsets.UTF_8))
                                .path("messages").isEmpty());

                String questionAfterReset = "вопрос после сброса";
                agent.ask(questionAfterReset);
                JsonNode resetBody = MAPPER.readTree(lastBody.get());
                JsonNode resetMessages = resetBody.path("messages");
                expect("первый запрос после сброса содержит только system и нового user",
                        resetMessages.size() == 2
                                && "system".equals(resetMessages.get(0).path("role").asText())
                                && questionAfterReset.equals(
                                resetMessages.get(1).path("content").asText()));
                expect("старые сообщения после сброса не отправляются",
                        resetBody.toString().contains(question1) == false);
                expect("после сброса session ID изменился",
                        sessions.size() >= 3
                                && !sessions.get(sessions.size() - 1).equals(sessions.get(1)));

                // --- День 9: архив больше не обрезается ---
                for (int i = 1; i <= LlmAgent.MAX_HISTORY_TURNS + 1; i++) {
                    agent.ask("вопрос " + i);
                }
                List<ChatMessage> fullHistory = agent.getHistory();
                expect("День 9: архив не теряет старые сообщения",
                        fullHistory.size() == (1 + LlmAgent.MAX_HISTORY_TURNS + 1) * 2);
                expect("пары в истории не разорваны (чередование user/assistant)",
                        rolesAlternate(fullHistory));

                String overflowQuestion = "вопрос " + (LlmAgent.MAX_HISTORY_TURNS + 2);
                agent.ask(overflowQuestion);
                JsonNode overflowBody = MAPPER.readTree(lastBody.get());
                JsonNode overflowMessages = overflowBody.path("messages");
                expect("запрос при большой истории содержит system, весь архив и нового user",
                        overflowMessages.size() == 2
                                + (LlmAgent.MAX_HISTORY_TURNS + 2) * 2);
                expect("в запросе есть и самые старые, и самые новые пары",
                        "system".equals(overflowMessages.get(0).path("role").asText())
                                && streamContents(overflowMessages).anyMatch(
                                        m -> m.contains("вопрос после сброса"))
                                && streamContents(overflowMessages).anyMatch("вопрос 1"::equals)
                                && streamContents(overflowMessages).noneMatch("Вопрос"::equals)
                                && overflowQuestion.equals(overflowMessages.get(overflowMessages.size() - 1).path("content").asText()));

                // --- Неудачный запрос при заполненной истории ---
                List<ChatMessage> historyBeforeFailure = agent.getHistory();
                expectAgentError(agent, "case=http-500");
                expect("неудачный запрос при заполненной истории не удаляет старые пары",
                        historyEquals(agent.getHistory(), historyBeforeFailure));

                // --- Снимок getHistory ---
                List<ChatMessage> snapshot = agent.getHistory();
                boolean immutable;
                try {
                    snapshot.add(new ChatMessage("user", "взлом"));
                    immutable = false;
                } catch (UnsupportedOperationException e) {
                    immutable = true;
                }
                expect("getHistory возвращает неизменяемый снимок", immutable);
                agent.ask("вопрос для снимка");
                expect("снимок истории не изменяется при новых запросах",
                        snapshot.size() == (LlmAgent.MAX_HISTORY_TURNS + 3) * 2);

                // --- Диспетчер команд Main и интерфейс ---
                checkMainDispatch(agent, hitCounter);
            } finally {
                server.stop(0);
            }
        } finally {
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkMainDispatch(LlmAgent agent, AtomicInteger hitCounter) {
        // Начинаем с чистой беседы, чтобы ожидания были детерминированы.
        agent.resetConversation();

        // Служебные команды не вызывают API.
        int hitsBefore = hitCounter.get();
        FakeUi commandsUi = new FakeUi(
                TerminalUi.Input.command("/help"),
                TerminalUi.Input.command("/history"),
                TerminalUi.Input.command("/clear"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(commandsUi, agent, "test-model");
        expect("команды не вызывают API", hitCounter.get() == hitsBefore);
        expect("/help выводит справку", commandsUi.helpCount == 1);
        expect("/history вызывается", commandsUi.historyCalls == 1);
        expect("/clear запрашивает подтверждение предмета очистки",
                commandsUi.confirmClearCount == 1
                        && "текущего диалога".equals(commandsUi.confirmClearSubject)
                        && commandsUi.systems.stream().anyMatch(s -> s.contains("Удаление отменено")));
        expect("/exit завершает приложение", commandsUi.systems.stream()
                .anyMatch(s -> s.contains("Работа завершена")));

        // Неизвестная команда — подсказка, а не запрос к API.
        agent.resetConversation();
        hitsBefore = hitCounter.get();
        FakeUi unknownUi = new FakeUi(TerminalUi.Input.command("/foo"));
        Main.runLoop(unknownUi, agent, "test-model");
        expect("неизвестная команда выдаёт подсказку и не вызывает API",
                hitCounter.get() == hitsBefore
                        && unknownUi.systems.stream().anyMatch(s -> s.contains("Неизвестная команда")));

        // Сообщение уходит агенту ровно один раз.
        agent.resetConversation();
        hitsBefore = hitCounter.get();
        FakeUi messageUi = new FakeUi(
                TerminalUi.Input.message("Ответь одним словом: столица Франции?"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(messageUi, agent, "test-model");
        expect("MESSAGE вызывает агент ровно один раз",
                hitCounter.get() == hitsBefore + 1 && messageUi.messages.size() == 1);

        // /clear без подтверждения не трогает историю.
        agent.resetConversation();
        FakeUi clearUi = new FakeUi(
                TerminalUi.Input.message("вопрос для /clear"),
                TerminalUi.Input.command("/clear"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(clearUi, agent, "test-model");
        expect("/clear без подтверждения сохраняет историю",
                clearUi.confirmClearCount == 1
                        && !clearUi.confirmClearAnswer
                        && agent.getHistory().size() == 2);

        // /reset с подтверждением: отказ сохраняет историю.
        agent.resetConversation();
        FakeUi resetNoUi = new FakeUi(
                TerminalUi.Input.message("вопрос для /reset"),
                TerminalUi.Input.command("/reset"),
                TerminalUi.Input.command("/exit"));
        resetNoUi.confirmAnswer = false;
        Main.runLoop(resetNoUi, agent, "test-model");
        expect("/reset при отказе сохраняет историю",
                resetNoUi.confirmCount == 1 && agent.getHistory().size() == 2
                        && resetNoUi.systems.stream().anyMatch(s -> s.contains("отменён")));

        // /reset после подтверждения очищает историю.
        FakeUi resetYesUi = new FakeUi(TerminalUi.Input.command("/reset"), TerminalUi.Input.command("/exit"));
        resetYesUi.confirmAnswer = true;
        Main.runLoop(resetYesUi, agent, "test-model");
        expect("/reset после подтверждения начинает новую беседу",
                resetYesUi.confirmCount == 1 && agent.getHistory().isEmpty()
                        && resetYesUi.systems.stream().anyMatch(s -> s.contains("Начата новая беседа")));

        // /reset при пустой истории — без подтверждения.
        FakeUi resetEmptyUi = new FakeUi(TerminalUi.Input.command("/reset"), TerminalUi.Input.command("/exit"));
        Main.runLoop(resetEmptyUi, agent, "test-model");
        expect("/reset при пустой истории не запрашивает подтверждение",
                resetEmptyUi.confirmCount == 0 && resetEmptyUi.systems.stream()
                        .anyMatch(s -> s.contains("Начата новая беседа")));

        // После обычной ошибки можно продолжить чат.
        agent.resetConversation();
        hitsBefore = hitCounter.get();
        FakeUi errorUi = new FakeUi(
                TerminalUi.Input.message("case=http-500"),
                TerminalUi.Input.message("вопрос после ошибки"),
                TerminalUi.Input.command("exit"));
        Main.runLoop(errorUi, agent, "test-model");
        expect("ошибка запроса не прерывает чат",
                hitCounter.get() == hitsBefore + 2
                        && errorUi.errors.stream().anyMatch(s -> s.contains("HTTP-статус 500"))
                        && errorUi.messages.size() == 1
                        && agent.getHistory().size() == 2);

        // exit и quit совместимы с прежним поведением.
        FakeUi exitUi = new FakeUi(TerminalUi.Input.command("exit"));
        Main.runLoop(exitUi, agent, "test-model");
        FakeUi quitUi = new FakeUi(TerminalUi.Input.command("quit"));
        Main.runLoop(quitUi, agent, "test-model");
        expect("exit и quit завершают приложение",
                exitUi.systems.stream().anyMatch(s -> s.contains("Работа завершена"))
                        && quitUi.systems.stream().anyMatch(s -> s.contains("Работа завершена")));

        // EOF корректно завершает цикл.
        FakeUi eofUi = new FakeUi();
        Main.runLoop(eofUi, agent, "test-model");
        expect("EOF завершает приложение", eofUi.systems.stream()
                .anyMatch(s -> s.contains("Работа завершена")));
    }

     static void checkRequestParameters() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // Профиль fast с явной temperature.
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_RESPONSE_MODE", "fast");
            env.put("LLM_TEMPERATURE", "0.3");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store,
                    tempMemoryStore());
            agent.ask("вопрос для проверки параметров");
            JsonNode body = MAPPER.readTree(lastBody.get());
            expect("в запросе отправляется max_tokens профиля fast (1024)",
                    body.path("max_tokens").asInt(-1) == 1024);
            expect("temperature включается при явном переопределении",
                    body.has("temperature") && body.path("temperature").asDouble(-1) == 0.3);
            expect("model и messages не изменены",
                    "glm-5.3-flash".equals(body.path("model").asText())
                            && body.path("messages").size() == 2);
            java.util.Set<String> fieldNames = new java.util.HashSet<>();
            body.fieldNames().forEachRemaining(fieldNames::add);
            java.util.Set<String> unsupported = java.util.Set.of(
                    "reasoning_effort", "enable_thinking", "thinking",
                    "max_completion_tokens", "top_p");
            expect("в запросе нет неподтверждённых провайдерских параметров",
                    fieldNames.stream().noneMatch(unsupported::contains));
            expect("для fast системная инструкция дополняется предпочтением краткости",
                    body.path("messages").get(0).path("content").asText()
                            .equals(LlmAgent.systemPromptFor(ModelSettings.FAST))
                            && body.path("messages").get(0).path("content").asText()
                            .contains("кратко и по существу"));
            store.close();

            // Базовый профиль balanced: temperature не отправляется.
            JsonConversationStore store2 = tempStore();
            LlmAgent balancedAgent = new LlmAgent(config, ModelSettings.defaults(), client,
                    store2, tempMemoryStore());
            balancedAgent.ask("вопрос для проверки базовых параметров");
            JsonNode balancedBody = MAPPER.readTree(lastBody.get());
            expect("в базовом профиле max_tokens равен 2048",
                    balancedBody.path("max_tokens").asInt(-1) == 2048);
            expect("без явного переопределения temperature не отправляется",
                    !balancedBody.has("temperature"));
            expect("в базовом профиле системная инструкция без изменений",
                    balancedBody.path("messages").get(0).path("content").asText()
                            .equals(SYSTEM_PROMPT_TEXT));
            store2.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkContextLimit() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_CONTEXT_MAX_TURNS", "2");
            env.put("LLM_MAX_OUTPUT_TOKENS", "128");
            // Полный архив отправляется в стратегии веток.
            env.put("LLM_CONTEXT_STRATEGY", "branching");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);
            Path historyFile = store.file();

            for (int i = 1; i <= 4; i++) {
                agent.ask("вопрос " + i);
            }
            agent.ask("вопрос с урезанным контекстом");

            JsonNode messages = MAPPER.readTree(lastBody.get()).path("messages");
            // День 9: LLM_CONTEXT_MAX_TURNS в режимах контекста не применяется —
            // full действительно отправляет весь архив: system + 4 пары + новый запрос.
            expect("LLM_CONTEXT_MAX_TURNS не применяется: full отправляет весь архив",
                    messages.size() == 10
                            && "system".equals(messages.get(0).path("role").asText())
                            && "вопрос 1".equals(messages.get(1).path("content").asText())
                            && "вопрос 4".equals(messages.get(7).path("content").asText())
                            && "вопрос с урезанным контекстом".equals(
                            messages.get(9).path("content").asText()));

            JsonNode saved = MAPPER.readTree(Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("лимит отправки не удаляет архив: в файле все пары",
                    saved.path("messages").size() == 10);

            List<ChatMessage> memoryBeforeRestart = agent.getHistory();
            store.close();
            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                LlmAgent restored = new LlmAgent(config, ModelSettings.from(env), client, reopened);
                expect("после перезапуска история восстанавливается целиком",
                        restored.getHistory().equals(memoryBeforeRestart));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkErrorClassification() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains("case=ctx-overflow")) {
                return new Response(400, ("{\"error\":{\"code\":\"context_length_exceeded\","
                        + "\"message\":\"This model's maximum context length is 4096 tokens\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=other-400")) {
                return new Response(400, ("{\"error\":{\"code\":\"invalid_request_error\","
                        + "\"message\":\"Invalid parameter: temperature\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), client, store);
            agent.setTokenCounter(LEN_COUNTER);
            agent.ask("вопрос перед ошибками");
            List<ChatMessage> before = agent.getHistory();
            long attemptsBefore = agent.sessionStats().apiAttempts();
            long unknownBefore = agent.sessionStats().requestsWithoutUsage();

            String overflowMessage = expectAgentError(agent, "case=ctx-overflow");
            expect("отказ из-за контекста распознаётся по стандартной структуре ошибки",
                    overflowMessage.contains("размера контекста")
                            && overflowMessage.contains("HTTP-статус 400"));
            expect("отказ по контексту не портит историю", agent.getHistory().equals(before));

            String other400 = expectAgentError(agent, "case=other-400");
            expect("прочий HTTP 400 не называется переполнением контекста",
                    other400.contains("HTTP-статус 400") && !other400.contains("контекста"));
            expect("после ошибок история не изменена", agent.getHistory().equals(before));
            expect("неудачные запросы засчитаны как попытки с неизвестным расходом",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 2
                            && agent.sessionStats().requestsWithoutUsage() == unknownBefore + 2
                            && !agent.sessionStats().complete());
            store.close();

            // Таймаут — отдельный тип ошибки; расход неизвестен.
            Path keyStore2 = createSelfSignedKeyStore();
            HttpsServer slowServer = startHttpsServer(keyStore2, (requestBody, session, auth) -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
            });
            try {
                Config slowConfig = new Config("test-key",
                        "https://127.0.0.1:" + slowServer.getAddress().getPort()
                                + "/v1/chat/completions",
                        "glm-5.3-flash");
                Map<String, String> env = new java.util.HashMap<>();
                env.put("LLM_REQUEST_TIMEOUT_SECONDS", "1");
                JsonConversationStore slowStore = tempStore();
                LlmAgent slowAgent = new LlmAgent(slowConfig, ModelSettings.from(env),
                        trustedHttpClient(keyStore2), slowStore);
                String timeoutMessage = expectAgentError(slowAgent, "вопрос с таймаутом");
                expect("таймаут распознаётся как таймаут", timeoutMessage.contains("таймаут"));
                expect("после таймаута история не изменилась", slowAgent.getHistory().isEmpty());
                expect("после таймаута попытка засчитана, расход неизвестен",
                        slowAgent.sessionStats().apiAttempts() == 1
                                && slowAgent.sessionStats().requestsWithoutUsage() == 1);
                slowStore.close();
            } finally {
                slowServer.stop(0);
                Files.deleteIfExists(keyStore2);
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkLocalOllamaConfig() throws Exception {
        // Конфиг Ollama: открытый HTTP на loopback допускается только для localhost;
        // ключ не требуется, но включается (заглушка) и уходит как обычный Bearer.
        expect("Ollama http://localhost:11434/v1 принимается",
                new Config("ollama", "http://localhost:11434/v1", "qwen2.5:3b") != null);
        expect("127.0.0.1 на HTTP допускается для локальной LLM",
                new Config("ollama", "http://127.0.0.1:11434/v1", "qwen2.5:3b") != null);
        expect("непринимаемый HTTP-хост без HTTPS отклоняется",
                expectConfigError("test-key", "http://example.com/v1/chat/completions",
                        "glm-5.3-flash").contains("HTTPS"));
        expect("пустой ключ Ollama не принят без заглушки",
                expectConfigError(null, "http://localhost:11434/v1", "qwen2.5:3b")
                        .contains("LLM_API_KEY"));

        // Диалог через plain HTTP loopback: ответ модели и модель в теле запроса.
        int[] portHolder = startPlainEphemeralServer();
        try {
            Config config = new Config("ollama",
                    "http://localhost:" + portHolder[0] + "/v1/chat/completions",
                    "qwen2.5:3b");
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_REQUEST_TIMEOUT_SECONDS", "300");
            JsonConversationStore store = tempStore();
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30)).build();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), httpClient,
                    store, tempMemoryStore());
            String answer = agent.ask("Скажи одно слово: привет");
            expect("Ollama-конфиг даёт ответ через plain HTTP loopback",
                    "Привет".equals(answer));
            expect("в запросе уходит configured модель qwen2.5:3b",
                    agent.modelName().equals("qwen2.5:3b"));
            expect("таймаут из LLM_REQUEST_TIMEOUT_SECONDS применён в настройках",
                    agent.currentSettings().requestTimeoutSeconds() == 300);

            // Значения по умолчанию (без env) прежние: HTTPS требуется, таймаут 180.
            expect("режим по умолчанию: таймаут 180 с без настройки",
                    ModelSettings.defaults().requestTimeoutSeconds() == 180);
            expect("режим по умолчанию не поддерживает удалённый HTTP",
                    expectConfigError("test-key",
                            "http://cloud.example.com:8080/v1/chat/completions",
                            "glm-5.3-flash")
                            .contains("HTTPS"));
            store.close();
        } finally {
            stopPlainEphemeralServer();
        }
    }

    /** Общий ephemeral plain-HTTP-сервер для проверки локальной Ollama-конфигурации. */
    private static int[] lastPlainPort;
    private static com.sun.net.httpserver.HttpServer plainLoopbackServer;

    private static int[] startPlainEphemeralServer() throws Exception {
        plainLoopbackServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        // Эмуляция проверки доступности Ollama: /api/tags отвечает списком моделей.
        plainLoopbackServer.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[{\"name\":\"qwen2.5:3b\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        plainLoopbackServer.createContext("/v1/chat/completions", exchange -> {
            String requestBody =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // Минимальный OpenAI-совместимый ответ, как у Ollama /v1/chat/completions.
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Привет\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,"
                    + "\"total_tokens\":12},\"model\":\"qwen2.5:3b\"}")
                    .getBytes(StandardCharsets.UTF_8);
            if (!requestBody.contains("qwen2.5:3b")) {
                body = "{\"error\":{\"message\":\"model mismatch\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        plainLoopbackServer.start();
        lastPlainPort = new int[]{plainLoopbackServer.getAddress().getPort()};
        return lastPlainPort;
    }

    private static void stopPlainEphemeralServer() {
        if (plainLoopbackServer != null) {
            plainLoopbackServer.stop(0);
            plainLoopbackServer = null;
        }
    }

     static void checkModelProfileSwitch() throws Exception {
        // Профиль ollama по умолчанию (без env): URL, модель, ключ-заглушка, таймаут.
        ModelProfiles.Profile ollamaDefaults =
                ModelProfiles.ollama(java.util.Map.of());
        expect("профиль ollama по умолчанию: URL, модель, ключ, таймаут",
                "ollama".equals(ollamaDefaults.name())
                        && "http://localhost:11434/v1/chat/completions"
                        .equals(ollamaDefaults.apiUrl())
                        && "qwen2.5:3b".equals(ollamaDefaults.model())
                        && "ollama".equals(ollamaDefaults.apiKey())
                        && ollamaDefaults.requestTimeoutSeconds() == 300);

        // Проверка доступности: закрытый порт — недоступна, живой /api/tags — доступна.
        ModelProfiles.Profile closedOllama = new ModelProfiles.Profile("ollama",
                "http://127.0.0.1:1/v1/chat/completions", "qwen2.5:3b", "ollama", 300);
        expect("недоступная ollama (закрытый порт) распознаётся",
                !ModelProfiles.reachable(closedOllama));

        // Переключение на живом агенте: URL/модель/таймаут меняются, ключ не печатается.
        int[] portHolder = startPlainEphemeralServer();
        try {
            Config cloudConfig = new Config("secret-test-key",
                    "https://always-cloud.example.com/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            ModelSettings settings = ModelSettings.defaults();
            LlmAgent agent = new LlmAgent(cloudConfig, settings,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                    store, tempMemoryStore());
            expect("профиль по умолчанию — cloud", ModelProfiles.CLOUD
                    .equals(agent.currentProfileName()));
            expect("конфигурация cloud не изменилась до переключения",
                    agent.modelName().equals("glm-5.3-flash")
                            && agent.providerApiUrl()
                            .equals("https://always-cloud.example.com/v1/chat/completions")
                            && agent.currentSettings().requestTimeoutSeconds() == 180);

            // Один обмен на cloud (локально, без API), затем переключение и
            // второй обмен на ollama-конфиг: история и состояние диалога
            // непрерывны.
            String question1 = "первый вопрос";
            String answer1 = agent.recordLocalAnswer(question1, "Привет");
            expect("пара на cloud записана локально", "Привет".equals(answer1));
            List<ChatMessage> historyAfterCloud = agent.getHistory();
            DialogTaskState stateAfterCloud = agent.dialogState();

            ModelProfiles.Profile ollamaLive = new ModelProfiles.Profile("ollama",
                    "http://127.0.0.1:" + portHolder[0] + "/v1/chat/completions",
                    "qwen2.5:3b", "ollama", 300);
            agent.switchToProfile(ollamaLive);
            expect("после переключения обновлены профиль, модель и таймаут",
                    "ollama".equals(agent.currentProfileName())
                            && "qwen2.5:3b".equals(agent.modelName())
                            && agent.providerApiUrl()
                            .contains(String.valueOf(portHolder[0]))
                            && agent.currentSettings().requestTimeoutSeconds() == 300);
            expect("история сохранилась при переключении профиля",
                    agent.getHistory().equals(historyAfterCloud));
            expect("состояние диалога сохранилось при переключении профиля",
                    agent.dialogState().equals(stateAfterCloud));

            String answer2 = agent.ask("второй вопрос");
            expect("второй обмен прошёл через ollama-конфигурацию",
                    "Привет".equals(answer2));

            // http на внешнем хосте отклонён Config-проверкой; состояние не меняется.
            String modelBeforeRejection = agent.modelName();
            String nameBeforeRejection = agent.currentProfileName();
            boolean rejectedRemoteHttp;
            try {
                agent.switchToProfile(new ModelProfiles.Profile("ollama",
                        "http://example.com/v1/chat/completions", "other-model",
                        "ollama", 300));
                rejectedRemoteHttp = false;
            } catch (AgentException e) {
                rejectedRemoteHttp = e.getMessage().contains("HTTPS");
            }
            expect("удалённый http-профиль отклонён, состояние не изменилось",
                    rejectedRemoteHttp
                            && agent.modelName().equals(modelBeforeRejection)
                            && agent.currentProfileName().equals(nameBeforeRejection));

            // Неизвестное имя профиля — ошибка со списком доступных.
            boolean unknownReported;
            try {
                ModelProfiles.resolve("gpt", agent.cloudProfile());
                unknownReported = false;
            } catch (AgentException e) {
                unknownReported = e.getMessage().contains("Неизвестный профиль")
                        && e.getMessage().contains("cloud, ollama");
            }
            expect("неизвестное имя профиля даёт ошибку со списком", unknownReported);

            // Возврат на cloud: прежние URL, модель и таймаут.
            agent.switchToProfile(agent.cloudProfile());
            expect("возврат на cloud восстанавливает конфигурацию и таймаут",
                    ModelProfiles.CLOUD.equals(agent.currentProfileName())
                            && "glm-5.3-flash".equals(agent.modelName())
                            && agent.providerApiUrl()
                            .equals("https://always-cloud.example.com/v1/chat/completions")
                            && agent.currentSettings().requestTimeoutSeconds() == 180);
            store.close();
        } finally {
            stopPlainEphemeralServer();
        }
    }
}
