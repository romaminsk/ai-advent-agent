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

final class ContextChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkDiagnosticsAndLimit();
        checkContextBudget();
        checkTokensStatsCommandsNoApi();
        checkTokenCounterHeuristics();
        checkTokenEstimations();
        checkSessionUsageAccounting();
        checkSessionCost();
        checkSessionTokenLimitFeature();
        checkDay9SettingsValidation();
        checkDay9SummaryThresholds();
        checkDay9IncrementalSummary();
        checkDay9PersistenceAndStale();
        checkDay9FailureKeepsHistory();
        checkDay9ClearRemovesSummary();
        checkDay9ContextCommandsNoApi();
        checkDay9Compare();
        checkDay9CompareGuards();
        checkDay9CompareReadySummary();
        checkDay9CompareAfterRefresh();
        checkDay9CompareReuseAfterRestart();
        checkDay9ComparePrepFailure();
        checkDay9CompareUsageGaps();
        checkDay9ContextCaptions();
        checkDay9ArchiveNoLossAcrossModes();
        ContextStrategyChecks.run();
    }

     static void checkDiagnosticsAndLimit() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        AtomicInteger successCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            if (requestBody.contains("case=truncated")) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"length\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"частичный ответ\"}}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=empty-length")) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"length\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=no-usage")) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ без usage\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                    + "{\"role\":\"assistant\",\"content\":\"Ответ "
                    + successCounter.incrementAndGet() + "\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_DIAGNOSTICS", "true");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);

            // Обычный ответ с usage: диагностика без секретов и текстов переписки.
            FakeUi normalUi = new FakeUi(
                    TerminalUi.Input.message("вопрос-секрет-42"),
                    TerminalUi.Input.command("/exit"));
            expect("запрос с диагностикой завершается нормально",
                    Main.runLoop(normalUi, agent, "glm-5.3-flash") == 0);
            String normalText = String.join("\n", normalUi.systems);
            expect("диагностика показывает профиль, лимит и метрики времени",
                    normalText.contains("профиль balanced")
                            && normalText.contains("лимит генерации 2048")
                            && normalText.contains("Диагностика")
                            && normalText.contains("HTTP до полного ответа"));
            expect("диагностика показывает finish_reason и usage из ответа",
                    normalText.contains("finish_reason: stop")
                            && normalText.contains("prompt_tokens: 10")
                            && normalText.contains("completion_tokens: 20")
                            && normalText.contains("total_tokens: 30"));
            expect("диагностика не содержит ключ API и тексты переписки",
                    !normalText.contains("test-key") && !normalText.contains("секрет-42"));

            // Обрезанный по лимиту ответ: показан, с предупреждением, без повторов.
            FakeUi truncatedUi = new FakeUi(
                    TerminalUi.Input.message("case=truncated"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(truncatedUi, agent, "glm-5.3-flash");
            expect("обрезанный ответ показывается пользователю",
                    truncatedUi.messages.contains("частичный ответ"));
            expect("при остановке по лимиту выводится предупреждение",
                    truncatedUi.systems.stream().anyMatch(s -> s.contains("обрезан по лимиту")));
            expect("при обрезанном ответе повторный запрос не выполняется",
                    hitCounter.get() == 2);
            expect("последние метрики фиксируют finish_reason length",
                    agent.getLastDiagnostics() != null && agent.getLastDiagnostics().limitReached());

            // Пустой итоговый ответ при лимите — на отдельном агенте с чистой
            // историей, чтобы маркер предыдущего сценария не попадал в запрос.
            JsonConversationStore emptyStore = tempStore();
            LlmAgent emptyAgent = new LlmAgent(config, ModelSettings.from(env), client, emptyStore);
            expect("пустой ответ при исчерпанном лимите объясняется",
                    expectAgentError(emptyAgent, "case=empty-length").contains("max_tokens"));
            expect("после пустого ответа история в памяти не изменилась",
                    emptyAgent.getHistory().isEmpty());
            expect("после пустого ответа повторный запрос не выполнялся",
                    hitCounter.get() == 3);
            emptyStore.close();

            // Отсутствующий usage — «нет данных», а не ноль (чистая история,
            // чтобы маркер предыдущего сценария не попал в запрос).
            JsonConversationStore noUsageStore = tempStore();
            LlmAgent noUsageAgent = new LlmAgent(config, ModelSettings.from(env), client, noUsageStore);
            FakeUi noUsageUi = new FakeUi(
                    TerminalUi.Input.message("case=no-usage"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(noUsageUi, noUsageAgent, "glm-5.3-flash");
            expect("при отсутствующем usage диагностика показывает «нет данных»",
                    noUsageUi.systems.stream().anyMatch(s -> s.contains("usage: нет данных")));
            noUsageStore.close();
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkTokenCounterHeuristics() {
        HeuristicTokenCounter counter = HeuristicTokenCounter.INSTANCE;
        expect("пустой текст оценивается нулём", counter.count("") == 0 && counter.count(null) == 0);
        expect("русский текст даёт ненулевую оценку", counter.count("Привет, как дела?") > 0);
        expect("кириллица оценивается дороже латиницы той же длины",
                counter.count("абвгдеёжзи") > counter.count("abcdefghij"));
        expect("код даёт ненулевую оценку", counter.count("if (a < b) { return null; }") > 0);
        expect("emoji оценивается примерно в два токена",
                counter.count("👍") == 2 && counter.count("😀😀") == 4);
        expect("CJK — около токена на иероглиф", counter.count("你好世界") == 4);
        expect("эвристика помечается как оценка, а не точный подсчёт",
                !counter.exact() && counter.description().contains("оценк"));
        expect("накладные расходы сообщения и тела считаются отдельно",
                counter.countOverhead(2) == TokenCounter.REQUEST_OVERHEAD_TOKENS
                        + 2 * TokenCounter.MESSAGE_OVERHEAD_TOKENS);
        int messages = counter.countMessages(List.of(
                new ChatMessage("system", "абв"), new ChatMessage("user", "def")));
        expect("оценка списка сообщений = тексты + накладные всех сообщений и тела",
                messages == counter.count("абв") + counter.count("def")
                        + 2 * TokenCounter.MESSAGE_OVERHEAD_TOKENS
                        + TokenCounter.REQUEST_OVERHEAD_TOKENS);
    }

     static void checkTokenEstimations() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}],"
                        + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":7,\"total_tokens\":49}}")
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_CONTEXT_MAX_TURNS", "1");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);
            agent.setTokenCounter(LEN_COUNTER);

            String question1 = "первый вопрос";
            agent.ask(question1);
            RequestDiagnostics d1 = agent.getLastDiagnostics();
            expect("оценка нового сообщения совпадает с детерминированным счётчиком",
                    d1.estimatedUserMessageTokens() == LEN_COUNTER.count(question1));
            List<ChatMessage> expectedOutgoing1 = List.of(
                    new ChatMessage("system", LlmAgent.systemPromptFor(ModelSettings.BALANCED)),
                    new ChatMessage("user", question1));
            expect("оценка отправленного запроса включает system, сообщение и накладные",
                    d1.estimatedRequestTokens() == LEN_COUNTER.countMessages(expectedOutgoing1));
            expect("оценка накладных расходов указана отдельно",
                    d1.estimatedRequestOverheadTokens() == LEN_COUNTER.countOverhead(2));
            expect("фактические токены API берутся из usage, а не из оценки",
                    d1.promptTokens() == 42 && d1.completionTokens() == 7);
            expect("оценка видимого ответа считается локально",
                    d1.estimatedAnswerTokens() == LEN_COUNTER.count("Ответ 1"));
            List<ChatMessage> historyAfterFirst = List.of(
                    new ChatMessage("user", question1),
                    new ChatMessage("assistant", "Ответ 1"));
            expect("оценка полной истории после ответа и число сообщений совпадают",
                    d1.estimatedHistoryTokensAfter() == LEN_COUNTER.countMessages(historyAfterFirst)
                            && d1.savedMessagesAfter() == 2);

            String question2 = "второй вопрос";
            agent.ask(question2);
            RequestDiagnostics d2 = agent.getLastDiagnostics();
            // День 9: LLM_CONTEXT_MAX_TURNS больше не ограничивает —
            // контекст включает system + все пары + новое сообщение.
            List<ChatMessage> expectedOutgoing2 = List.of(
                    new ChatMessage("system", LlmAgent.systemPromptFor(ModelSettings.BALANCED)),
                    new ChatMessage("user", question1),
                    new ChatMessage("assistant", "Ответ 1"),
                    new ChatMessage("user", question2));
            expect("оценка окончательного messages считается после ограничения истории",
                    d2.estimatedRequestTokens() == LEN_COUNTER.countMessages(expectedOutgoing2));
            expect("полная сохранённая история считается отдельно от отправляемого контекста",
                    d2.estimatedHistoryTokensAfter() == LEN_COUNTER.countMessages(agent.getHistory())
                            && agent.estimateNextContextTokens()
                            == LEN_COUNTER.countMessages(List.of(
                                    new ChatMessage("system",
                                            LlmAgent.systemPromptFor(ModelSettings.BALANCED)),
                                    new ChatMessage("user", question1),
                                    new ChatMessage("assistant", "Ответ 1"),
                                    new ChatMessage("user", question2),
                                    new ChatMessage("assistant", "Ответ 2"))));

            // День 9: архив и отправляемый контекст растут вместе (нет ограничения
            // отправки для контекстного запроса).
            int historyBeforeThird = agent.estimateHistoryTokens();
            int contextBeforeThird = agent.estimateNextContextTokens();
            agent.ask(question2);
            expect("архив и отправляемый контекст согласованно растут",
                    agent.estimateHistoryTokens() > historyBeforeThird
                            && agent.estimateNextContextTokens() > contextBeforeThird);

            int contextBalanced = agent.estimateNextContextTokens();
            agent.setProfile(ModelSettings.FAST);
            expect("изменение профиля отражается в оценке system-инструкции",
                    agent.estimateNextContextTokens() > contextBalanced);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkSessionUsageAccounting() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains("case=partial-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ P\"}}],\"usage\":{\"prompt_tokens\":7}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=no-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ U\"}}]}").getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=empty-with-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=big-completion")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":500}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ ОК\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20,\"total_tokens\":30}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // Полный usage: ровно один учёт на запрос, total_tokens повторно не суммируется.
            JsonConversationStore fullStore = tempStore();
            LlmAgent fullAgent = new LlmAgent(config, ModelSettings.defaults(), client, fullStore);
            fullAgent.ask("вопрос с полным usage");
            SessionTokenStats.Snapshot full = fullAgent.sessionStats();
            expect("полный usage учитывается один раз: 10/20, total_tokens не дублируется",
                    full.apiAttempts() == 1 && full.requestsWithUsage() == 1
                            && full.totalPromptTokens() == 10 && full.totalCompletionTokens() == 20
                            && full.complete());
            fullStore.close();

            // Частичный usage: отсутствующее поле — «нет данных», а не 0.
            JsonConversationStore partialStore = tempStore();
            LlmAgent partialAgent = new LlmAgent(config, ModelSettings.defaults(), client, partialStore);
            partialAgent.ask("case=partial-usage");
            SessionTokenStats.Snapshot partial = partialAgent.sessionStats();
            expect("частичный usage: вход учтён, итог помечен неполным",
                    partial.apiAttempts() == 1 && partial.requestsWithUsage() == 1
                            && partial.requestsWithPartialUsage() == 1
                            && partial.totalPromptTokens() == 7
                            && partial.totalCompletionTokens() == 0
                            && !partial.complete());
            expect("частичный usage в диагностике — null, а не 0",
                    partialAgent.getLastDiagnostics() != null
                            && partialAgent.getLastDiagnostics().completionTokens() == null);
            partialStore.close();

            // Отсутствие usage: расход неизвестен, итог неполный, «нет данных».
            JsonConversationStore noUsageStore = tempStore();
            LlmAgent noUsageAgent = new LlmAgent(config, ModelSettings.defaults(), client, noUsageStore);
            noUsageAgent.ask("case=no-usage");
            SessionTokenStats.Snapshot noUsage = noUsageAgent.sessionStats();
            expect("запрос без usage: попытка засчитана, расход неизвестен",
                    noUsage.apiAttempts() == 1 && noUsage.requestsWithUsage() == 0
                            && noUsage.requestsWithoutUsage() == 1 && !noUsage.complete());
            String diagNoUsage = Main.formatDiagnostics(noUsageAgent.getLastDiagnostics(),
                    "glm-5.3-flash", noUsage, noUsageAgent.currentSettings());
            expect("диагностика без usage показывает «нет данных», а не ноль",
                    diagNoUsage.contains("usage: нет данных")
                            && diagNoUsage.contains("вход нет данных"));
            noUsageStore.close();

            // Пустой ответ с usage: расход учитывается, история не меняется.
            JsonConversationStore emptyStore = tempStore();
            LlmAgent emptyAgent = new LlmAgent(config, ModelSettings.defaults(), client, emptyStore);
            expect("пустой ответ распознаётся",
                    expectAgentError(emptyAgent, "case=empty-with-usage")
                            .contains("пустой итоговый ответ"));
            SessionTokenStats.Snapshot empty = emptyAgent.sessionStats();
            expect("usage при пустом ответе учитывается в расходе сессии",
                    empty.apiAttempts() == 1 && empty.requestsWithUsage() == 1
                            && empty.totalPromptTokens() == 11
                            && empty.totalCompletionTokens() == 22);
            expect("после пустого ответа история не изменилась", emptyAgent.getHistory().isEmpty());
            emptyStore.close();

            // completion_tokens (API) != оценка видимого ответа (локальная).
            JsonConversationStore bigStore = tempStore();
            LlmAgent bigAgent = new LlmAgent(config, ModelSettings.defaults(), client, bigStore);
            bigAgent.setTokenCounter(LEN_COUNTER);
            bigAgent.ask("case=big-completion");
            RequestDiagnostics bigDiag = bigAgent.getLastDiagnostics();
            expect("фактический completion_tokens и оценка видимого ответа разделены",
                    bigDiag.completionTokens() == 500
                            && bigDiag.estimatedAnswerTokens() == LEN_COUNTER.count("Ок")
                            && bigDiag.estimatedAnswerTokens() != 500);
            String bigDiagText = Main.formatDiagnostics(bigDiag, "glm-5.3-flash",
                    bigAgent.sessionStats(), bigAgent.currentSettings());
            expect("в диагностике обе величины показаны раздельно",
                    bigDiagText.contains("completion_tokens: 500")
                            && bigDiagText.contains("видимый ответ ≈" + LEN_COUNTER.count("Ок")));
            bigStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkSessionCost() throws Exception {
        // Арифметика и форматирование на BigDecimal.
        expect("тариф 0.60 за 1M при 1 500 000 токенов даёт 0.900000",
                TokenCost.perMillion(new BigDecimal("0.60"), 1_500_000L)
                        .compareTo(new BigDecimal("0.900000")) == 0);
        expect("формат суммы не зависит от локали",
                TokenCost.formatUsd(new BigDecimal("0.900000")).equals("$0.900000"));

        // Разбор тарифов из окружения.
        Map<String, String> env = new java.util.HashMap<>();
        expect("без переменных тарифа стоимость «нет данных» (отсутствие не равно 0)",
                ModelSettings.from(env).inputPricePer1M() == null
                        && ModelSettings.from(env).outputPricePer1M() == null);
        env.put("LLM_INPUT_PRICE_PER_1M", "0");
        expect("явный нулевой тариф допустим",
                ModelSettings.from(env).inputPricePer1M().signum() == 0);
        env.put("LLM_INPUT_PRICE_PER_1M", "0.75");
        expect("дробный тариф читается",
                ModelSettings.from(env).inputPricePer1M().compareTo(new BigDecimal("0.75")) == 0);
        expectSettingsError(env, "LLM_INPUT_PRICE_PER_1M", "-1");
        expectSettingsError(env, "LLM_INPUT_PRICE_PER_1M", "abc");
        expectSettingsError(env, "LLM_OUTPUT_PRICE_PER_1M", "1,5");
        env.put("LLM_OUTPUT_PRICE_PER_1M", "2.2");

        // /stats с тарифом и usage: расчётная стоимость с пометкой «расчёт».
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":1000000,"
                        + "\"completion_tokens\":2000000}}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);
            FakeUi costUi = new FakeUi(
                    TerminalUi.Input.message("вопрос для стоимости"),
                    TerminalUi.Input.command("/stats"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(costUi, agent, "glm-5.3-flash");
            // 1M вход × 0.75 = 0.75; 2M выход × 2.2 = 4.4; итог 5.150000.
            expect("расчётная стоимость показана с тарифом и пометкой «расчёт»",
                    costUi.systems.stream().anyMatch(s -> s.contains("$5.150000")
                            && s.contains("расчёт") && s.contains("USD за 1 000 000")));

            // Одна из двух переменных — расчёт стоимости не выполняется.
            JsonConversationStore halfStore = tempStore();
            LlmAgent halfAgent = new LlmAgent(config, ModelSettings.from(env), client, halfStore);
            halfAgent.ask("вопрос при полном тарифе");
            Map<String, String> halfEnv = new java.util.HashMap<>(env);
            halfEnv.remove("LLM_OUTPUT_PRICE_PER_1M");
            JsonConversationStore halfOnlyStore = tempStore();
            LlmAgent halfOnlyAgent = new LlmAgent(config, ModelSettings.from(halfEnv), client,
                    halfOnlyStore);
            halfOnlyAgent.ask("вопрос при половинном тарифе");
            expect("один тариф без второго не даёт расчёт стоимости",
                    Main.formatStats(halfOnlyAgent).contains("только одна из переменных"));
            halfOnlyStore.close();

            // Без тарифов — «нет данных», а не ноль.
            JsonConversationStore plainStore = tempStore();
            LlmAgent plainAgent = new LlmAgent(config, ModelSettings.defaults(), client, plainStore);
            plainAgent.ask("вопрос без тарифа");
            expect("без тарифа стоимость «нет данных»",
                    Main.formatStats(plainAgent)
                            .contains("Стоимость: нет данных — тариф не настроен"));
            plainStore.close();

            // С тарифом, но без запросов — тоже «нет данных».
            JsonConversationStore freshStore = tempStore();
            LlmAgent freshAgent = new LlmAgent(config, ModelSettings.from(env), client, freshStore);
            expect("с тарифом, но без запросов с usage стоимость «нет данных»",
                    Main.formatStats(freshAgent)
                            .contains("нет данных — не было запросов с usage"));
            freshStore.close();
            halfStore.close();
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkContextBudget() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // Разбор политики и окна из окружения.
            Map<String, String> env = new java.util.HashMap<>();
            expect("политика по умолчанию — warn",
                    ModelSettings.from(env).overflowPolicy() == ContextOverflowPolicy.WARN);
            env.put("LLM_CONTEXT_OVERFLOW_POLICY", " block ");
            expect("политика block читается без учёта регистра и пробелов",
                    ModelSettings.from(env).overflowPolicy() == ContextOverflowPolicy.BLOCK);
            env.put("LLM_CONTEXT_WINDOW_TOKENS", "8192");
            expect("контекстное окно читается",
                    ModelSettings.from(env).contextWindowTokens() == 8192);
            expectSettingsError(env, "LLM_CONTEXT_WINDOW_TOKENS", "0");
            expectSettingsError(env, "LLM_CONTEXT_OVERFLOW_POLICY", "nope");

            env.put("LLM_MAX_OUTPUT_TOKENS", "64");
            env.remove("LLM_CONTEXT_WINDOW_TOKENS");

            // Прогноз считается на «зондовом» агенте без окна.
            JsonConversationStore probeStore = tempStore();
            LlmAgent probeAgent = new LlmAgent(config, ModelSettings.from(env), client, probeStore);
            probeAgent.setTokenCounter(LEN_COUNTER);
            String message = "вопрос бюджета";
            int projected = agentProjected(probeAgent, message);
            probeStore.close();

            // Граница: оценка ровно на уровне бюджета — блокировки нет.
            Map<String, String> edgeEnv = new java.util.HashMap<>(env);
            edgeEnv.put("LLM_CONTEXT_WINDOW_TOKENS", String.valueOf(projected));
            edgeEnv.put("LLM_CONTEXT_OVERFLOW_POLICY", "block");
            JsonConversationStore edgeStore = tempStore();
            LlmAgent edgeAgent = new LlmAgent(config, ModelSettings.from(edgeEnv), client, edgeStore);
            edgeAgent.setTokenCounter(LEN_COUNTER);
            expect("оценка на границе бюджета не блокируется",
                    "Ок".equals(edgeAgent.ask(message)));
            edgeStore.close();

            // block: прогнозируемое превышение — без HTTP, без изменения истории.
            Map<String, String> blockEnv = new java.util.HashMap<>(env);
            blockEnv.put("LLM_CONTEXT_WINDOW_TOKENS", String.valueOf(projected - 1));
            blockEnv.put("LLM_CONTEXT_OVERFLOW_POLICY", "block");
            JsonConversationStore blockStore = tempStore();
            LlmAgent blockAgent = new LlmAgent(config, ModelSettings.from(blockEnv), client, blockStore);
            blockAgent.setTokenCounter(LEN_COUNTER);
            int hitsBefore = hitCounter.get();
            long attemptsBefore = blockAgent.sessionStats().apiAttempts();
            List<ChatMessage> historyBeforeBlock = blockAgent.getHistory();
            String blockedMessage = expectAgentError(blockAgent, message);
            expect("превышение бюджета при block даёт локальную блокировку",
                    blockedMessage.contains("заблокирован локально")
                            && blockedMessage.contains("локальная оценка"));
            expect("при локальной блокировке HTTP не выполнялся",
                    hitCounter.get() == hitsBefore);
            expect("при локальной блокировке история не изменена",
                    blockAgent.getHistory().equals(historyBeforeBlock));
            expect("при локальной блокировке попытка к API не засчитана",
                    blockAgent.sessionStats().apiAttempts() == attemptsBefore);
            expect("при block предупреждение перед отправкой не выдаётся",
                    blockAgent.predictContextBudgetWarning(message) == null);

            // warn: предупреждение с пометкой «оценка», прежнее поведение отправки.
            Map<String, String> warnEnv = new java.util.HashMap<>(env);
            warnEnv.put("LLM_CONTEXT_WINDOW_TOKENS", String.valueOf(projected - 1));
            warnEnv.put("LLM_CONTEXT_OVERFLOW_POLICY", "warn");
            JsonConversationStore warnStore = tempStore();
            LlmAgent warnAgent = new LlmAgent(config, ModelSettings.from(warnEnv), client, warnStore);
            warnAgent.setTokenCounter(LEN_COUNTER);
            String warning = warnAgent.predictContextBudgetWarning(message);
            expect("при warn выдаётся предупреждение с пометкой «оценка»",
                    warning != null && warning.contains("оценка"));
            expect("при warn запрос всё равно отправляется",
                    "Ок".equals(warnAgent.ask(message)) && hitCounter.get() == hitsBefore + 1);
            expect("диагностика отмечает превышение бюджета при warn",
                    warnAgent.getLastDiagnostics() != null
                            && warnAgent.getLastDiagnostics().contextBudgetExceeded());

            // /tokens: окно настроено — источник и значение; окна нет — «неизвестен».
            FakeUi tokensUi = new FakeUi(
                    TerminalUi.Input.command("/tokens"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(tokensUi, warnAgent, "glm-5.3-flash");
            expect("/tokens показывает настроенное окно и источник",
                    tokensUi.systems.stream().anyMatch(s -> s.contains("контекстное окно: "
                            + (projected - 1)) && s.contains("ручная настройка")));
            warnStore.close();
            blockStore.close();

            Map<String, String> noWindowEnv = new java.util.HashMap<>();
            noWindowEnv.put("LLM_MAX_OUTPUT_TOKENS", "64");
            JsonConversationStore noWindowStore = tempStore();
            LlmAgent noWindowAgent = new LlmAgent(config, ModelSettings.from(noWindowEnv), client,
                    noWindowStore);
            String tokensNoWindow = Main.formatTokens(noWindowAgent, "glm-5.3-flash");
            expect("/tokens без окна сообщает, что лимит неизвестен",
                    tokensNoWindow.contains("лимит контекста неизвестен")
                            && tokensNoWindow.contains("процент заполнения не вычисляется"));
            noWindowStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkTokensStatsCommandsNoApi() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":12,"
                    + "\"completion_tokens\":34}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), client, store);
            agent.ask("вопрос для команд токенов");
            int hitsAfterAsk = hitCounter.get();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/tokens"),
                    TerminalUi.Input.command("/stats"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/tokens и /stats не вызывают API", hitCounter.get() == hitsAfterAsk);
            expect("/tokens показывает оценку истории и пометку «оценка»",
                    ui.systems.stream().anyMatch(s -> s.contains("Токены")
                            && s.contains("источник подсчёта")
                            && s.contains("оценка")
                            && s.contains("сохранённый архив")));
            expect("/stats показывает фактические суммы usage",
                    ui.systems.stream().anyMatch(s -> s.contains("Статистика сессии")
                            && s.contains("попыток обращения к API: 1")
                            && s.contains("12") && s.contains("34")));

            // Без запросов: итог «запросов ещё не было», расход «нет данных».
            JsonConversationStore freshStore = tempStore();
            LlmAgent freshAgent = new LlmAgent(config, ModelSettings.defaults(), client, freshStore);
            String freshStats = Main.formatStats(freshAgent);
            expect("/stats без запросов не выдумывает расход",
                    freshStats.contains("запросов ещё не было")
                            && freshStats.contains("нет данных")
                            && freshStats.contains("Стоимость: нет данных — тариф не настроен"));
            freshStore.close();

            // Справка plain-режима содержит новые команды.
            CapturedStream err = capturingStream();
            PlainTerminalUi helpUi = new PlainTerminalUi(reader(""), capturingStream().stream,
                    err.stream);
            helpUi.showFullHelp();
            expect("справка plain-режима содержит /tokens и /stats",
                    err.text().contains("/tokens") && err.text().contains("/stats"));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkSessionTokenLimitFeature() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            // Маркер более длинного сценария проверяется первым: после первого
            // запроса маркеры попадают в историю и уходят в следующих телах.
            if (requestBody.contains("case=partial-big")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ P2\"}}],\"usage\":{\"prompt_tokens\":600}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=partial-small")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ P1\"}}],\"usage\":{\"prompt_tokens\":10}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=no-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ U\"}}]}").getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=empty-with-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"\"}}],\"usage\":{\"prompt_tokens\":1000,"
                        + "\"completion_tokens\":2000,\"total_tokens\":9000}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ "
                    + hitCounter.get() + "\"}}],\"usage\":{\"prompt_tokens\":100,"
                    + "\"completion_tokens\":50,\"total_tokens\":500}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // Расход меньше лимита: уведомления нет, показывается остаток.
            JsonConversationStore underStore = tempStore();
            LlmAgent underAgent = newAgentLimit(config, client, underStore, 400);
            underAgent.ask("вопрос до лимита");
            expect("расход меньше лимита: уведомления нет",
                    underAgent.consumeSessionLimitNotice() == null);
            String underStatus = Main.formatLimit(underAgent);
            expect("статус «не превышен» с остатком до лимита",
                    underStatus.contains("не превышен")
                            && underStatus.contains("остаток до лимита: 250"));
            underStore.close();

            // Расход равен лимиту: «достигнут», уведомление о превышении не выводится.
            JsonConversationStore equalStore = tempStore();
            LlmAgent equalAgent = newAgentLimit(config, client, equalStore, 150);
            equalAgent.ask("вопрос на границе лимита");
            expect("расход равен лимиту: уведомления о превышении нет",
                    equalAgent.consumeSessionLimitNotice() == null);
            String equalStatus = Main.formatLimit(equalAgent);
            expect("при равенстве статус «достигнут» без превышения",
                    equalStatus.contains("достигнут")
                            && !equalStatus.contains("превышен")
                            && !equalStatus.contains("превышение"));
            equalStore.close();

            // Пересечение: уведомление один раз, не повторяется на следующих
            // запросах; после превышения запросы по-прежнему разрешены;
            // total_tokens не суммируется повторно.
            JsonConversationStore crossStore = tempStore();
            LlmAgent crossAgent = newAgentLimit(config, client, crossStore, 200);
            crossAgent.ask("первый вопрос с лимитом");
            expect("до пересечения уведомления нет",
                    crossAgent.consumeSessionLimitNotice() == null);
            crossAgent.ask("второй вопрос с лимитом");
            String firstNotice = crossAgent.consumeSessionLimitNotice();
            expect("при пересечении показывается уведомление с деталями",
                    firstNotice != null
                            && firstNotice.contains("Лимит токенов за сессию превышен")
                            && firstNotice.contains("Установленный лимит: 200")
                            && firstNotice.contains("Учтённый расход: 300")
                            && firstNotice.contains("Превышение: 100 токенов")
                            && firstNotice.contains("Агент продолжает работу")
                            && firstNotice.contains("/limit off"));
            expect("уведомление однократное: повторное чтение пусто",
                    crossAgent.consumeSessionLimitNotice() == null);
            String thirdAnswer = crossAgent.ask("третий вопрос с лимитом");
            expect("после превышения следующий запрос разрешён",
                    thirdAnswer.contains("Ответ") && crossAgent.consumeSessionLimitNotice() == null);
            SessionTokenStats.Snapshot crossStats = crossAgent.sessionStats();
            expect("total_tokens не учитывается повторно (450, а не 1500)",
                    crossStats.totalPromptTokens() == 300
                            && crossStats.totalCompletionTokens() == 150
                            && crossStats.knownTotal() == 450);
            crossStore.close();

            // Команды /limit через диспетчер Main: установка, off, повторы,
            // понижение/повышение и некорректные значения.
            JsonConversationStore cmdStore = tempStore();
            LlmAgent cmdAgent = newAgentLimit(config, client, cmdStore, 200);
            cmdAgent.ask("командный вопрос 1");
            cmdAgent.ask("командный вопрос 2");
            expect("первое превышение по порогу из окружения зафиксировано",
                    cmdAgent.consumeSessionLimitNotice() != null);
            int hitsBeforeCommands = hitCounter.get();
            int historyBeforeCommands = cmdAgent.getHistory().size();
            FakeUi limitUi = new FakeUi(
                    TerminalUi.Input.command("/limit"),
                    TerminalUi.Input.command("/limit 200"),
                    TerminalUi.Input.command("/limit 500"),
                    TerminalUi.Input.command("/limit 100"),
                    TerminalUi.Input.command("/limit off"),
                    TerminalUi.Input.command("/limit"),
                    TerminalUi.Input.message("вопрос после отключения лимита"),
                    TerminalUi.Input.command("/limit 100"),
                    TerminalUi.Input.command("/limit 90"),
                    TerminalUi.Input.command("/limit zero"),
                    TerminalUi.Input.command("/limit 0"),
                    TerminalUi.Input.command("/limit -5"),
                    TerminalUi.Input.command("/limit 5.5"),
                    TerminalUi.Input.command("/limit 99999999999999999999999"),
                    TerminalUi.Input.command("/limit"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(limitUi, cmdAgent, "glm-5.3-flash");
            expect("при запуске сообщается, включён ли лимит сессии",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит расхода токенов за сессию: 200")));
            expect("/limit показывает статус превышенного лимита",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит расхода токенов за сессию: 200")
                                    && s.contains("статус: превышен")));
            expect("повторная установка того же лимита без дублирующего уведомления",
                    limitUi.systems.stream().noneMatch(s ->
                            s.contains("Установленный лимит: 200")));
            expect("повышение лимита выше расхода не создаёт уведомления",
                    limitUi.systems.stream().noneMatch(s ->
                            s.contains("Установленный лимит: 500")));
            expect("установка порога ниже расхода даёт сообщение сразу",
                    limitUi.systems.stream().filter(s ->
                            s.contains("Установленный лимит: 100")).count() == 1);
            expect("/limit off отключает уведомления и сохраняет расход",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Уведомление по лимиту сессии отключено")
                                    && s.contains("Накопленный расход сохранён: 300")));
            expect("после отключения статус «отключён»",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит расхода токенов за сессию: отключён")));
            expect("после превышения запрос по-прежнему выполнен",
                    limitUi.messages.size() == 1);
            expect("повторное включение того же порога не дублирует уведомление",
                    limitUi.systems.stream().filter(s ->
                            s.contains("Установленный лимит: 100")).count() == 1);
            expect("понижение до нового порога даёт уведомление сразу",
                    limitUi.systems.stream().filter(s ->
                            s.contains("Установленный лимит: 90")).count() == 1);
            expect("некорректные значения отклоняются с подсказкой (5 попыток)",
                    limitUi.errors.stream().filter(s ->
                            s.contains("положительным целым числом")).count() == 5);
            expect("после некорректного ввода прежняя настройка сохранена",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит расхода токенов за сессию: 90")));
            expect("уведомления по лимиту не привязаны к диагностике",
                    limitUi.systems.stream().noneMatch(s -> s.contains("Диагностика:")));
            expect("команды лимита не вызывают API",
                    hitCounter.get() == hitsBeforeCommands + 1);
            expect("команды лимита не меняют историю",
                    cmdAgent.getHistory().size() == historyBeforeCommands + 2);
            expect("изменение лимита не сбрасывает накопленный расход",
                    cmdAgent.sessionStats().knownTotal() == 450);
            cmdStore.close();

            // Неполный usage: расход «не менее», статус неопределён.
            JsonConversationStore partialStore = tempStore();
            LlmAgent partialAgent = newAgentLimit(config, client, partialStore, 50);
            partialAgent.ask("case=partial-small");
            expect("частичный usage ниже лимита: уведомления нет",
                    partialAgent.consumeSessionLimitNotice() == null);
            String partialStatus = Main.formatLimit(partialAgent);
            expect("неполные данные: расход «не менее» и статус неопределён",
                    partialStatus.contains("не менее 10")
                            && partialStatus.contains("нельзя достоверно определить")
                            && partialStatus.contains("фактический расход может быть выше"));
            partialAgent.ask("case=partial-big");
            String partialNotice = partialAgent.consumeSessionLimitNotice();
            expect("превышение при неполном usage обозначается как минимум",
                    partialNotice != null
                            && partialNotice.contains("не менее 610")
                            && partialNotice.contains("Превышение: не менее 560 токенов")
                            && partialNotice.contains("фактический расход может быть выше"));
            partialStore.close();

            // Отсутствие usage: расход неизвестен, уведомления нет.
            JsonConversationStore noUsageStore = tempStore();
            LlmAgent noUsageAgent = newAgentLimit(config, client, noUsageStore, 50);
            noUsageAgent.ask("case=no-usage");
            expect("без usage расход неизвестен и уведомления нет",
                    noUsageAgent.consumeSessionLimitNotice() == null);
            expect("статус с отсутствующим usage — «нельзя достоверно определить»",
                    Main.formatLimit(noUsageAgent).contains("нельзя достоверно определить"));
            noUsageStore.close();

            // Пустой ответ с usage через Main: уведомление показывается
            // при выключенной диагностике; история не меняется; далее чат жив.
            JsonConversationStore emptyStore = tempStore();
            Map<String, String> emptyEnv = new java.util.HashMap<>();
            emptyEnv.put("LLM_SESSION_TOKEN_LIMIT", "50");
            LlmAgent emptyAgent = new LlmAgent(config, ModelSettings.from(emptyEnv), client, emptyStore);
            FakeUi emptyUi = new FakeUi(
                    TerminalUi.Input.message("case=empty-with-usage"),
                    TerminalUi.Input.message("обычный вопрос после пустого"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(emptyUi, emptyAgent, "glm-5.3-flash");
            expect("пустой ответ распознаётся ошибкой",
                    emptyUi.errors.stream().anyMatch(s -> s.contains("пустой итоговый ответ")));
            expect("уведомление о лимите показано при пустом ответе с usage",
                    emptyUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит токенов за сессию превышен")
                                    && s.contains("Установленный лимит: 50")
                                    && s.contains("Учтённый расход: 3000")));
            expect("уведомление показывается при выключенной диагностике",
                    emptyUi.systems.stream().noneMatch(s -> s.contains("Диагностика:")));
            expect("после пустого ответа история не изменилась",
                    emptyAgent.getHistory().size() == 2);
            expect("после превышения следующим сообщением получен ответ",
                    emptyUi.messages.size() == 1);
            emptyStore.close();

            // Перезапуск: расход сессии сбрасывается, история сохраняется.
            Path restartFile = Files.createTempDirectory(baseTempDir, "limit-restart-")
                    .resolve("conversation.json");
            Map<String, String> restartEnv = new java.util.HashMap<>();
            restartEnv.put("LLM_SESSION_TOKEN_LIMIT", "200");
            int historyBeforeRestart;
            try (JsonConversationStore restartStore = new JsonConversationStore(restartFile)) {
                LlmAgent first = new LlmAgent(config, ModelSettings.from(restartEnv),
                        client, restartStore);
                first.ask("вопрос до перезапуска");
                first.ask("второй вопрос до перезапуска");
                expect("перед перезапуском расход учтён",
                        first.sessionStats().knownTotal() == 300);
                historyBeforeRestart = first.getHistory().size();
            }
            try (JsonConversationStore reopened = new JsonConversationStore(restartFile)) {
                LlmAgent second = new LlmAgent(config, ModelSettings.from(restartEnv),
                        client, reopened);
                expect("перезапуск сбрасывает расход сессии",
                        second.sessionStats().apiAttempts() == 0
                                && second.sessionStats().knownTotal() == 0);
                expect("перезапуск сохраняет историю",
                        second.hasRestoredContext()
                                && second.getHistory().size() == historyBeforeRestart);
                expect("после перезапуска уведомлений нет",
                        second.consumeSessionLimitNotice() == null);
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9SettingsValidation() {
        Map<String, String> env = day9Env("10", "10");
        ModelSettings settings = ModelSettings.from(env);
        expect("LLM_CONTEXT_MODE=summary принимается", settings.contextMode() == ContextMode.SUMMARY);
        expect("значения сжатия по умолчанию соответствуют константам",
                settings.keepLastMessages() == ModelSettings.DEFAULT_KEEP_LAST_MESSAGES
                        && settings.summaryBatchMessages()
                        == ModelSettings.DEFAULT_SUMMARY_BATCH_MESSAGES
                        && settings.summaryMaxOutputTokens()
                        == ModelSettings.DEFAULT_SUMMARY_MAX_OUTPUT_TOKENS);

        for (String invalid : new String[]{"0", "3", "11", "abc", "-2"}) {
            Map<String, String> badKeep = day9Env(invalid, "10");
            try {
                ModelSettings.from(badKeep);
                expect("LLM_CONTEXT_KEEP_LAST_MESSAGES=" + invalid + " отклоняется", false);
            } catch (AgentException e) {
                expect("LLM_CONTEXT_KEEP_LAST_MESSAGES=" + invalid + " отклоняется с понятной "
                        + "ошибкой", e.getMessage().contains("LLM_CONTEXT_KEEP_LAST_MESSAGES"));
            }
            Map<String, String> badBatch = day9Env("10", invalid);
            try {
                ModelSettings.from(badBatch);
                expect("LLM_SUMMARY_BATCH_MESSAGES=" + invalid + " отклоняется", false);
            } catch (AgentException e) {
                expect("LLM_SUMMARY_BATCH_MESSAGES=" + invalid + " отклоняется", e.getMessage()
                        .contains("LLM_SUMMARY_BATCH_MESSAGES"));
            }
        }
        Map<String, String> badSummaryTokenLimit = day9Env("10", "10");
        badSummaryTokenLimit.put("LLM_SUMMARY_MAX_OUTPUT_TOKENS", "-5");
        try {
            ModelSettings.from(badSummaryTokenLimit);
            expect("LLM_SUMMARY_MAX_OUTPUT_TOKENS=-5 отклоняется", false);
        } catch (AgentException e) {
            expect("LLM_SUMMARY_MAX_OUTPUT_TOKENS=-5 отклоняется",
                    e.getMessage().contains("LLM_SUMMARY_MAX_OUTPUT_TOKENS"));
        }
        Map<String, String> badMode = day9Env("10", "10");
        badMode.put("LLM_CONTEXT_MODE", "gip");
        try {
            ModelSettings.from(badMode);
            expect("LLM_CONTEXT_MODE=gip отклоняется", false);
        } catch (AgentException e) {
            expect("LLM_CONTEXT_MODE=gip отклоняется",
                    e.getMessage().contains("LLM_CONTEXT_MODE"));
        }
    }

     static void checkDay9SummaryThresholds() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            Path historyFile = store.file();

            agent.ask("вопрос 1");  // 2 сообщений: сжимать нечего
            agent.ask("вопрос 2");  // 4 сообщения: uncovered = 0..2 < BATCH=4
            agent.ask("вопрос 3");  // 6 сообщений: uncovered = 2 < BATCH=4
            expect("ниже порога суммаризация не запускается",
                    s.summaryHits().get() == 0 && agent.summary() == null);

            int hitsBefore = s.regularHits().get();
            agent.ask("вопрос 4");  // 8 сообщений: uncovered = 4 >= BATCH → резюме
            expect("на пороге суммаризация выполняется ровно один раз",
                    s.summaryHits().get() == 1 && agent.summary() != null);
            ConversationSummary summary = agent.summary();
            expect("резюме покрывает первые сообщения вне последних KEEP",
                    summary.coveredMessages() == 4 && agent.getHistory().size() == 8
                            && agent.getHistory().size() - summary.coveredMessages() == 4);
            expect("резюме соответствует текущему архиву",
                    summary.matchesArchive(agent.sessionIdAfterAskForTest(), agent.getHistory()));

            // Регулярный запрос после сжатия: system со справкой + хвост дословно.
            expect("обычный запрос выполнен после суммаризации",
                    s.regularHits().get() == hitsBefore + 1);
            String lastRegularBody = null;
            for (int i = s.bodies().size() - 1; i >= 0; i--) {
                String body = s.bodies().get(i);
                if (body.contains("system") && !body.contains(SUMMARY_MARKER)
                        && body.contains("вопрос 4")) {
                    lastRegularBody = body;
                    break;
                }
            }
            JsonNode regularMessages = MAPPER.readTree(lastRegularBody).path("messages");
            expect("system содержит справочное резюме, отделённое от инструкций",
                    regularMessages.get(0).path("content").asText()
                            .contains("<<<РЕЗЮМЕ")
                            && regularMessages.get(0).path("content").asText()
                            .contains("Резюме: кодовое слово ЯКОРЬ-42")
                            && regularMessages.get(0).path("content").asText()
                            .contains("исторические"));
            expect("несжатый хвост отправляется дословно, без дублирования покрытых",
                    regularMessages.size() == 4 // system + tail (2 сообщения) + новый вопрос
                            && "вопрос 3".equals(regularMessages.get(1).path("content").asText())
                            && regularMessages.get(2).path("content").asText().startsWith("Ответ 3")
                            && "вопрос 4".equals(regularMessages.get(3).path("content").asText()));

            // Полный архив сохранён, резюме хранится отдельно, как сущность.
            JsonNode saved = MAPPER.readTree(
                    Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("архив не обрезается сжатием: все сообщения в файле",
                    saved.path("messages").size() == 8);
            expect("резюме сохранено отдельным объектом с отпечатком и версией",
                    saved.path("summary").path("coveredMessages").asInt(-1) == 4
                            && saved.path("summary").path("coveredFingerprint").asText().length()
                            == 64
                            && saved.path("summary").path("formatVersion").asInt(-1) == 1
                            && saved.path("summary").path("text").asText()
                            .contains("ЯКОРЬ-42"));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9IncrementalSummary() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);

            for (int i = 1; i <= 4; i++) {
                agent.ask("уникальный вопрос " + i);
            }
            expect("первое резюме создано", agent.summary() != null);
            String firstSummaryText = agent.summary().text();

            // Ещё две пары: первое прихранённое резюме не сдвигается, затем
            // обновление на новую границу (инкрементально).
            agent.ask("запоздалый вопрос 4");
            agent.ask("запоздалый вопрос 4 задача");
            expect("резюме обновлено на новую границу",
                    agent.summary() != null && agent.summary().coveredMessages() == 8);
            String lastSummaryBody = null;
            for (int i = s.bodies().size() - 1; i >= 0; i--) {
                String body = s.bodies().get(i);
                if (body.contains(SUMMARY_MARKER)) {
                    lastSummaryBody = body;
                    break;
                }
            }
            JsonNode summaryMessages = MAPPER.readTree(lastSummaryBody).path("messages");
            String userContent = summaryMessages.get(1).path("content").asText();
            expect("в инкрементальный запрос включено предыдущее резюме",
                    userContent.contains("Резюме: кодовое слово ЯКОРЬ-42")
                            && userContent.contains("уникальный вопрос 3")
                            && userContent.contains("уникальный вопрос 4"));
            expect("уже сжатые сообщения повторно не отправляются на суммаризацию",
                    !userContent.contains("уникальный вопрос 1")
                            && !userContent.contains("уникальный вопрос 2")
                            && !userContent.contains("запоздалый")
                            && firstSummaryText.equals(summaryMessages.get(0)
                            .path("content").asText()) == false);
            expect("инструкция суммаризации не подменяется краткостью профиля",
                    summaryMessages.get(0).path("content").asText().contains(SUMMARY_MARKER)
                            && !summaryMessages.get(0).path("content").asText()
                            .contains("кратко и по существу"));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9PersistenceAndStale() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 4; i++) {
                agent.ask("вопрос персистентности " + i);
            }
            ConversationSummary summaryBefore = agent.summary();
            expect("резюме создано перед перезапуском", summaryBefore != null);
            List<ChatMessage> archiveBefore = agent.getHistory();
            int summaryAttemptsBefore = s.summaryHits().get();
            store.close();

            // Перезапуск: архив и резюме восстанавливаются, API не вызывается.
            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                LlmAgent restored = new LlmAgent(config,
                        ModelSettings.from(day9Env("2", "4")), trustedHttpClient(keyStore),
                        reopened);
                expect("архив восстанавливается полностью",
                        restored.getHistory().equals(archiveBefore));
                ConversationSummary restoredSummary = restored.summary();
                expect("резюме восстанавливается с той же границей",
                        restoredSummary != null && restoredSummary.text()
                                .equals(summaryBefore.text())
                                && restoredSummary.coveredMessages()
                                == summaryBefore.coveredMessages());
                // Новый запрос: резюме идёт в system, суммаризация не вызывается.
                int summariesBefore2 = s.summaryHits().get();
                restored.ask("вопрос после перезапуска");
                expect("устойчивое резюме не требует повторной суммаризации",
                        s.summaryHits().get() == summariesBefore2);
                expect("после перезапуска в system уходит справка-резюме",
                        s.bodies().get(s.bodies().size() - 1).contains("<<<РЕЗЮМЕ"));
            }

            // Устаревшее резюме: изменить сообщение внутри покрытого куска
            // вручную (как это сделал бы другой процесс или редактирование).
            byte[] raw = Files.readAllBytes(historyFile);
            String json = new String(raw, StandardCharsets.UTF_8)
                    .replace("вопрос персистентности 1", "вопрос персистентности 1 ИЗМЕНЁН");
            Files.write(historyFile, json.getBytes(StandardCharsets.UTF_8));
            try (JsonConversationStore stale = new JsonConversationStore(historyFile)) {
                LlmAgent staleAgent = new LlmAgent(config,
                        ModelSettings.from(day9Env("2", "4")), trustedHttpClient(keyStore), stale);
                expect("устаревшее резюме не применяется, архив остаётся",
                        staleAgent.summary() == null
                                && staleAgent.getHistory().size() == 10);
                // Новый обычный запрос после устаревшего резюме создаёт новое
                // резюме (безопасное поведение без потери архива).
                staleAgent.ask("новый запрос после устаревшего резюме");
                expect("после устаревшего резюме создаётся новое резюме",
                        staleAgent.summary() != null);
            }
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9FailureKeepsHistory() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        AtomicInteger summaryScenarios = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                int scenario = summaryScenarios.incrementAndGet();
                if (scenario == 1) {
                    return new Response(500, "{\"error\":{\"message\":\"internal\"}}"
                            .getBytes(StandardCharsets.UTF_8));
                }
                if (scenario == 2) {
                    return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"\"}}],"
                            + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5}}")
                            .getBytes(StandardCharsets.UTF_8));
                }
                if (scenario == 3) {
                    // Обрезанный по лимиту ответ с usage: не выдаётся за резюме.
                    return new Response(200, ("{\"choices\":[{\"finish_reason\":\"length\","
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"частичный\""
                            + "}}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":500}}")
                            .getBytes(StandardCharsets.UTF_8));
                }
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                        + "{\"role\":\"assistant\",\"content\":\"Резюме: ок\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");

            for (int scenario = 1; scenario <= 3; scenario++) {
                JsonConversationStore store = tempStore();
                LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                        trustedHttpClient(keyStore), store);
                // Три подготовленных пары: следующий запрос достигает порога
                // и сначала выполняет реальную попытку суммаризации.
                for (int i = 1; i <= 3; i++) {
                    agent.ask("подготовка " + i);
                }
                String answer = agent.ask("вопрос для сбоя резюме " + scenario);
                expect("ошибка суммаризации не мешает обычному ответу",
                        answer != null && !answer.isBlank());
                expect("после сбоя суммаризации история сохранена и дополнена парой",
                        agent.getHistory().size() == 8
                                && agent.summary() == null);
                expect("после сбоя суммаризации архив не потерян и повторов нет",
                        agent.getHistory().size() == 8);
                SessionTokenStats.Snapshot stats = agent.sessionStats();
                // Попытки: обычные ответы + одна попытка суммаризации.
                expect("попытки API учитываются: обычные запросы и суммаризация",
                        stats.apiAttempts() == 5 && stats.regularAttempts() == 4
                                && stats.summaryAttempts() == 1
                                && (scenario >= 2 ? stats.summaryPromptTokens() == 50
                                : stats.summaryPromptTokens() == 0));
                if (scenario == 3) {
                    // length: completion учтён, резюме не принято.
                    expect("ограниченный по length ответ не принят за резюме, но учтён",
                            stats.summaryCompletionTokens() == 500);
                }
                // Разрыв пар нет, служебные запросы в историю не попали.
                expect("служебный запрос суммаризации не попадает в диалог",
                        agent.getHistory().stream()
                                .noneMatch(m -> m.content().contains("Резюме")));
                store.close();
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9ClearRemovesSummary() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 4; i++) {
                agent.ask("вопрос для очистки " + i);
            }
            expect("перед очисткой резюме существует", agent.summary() != null);
            long spendBefore = agent.sessionStats().knownTotal();
            boolean spendTracked = agent.sessionStats().summaryAttempts() > 0;

            agent.resetConversation();
            expect("resetConversation сбрасывает резюме", agent.summary() == null);
            JsonNode saved = MAPPER.readTree(
                    Files.readString(store.file(), StandardCharsets.UTF_8));
            expect("файл после очистки пуст и без объекта summary",
                    !saved.has("summary") && saved.path("messages").isEmpty());
            expect("расход сессии не сбрасывается очисткой",
                    agent.sessionStats().knownTotal() >= spendBefore);
            expect("расход суммаризации был учтён в сессии",
                    spendTracked && agent.sessionStats().summaryAttempts() >= 1);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9ContextCommandsNoApi() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            int hitsBefore = s.regularHits().get() + s.summaryHits().get();
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context"),
                    TerminalUi.Input.command("/context summary"),
                    TerminalUi.Input.command("/summary"),
                    TerminalUi.Input.command("/context full"),
                    TerminalUi.Input.command("/summary refresh"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("команды /context и /summary не вызывают API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore);
            expect("/context показывает текущий режим и параметры",
                    ui.systems.stream().anyMatch(t -> t.contains("Режим контекста")
                            && t.contains("дословно")));
            expect("/context summary сообщает о расходе служебных запросов",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("дополнительные запросы")));
            expect("переключённый режим отражается моделью настроек",
                    agent.currentSettings().contextMode() == ContextMode.FULL);
            expect("/summary refresh без истории объясняет без API",
                    ui.systems.stream().anyMatch(t -> t.contains("Сжимать пока нечего")));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9Compare() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 4; i++) {
                agent.ask("факт " + i);
            }
            expect("резюме есть перед сравнением", agent.summary() != null);
            List<ChatMessage> historyBefore = agent.getHistory();
            ConversationSummary summaryBefore = agent.summary();
            long knownBefore = agent.sessionStats().knownTotal();
            long attemptsBefore = agent.sessionStats().apiAttempts();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare Какой факт был первым?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            expect("сравнение не изменяет историю",
                    agent.getHistory().equals(historyBefore));
            expect("сравнение не продвигает резюме",
                    agent.summary().coveredMessages() == summaryBefore.coveredMessages()
                            && agent.summary().text().equals(summaryBefore.text()));
            expect("показаны оба ответа",
                    ui.messages.stream().anyMatch(m -> m.contains("БЕЗ СЖАТИЯ"))
                            && ui.messages.stream().anyMatch(m -> m.contains("СО СЖАТИЕМ")));
            // Сжатое резюме покрывает 6 из 10: сравнение готовит инкремент
            // (COMPARE_SUMMARY_PREP) и выполняет два сравнительных запроса.
            expect("сравнение = подготовка + ровно два запроса вариантов",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 3
                            && agent.sessionStats().compareAttempts() == 3);
            expect("расход сравнения учтён в сессии",
                    agent.sessionStats().knownTotal() > knownBefore);
            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("расход сравнения и суммаризации помечен по назначениям",
                    stats.compareAttempts() == 3 && stats.regularAttempts() >= 3
                            && stats.summaryAttempts() == 1);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9CompareGuards() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");

            // Короткая история: нет смысла сравнивать два одинаковых запроса.
            JsonConversationStore shortStore = tempStore();
            LlmAgent shortAgent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), shortStore);
            shortAgent.ask("единственный вопрос");
            int hitsBefore = s.regularHits().get() + s.summaryHits().get();
            FakeUi shortUi = new FakeUi(
                    TerminalUi.Input.command("/context compare любой"),
                    TerminalUi.Input.command("/exit"));
            shortUi.confirmCompareAnswer = true;
            Main.runLoop(shortUi, shortAgent, "glm-5.3-flash");
            expect("на короткой истории сравнение не выполняется без API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore
                            && shortUi.confirmCompareCount == 0);
            shortStore.close();

            // Отказ подтверждения: API не вызывается.
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 3; i++) {
                agent.ask("вопрос для отмены " + i);
            }
            hitsBefore = s.regularHits().get() + s.summaryHits().get();
            FakeUi cancelUi = new FakeUi(
                    TerminalUi.Input.command("/context compare любой"),
                    TerminalUi.Input.command("/exit"));
            cancelUi.confirmCompareAnswer = false;
            Main.runLoop(cancelUi, agent, "glm-5.3-flash");
            expect("отказ подтверждения не выполняет API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore
                            && cancelUi.messages.isEmpty());
            store.close();

            // Демо-беседа: сравнение отказывается (изоляция режимов).
            FakeUi demoUi = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.command("/context compare любой"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(demoUi, agent, "glm-5.3-flash");
            expect("в демо-режиме сравнение не выполняется без API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore
                            && demoUi.systems.stream().anyMatch(
                                    t -> t.contains("режиме измерения")
                                            || t.contains("сравнивать не на чем")));
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9CompareReadySummary() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            String sessionId = "seed-session-day9";
            List<ChatMessage> archive = seededArchive("ранний факт", "принял", 6);
            List<ChatMessage> covered = new ArrayList<>(archive.subList(0, 8));
            store.save(new ConversationState(sessionId, archive,
                    seedSummary(archive, 8, sessionId,
                            "Резюме: ранние факты 1-4 в свернутом виде")));

            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            expect("готовое summary действует перед сравнением",
                    agent.summary() != null && agent.summary().coveredMessages() == 8);
            expect("новых старых сообщений нет (вне последних 4 всё покрыто)",
                    agent.uncoveredOldMessagesCount() == 0);
            expect("сравнение возможно по готовому summary", agent.canCompare());

            long attemptsBefore = agent.sessionStats().apiAttempts();
            int summaryHitsBefore = s.summaryHits().get();
            long knownBefore = agent.sessionStats().knownTotal();
            List<ChatMessage> archiveBefore = agent.getHistory();
            String question = "Составь карточку проекта по нашей переписке";

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare " + question),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            expect("сравнение не блокируется, подтверждение запрошено",
                    ui.confirmCompareCount == 1);
            expect("служебного запроса суммаризации нет",
                    s.summaryHits().get() == summaryHitsBefore);
            expect("выполняются ровно два обычных запроса сравнения",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 2
                            && agent.sessionStats().compareAttempts() == 2);

            List<String> recent = s.bodies().subList(s.bodies().size() - 2, s.bodies().size());
            String fullBody = null;
            String compactBody = null;
            for (String body : recent) {
                if (body.contains("Резюме: ранние факты")) {
                    compactBody = body;
                } else {
                    fullBody = body;
                }
            }
            JsonNode fullMessages = MAPPER.readTree(fullBody).path("messages");
            // system + 12 сообщений архива + новый user = 14.
            expect("FULL содержит все 12 сообщений архива",
                    fullMessages.size() == 14
                            && "ранний факт 1".equals(fullMessages.get(1).path("content").asText())
                            && "ранний факт 6".equals(fullMessages.get(11).path("content").asText())
                            && fullMessages.get(13).path("content").asText().equals(question));
            JsonNode compactMessages = MAPPER.readTree(compactBody).path("messages");
            // system со справкой + 4 последних сообщения + новый user = 6.
            expect("SUMMARY содержит резюме и последние 4 сообщения",
                    compactMessages.size() == 6
                            && compactMessages.get(0).path("content").asText().contains("<<<РЕЗЮМЕ")
                            && "ранний факт 5".equals(compactMessages.get(1).path("content").asText())
                            && "принял 6".equals(compactMessages.get(4).path("content").asText())
                            && compactMessages.get(5).path("content").asText().equals(question));
            expect("ранние 8 сообщений не дублируются в SUMMARY",
                    streamContents(compactMessages).noneMatch("ранний факт 1"::equals)
                            && streamContents(compactMessages).noneMatch("ранний факт 2"::equals)
                            && streamContents(compactMessages).noneMatch("ранний факт 4"::equals));
            expect("вопрос одинаковый в обоих вариантах",
                    streamContents(fullMessages).anyMatch(question::equals)
                            && streamContents(compactMessages).anyMatch(question::equals));
            expect("архив и резюме после сравнения не изменились",
                    agent.getHistory().equals(archiveBefore)
                            && agent.summary().coveredMessages() == 8);
            expect("расходы учтены ровно один раз (полный usage обеих веток)",
                    agent.sessionStats().knownTotal() > knownBefore
                            && agent.sessionStats().complete());
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9CompareAfterRefresh() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            String sessionId = "seed-refresh-day9";
            store.save(new ConversationState(sessionId,
                    seededArchive("факт уточнения", "принял уточнение", 6)));
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            String refreshMsg = agent.refreshSummary();
            expect("/summary refresh создаёт резюме на 8 сообщений",
                    agent.summary() != null && agent.summary().coveredMessages() == 8
                            && refreshMsg != null);
            long attemptsBefore = agent.sessionStats().apiAttempts();
            int summaryHitsBefore = s.summaryHits().get();
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare какие факты важны?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("сравнение сразу после refresh: подготовки нет, два запроса",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 2
                            && s.summaryHits().get() == summaryHitsBefore);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9CompareReuseAfterRestart() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            String sessionId = "seed-restart-day9";
            List<ChatMessage> archive = seededArchive("пара для перезапуска", "готово", 5);
            store.save(new ConversationState(sessionId, archive,
                    seedSummary(archive, 6, sessionId,
                            "Резюме перезапуска: факты 1-3 в свернутом виде")));
            store.close();
            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                        trustedHttpClient(keyStore), reopened);
                int summaryHitsBefore = s.summaryHits().get();
                long attemptsBefore = agent.sessionStats().apiAttempts();
                FakeUi ui = new FakeUi(
                        TerminalUi.Input.command("/context compare откуда пара 1?"),
                        TerminalUi.Input.command("/exit"));
                ui.confirmCompareAnswer = true;
                Main.runLoop(ui, agent, "glm-5.3-flash");
                expect("после перезапуска summary переиспользуется без суммаризации",
                        s.summaryHits().get() == summaryHitsBefore
                                && agent.sessionStats().apiAttempts() == attemptsBefore + 2);
            }
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9ComparePrepFailure() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                return new Response(500, "{\"error\":{\"message\":\"internal\"}}"
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            store.save(new ConversationState("seed-prep-fail",
                    seededArchive("предрынок подготовки", "ок", 3)));
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            long attemptsBefore = agent.sessionStats().apiAttempts();
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare ранние события?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("сбой подготовки: full-запрос выполнен как обычный COMPARE_FULL",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 2);
            expect("сбой подготовки не выдаёт сравнение за успешное",
                    ui.messages.stream().anyMatch(m -> m.contains("СО СЖАТИЕМ")
                            && m.contains("сравнение со сжатием не выполнялось"))
                            && ui.systems.stream().anyMatch(t ->
                            t.contains("НЕ полностью успешное")));
            expect("расход неудачной попытки подготовки известен как минимум",
                    !stats.complete() || stats.requestsWithoutUsage() > 0);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9CompareUsageGaps() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Резюме: тест\""
                        + "}}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":4}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            // Различаем ветки по числу сообщений assistant: FULL содержит
            // весь архив (4), сжатый вариант — только хвост (3-… 2).
            int assistantCount = requestBody.split("assistant", -1).length - 1;
            if (assistantCount >= 4) {
                // Частичный usage у FULL.
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ Full\"}}],\"usage\":{\"prompt_tokens\":10}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            // SUMMARY-ветка — без usage вовсе.
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ Summary\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            List<ChatMessage> archive = seededArchive("предрынок", "ок", 4);
            store.save(new ConversationState("seed-usage-day9", archive));
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            LlmAgent.CompareResult first = agent.compare("маркерный вопрос");
            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("подготовка и оба варианта составляют три сравнительных попытки",
                    stats.compareAttempts() == 3
                            && first.prepPerformed() && first.prepError() == null
                            && first.prepPromptTokens() != null
                            && first.prepCompletionTokens() != null);
            LlmAgent.CompareResult r = agent.compare("вопрос для разрыва usage");
            SessionTokenStats.Snapshot stats2 = agent.sessionStats();
            expect("частичный usage у FULL и отсутствующий у SUMMARY честно помечены",
                    stats2.totalPromptTokens() >= 50
                            && stats2.totalCompletionTokens() >= 0
                            && !stats2.complete()
                            && r.fullPromptTokens() != null && r.fullCompletionTokens() == null
                            && r.summaryPromptTokens() == null
                            && r.summaryCompletionTokens() == null);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9ContextCaptions() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");

            // Пустая история (full по умолчанию).
            JsonConversationStore empty = tempStore();
            LlmAgent fullAgent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), empty);
            fullAgent.ask("первый вопрос");
            expect("пустая история: подпись «Первый запрос»",
                    "Первый запрос: предыдущей истории нет".equals(
                            fullAgent.lastRequestContextCaption()));
            empty.close();

            // Summary-режим без резюме.
            JsonConversationStore noSummary = tempStore();
            LlmAgent noSummaryAgent = new LlmAgent(
                    config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), noSummary);
            noSummaryAgent.ask("начало");
            noSummaryAgent.ask("начало второе");
            expect("summary без резюме: дословные сообщения и «Резюме пока не создано»",
                    "Контекст запроса: 2 сообщений истории дословно. Резюме пока не создано"
                            .equals(noSummaryAgent.lastRequestContextCaption()));

            // Full: подпись последнего запроса фиксировала 6 сообщений истории
            // (перед четвёртым запросом в архиве 3 пары).
            JsonConversationStore fullStore = tempStore();
            LlmAgent fullAgent2 = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), fullStore);
            for (int i = 1; i <= 4; i++) {
                fullAgent2.ask("факт " + i);
            }
            String fullCaption = fullAgent2.lastRequestContextCaption();
            expect("full: «вся история — 6 сообщений дословно»",
                    fullCaption != null
                            && fullCaption.contains("вся история")
                            && fullCaption.contains("6 сообщений дословно"));
            fullStore.close();
            noSummary.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay9ArchiveNoLossAcrossModes() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "2")),
                    trustedHttpClient(keyStore), store);
            // 12 пар подготовлены до переключений (KEEP=2/BATCH=2 прощает.
            for (int i = 1; i <= 12; i++) {
                agent.ask("длинный факт " + i);
            }
            List<ChatMessage> archiveAfterSummary = agent.getHistory();
            agent.setContextMode("full");
            agent.ask("проверка полного переключения");
            List<ChatMessage> archiveAfterFull = agent.getHistory();
            JsonNode saved = MAPPER.readTree(
                    Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("архив не терялся при переключениях режимов",
                    saved.path("messages").size() == 26
                            && archiveAfterFull.size() == 26
                            && archiveAfterSummary.size() == 24);
            agent.setContextMode("summary");
            agent.ask("заключительный факт");
            expect("переключение summary → full → summary не теряет архив",
                    agent.getHistory().size() == 28
                            && agent.summary() != null);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }
}
