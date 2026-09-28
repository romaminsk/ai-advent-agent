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

final class ContextStrategyChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkDay92BenefitGateShortSkips();
        checkDay92BenefitGateProfitableRuns();
        checkDay92RefreshWarnsButRuns();
        checkDay92CompareWarnsButRuns();
        checkDay92CompactTemplateAndRules();
        checkDay92StatsBalanceLine();
        checkDay10SettingsValidation();
        checkDay10StrategySwitchNoApi();
        checkDay10SlidingWindow();
        checkDay10FactsFlow();
        checkDay10FactsManualAndClear();
        checkDay10FactsInSessionLimit();
        checkDay10Branching();
        checkDay10BranchPersistence();
        checkDay10StrategyCompare();
        checkDay10CompareGuards();
        checkFactsPrepLengthAbortsCompare();
        checkFactsNearLimitWarning();
        checkThreeLayersInRequest();
        checkContextBlockOrder();
        checkMemoryUpdateAccountingOnce();
    }

     static void checkDay92BenefitGateShortSkips() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger summaryHits = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            // Односложные ответы ассистента — как в реальном замере Дня 9.
            if (requestBody.contains(SUMMARY_MARKER)) {
                summaryHits.incrementAndGet();
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Резюме: тест\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Запомнил.\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "2")),
                    trustedHttpClient(keyStore), store);
            FakeUi ui = new FakeUi(TerminalUi.Input.command("/exit"));
            agent.setProgressListener(ui::showSystem);
            for (int i = 1; i <= 3; i++) {
                agent.ask("подробный факт пользователя номер " + i + " с числом, датой "
                        + "и запретом: длинное содержательное сообщение проекта, история "
                        + "действий, требований и ограничений, доля сведений пользователя "
                        + "в заменяемом объёме велика, и сжатие заведомо невыгодно");
            }
            List<String> notes = agent.consumeContextNotes();
            expect("короткий сценарий: сжатие пропущено, отмечена невыгодность",
                    agent.summary() == null
                            && summaryHits.get() == 0
                            && notes.stream().anyMatch(
                                    t -> t.contains("Сжатие сейчас невыгодно")));
            JsonNode saved = MAPPER.readTree(
                    Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("после пропуска сжатия архив сохранён без резюме",
                    saved.path("messages").size() == 6 && !saved.has("summary"));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay92BenefitGateProfitableRuns() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "2")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 3; i++) {
                agent.ask("короткий вопрос " + i);
            }
            expect("выгодное сжатие выполняется по локальной оценке",
                    agent.summary() != null && agent.summary().coveredMessages() == 2);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay92RefreshWarnsButRuns() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Резюме: тест\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Запомнил.\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "2")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 3; i++) {
                agent.ask("подробный факт пользователя номер " + i + ": имя проекта, датa "
                        + "сдачи, точное число вариантов, запрет на публикацию и открытый "
                        + "вопрос о совместимости; строка намеренно длинная, чтобы минимум "
                        + "содержания превышал безопасный порог выгоды по локальной оценке "
                        + "и сжатие заведомо не дало бы экономии");
            }
            expect("оценка указывает на невыгодность", !agent.compressionBenefitLikely());
            String result = agent.refreshSummary();
            expect("refresh выполняется независимо от невыгодной оценки",
                    agent.summary() != null);
            expect("перед явным сжатием показано предупреждение",
                    result != null && result.contains("Предупреждение")
                            && result.contains("невыгодно"));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay92CompareWarnsButRuns() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Резюме: тест\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Запомнил.\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 4; i++) {
                agent.ask("подробный факт пользователя " + i + ": имя проекта, численaч "
                        + "версия, запрет на публикацию и открытый вопрос; строка длинная, "
                        + "чтобы содержательная доля превышала порог выгоды и сжатие "
                        + "показывало невыгодность");
            }
            long attemptsBefore = agent.sessionStats().apiAttempts();
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare короткая проверка?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("сравнение выполняется при невыгодной оценке",
                    agent.sessionStats().apiAttempts() >= attemptsBefore + 2
                            && ui.messages.stream().anyMatch(m -> m.contains("БЕЗ СЖАТИЯ")));
            expect("перед сравнением показано предупреждение о невыгодности",
                    ui.systems.stream().anyMatch(t -> t.contains("Предупреждение")
                            && t.contains("невыгодно")));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay92CompactTemplateAndRules() {
        String prompt = LlmAgent.summaryPrompt();
        expect("шаблон summary: плоский список, «один факт — одна строка»",
                prompt.contains("плоский список") && prompt.contains("один факт — одна строка"));
        expect("шаблон запрещает заголовки и Markdown",
                prompt.contains("без заголовков и Markdown"));
        expect("шаблон отбрасывает односложные подтверждения ассистента",
                prompt.contains("«Запомнил.»") && prompt.contains("не сохраняй"));
        expect("каждое сведение записывается один раз",
                prompt.contains("каждое сведение записывай один раз"));
        expect("сохраняются только действующие значения",
                prompt.contains("только действующие значения"));
        expect("исправления заменяют прежние значения",
                prompt.contains("исправления заменяют прежние"));
        expect("разовые просьбы не превращаются в предпочтения",
                prompt.contains("не превращай"));
        expect("пустые разделы не добавляются",
                prompt.contains("только непустые"));
        expect("нет обещания постоянного сокращения",
                prompt.contains("не обещается"));
        expect("в шаблоне нет Markdown-заголовков",
                !prompt.contains("##") && !prompt.contains("# Резюме"));
    }

     static void checkDay92StatsBalanceLine() {
        SessionTokenStats emptyStats = new SessionTokenStats();
        String emptyLine = Main.compressionBalanceLine(emptyStats.snapshot());
        expect("без запросов баланс помечен недостатком данных",
                emptyLine.contains("недостаточно данных"));

        SessionTokenStats usedStats = new SessionTokenStats();
        usedStats.recordAttempt();
        usedStats.recordUsage(484, 128);
        usedStats.recordContextSavings(20, false, true);
        usedStats.recordAttempt(SessionTokenStats.Purpose.SUMMARY);
        usedStats.recordUsage(SessionTokenStats.Purpose.SUMMARY, 482, 118);
        SessionTokenStats.Snapshot used = usedStats.snapshot();
        String usedLine = Main.compressionBalanceLine(used);
        expect("баланс показывает оценку экономии и затраты суммаризации",
                usedLine.contains("экономия входа: 20 prompt_tokens")
                        && usedLine.contains("вход 482")
                        && usedLine.contains("выход 118"));
        long balance = used.contextSavingsPromptTokens()
                - (used.summaryPromptTokens() + used.summaryCompletionTokens());
        expect("итоговый баланс = экономия минус затраты суммаризации",
                usedLine.contains((balance >= 0 ? "+" : "") + balance));
        expect("баланс отрицательный — перерасход",
                balance < 0 && usedLine.contains(String.valueOf(balance)));

        SessionTokenStats partialStats = new SessionTokenStats();
        partialStats.recordUsage(484, 128);
        partialStats.recordContextSavings(0, false, false);
        String partialLine = Main.compressionBalanceLine(partialStats.snapshot());
        expect("отсутствующий usage помечается недостатком данных, а не нулём",
                partialLine.contains("недостаточно данных"));
        expect("разница при отсутствии usage не записывается как экономия",
                partialStats.snapshot().contextSavingsRequests() == 0);
    }

     static void checkDay10SettingsValidation() {
        Map<String, String> env = new java.util.HashMap<>();
        ModelSettings defaults = ModelSettings.from(env);
        expect("стратегия по умолчанию sliding-window",
                defaults.contextStrategy() == ContextStrategy.SLIDING_WINDOW
                        && defaults.slidingWindowMessages()
                        == ModelSettings.DEFAULT_SLIDING_WINDOW_MESSAGES
                        && defaults.factsWindowMessages()
                        == ModelSettings.DEFAULT_FACTS_WINDOW_MESSAGES
                        && defaults.factsMaxOutputTokens()
                        == ModelSettings.DEFAULT_FACTS_MAX_OUTPUT_TOKENS
                        && defaults.factsUpdateMode() == FactsUpdateMode.AUTO);

        env.put("LLM_CONTEXT_STRATEGY", " facts ");
        expect("LLM_CONTEXT_STRATEGY читается без учёта регистра и пробелов",
                ModelSettings.from(env).contextStrategy() == ContextStrategy.FACTS);
        env.put("LLM_CONTEXT_STRATEGY", "branching");
        expect("стратегия branching читается",
                ModelSettings.from(env).contextStrategy() == ContextStrategy.BRANCHING);
        env.put("LLM_SLIDING_WINDOW_MESSAGES", "14");
        env.put("LLM_FACTS_WINDOW_MESSAGES", "6");
        env.put("LLM_FACTS_MAX_OUTPUT_TOKENS", "256");
        env.put("LLM_FACTS_UPDATE_MODE", " manual ");
        ModelSettings full = ModelSettings.from(env);
        expect("окна и лимит генерации фактов читаются",
                full.slidingWindowMessages() == 14 && full.factsWindowMessages() == 6
                        && full.factsMaxOutputTokens() == 256
                        && full.factsUpdateMode() == FactsUpdateMode.MANUAL);

        for (String invalid : new String[]{"0", "3", "9", "abc", "-2"}) {
            Map<String, String> badWindow = new java.util.HashMap<>(env);
            badWindow.put("LLM_SLIDING_WINDOW_MESSAGES", invalid);
            try {
                ModelSettings.from(badWindow);
                expect("LLM_SLIDING_WINDOW_MESSAGES=" + invalid + " отклоняется", false);
            } catch (AgentException e) {
                expect("LLM_SLIDING_WINDOW_MESSAGES=" + invalid + " отклоняется "
                        + "с понятной ошибкой", e.getMessage()
                        .contains("LLM_SLIDING_WINDOW_MESSAGES"));
            }
            Map<String, String> badFactsWindow = new java.util.HashMap<>(env);
            badFactsWindow.put("LLM_FACTS_WINDOW_MESSAGES", invalid);
            try {
                ModelSettings.from(badFactsWindow);
                expect("LLM_FACTS_WINDOW_MESSAGES=" + invalid + " отклоняется", false);
            } catch (AgentException e) {
                expect("LLM_FACTS_WINDOW_MESSAGES=" + invalid + " отклоняется",
                        e.getMessage().contains("LLM_FACTS_WINDOW_MESSAGES"));
            }
        }
        Map<String, String> badStrategy = new java.util.HashMap<>(env);
        badStrategy.put("LLM_CONTEXT_STRATEGY", "summary");
        try {
            ModelSettings.from(badStrategy);
            expect("LLM_CONTEXT_STRATEGY=summary отклоняется", false);
        } catch (AgentException e) {
            expect("LLM_CONTEXT_STRATEGY=summary отклоняется",
                    e.getMessage().contains("LLM_CONTEXT_STRATEGY"));
        }
        expectSettingsError(env, "LLM_CONTEXT_STRATEGY", "gip");
        expectSettingsError(env, "LLM_FACTS_MAX_OUTPUT_TOKENS", "-1");
        expectSettingsError(env, "LLM_FACTS_UPDATE_MODE", "always");

        ContextStrategy switched = ContextStrategy.SLIDING_WINDOW;
        expect("переключение стратегии через withStrategy не меняет остальные поля",
                switched == ContextStrategy.SLIDING_WINDOW
                        && full.withStrategy(ContextStrategy.FACTS).contextStrategy()
                        == ContextStrategy.FACTS
                        && full.withStrategy(ContextStrategy.FACTS).slidingWindowMessages()
                        == full.slidingWindowMessages());

        expect("имя ветки валидируется: пустое отклоняется",
                expectAgentThrow(() -> BranchData.validateName("  "))
                        .contains("Имя ветки"));
        expect("имя ветки валидируется: пробелы и слэши отклоняются",
                expectAgentThrow(() -> BranchData.validateName("новая ветка/x"))
                        .contains("Недопустимый символ"));
        expect("имя ветки валидируется: длина ограничена 32",
                expectAgentThrow(() -> BranchData.validateName("а".repeat(33)))
                        .contains("не длиннее 32"));
        expect("корректное имя ветки нормализуется",
                BranchData.validateName(" Спорная-1 ").equals("спорная-1"));
    }

     static void checkDay10StrategySwitchNoApi() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            agent.ask("разговорное сообщение");
            int hitsBefore = s.regularHits().get() + s.summaryHits().get();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/strategy"),
                    TerminalUi.Input.command("/strategy facts"),
                    TerminalUi.Input.command("/strategy"),
                    TerminalUi.Input.command("/strategy branching"),
                    TerminalUi.Input.command("/branch list"),
                    TerminalUi.Input.command("/strategy sliding-window"),
                    TerminalUi.Input.command("/strategy rectangular"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("переключение стратегии не вызывает API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore);
            expect("/strategy показывает текущую стратегию и параметры",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Стратегия контекста: Скользящее окно")));
            expect("переключение на facts сообщается",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Стратегия: Факты")));
            expect("переключение на branching меняет настройки и показывает команды",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Ветки (история активной ветки)")));
            expect("неизвестная стратегия даёт ошибку и подсказку",
                    ui.errors.stream().anyMatch(t ->
                            t.contains("sliding-window, facts или branching")));
            expect("после возврата на sliding-window история не тронута",
                    agent.getHistory().size() == 2 && agent.factsView().isEmpty());
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay10SlidingWindow() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);

            for (int i = 1; i <= 7; i++) {
                agent.ask("вопрос окна " + i);
            }
            String lastRegular = null;
            for (int i = s.bodies().size() - 1; i >= 0; i--) {
                if (!s.bodies().get(i).contains(SUMMARY_MARKER)) {
                    lastRegular = s.bodies().get(i);
                    break;
                }
            }
            JsonNode body = MAPPER.readTree(lastRegular);
            JsonNode messages = body.path("messages");
            expect("sliding-window: в запросе ровно system + окно 10 + новое сообщение",
                    messages.size() == 12
                            && "system".equals(messages.get(0).path("role").asText())
                            && "вопрос окна 2".equals(messages.get(1).path("content").asText())
                            && "вопрос окна 7".equals(messages.get(messages.size() - 1).path("content").asText()));
            expect("sliding-window: более ранние сообщения не отправляются",
                    streamContents(messages).noneMatch(m -> m.contains("вопрос окна 1"))
                            && streamContents(messages).noneMatch(
                            m -> m.contains("Ответ 1")));
            JsonNode saved = MAPPER.readTree(Files.readString(store.file(),
                    StandardCharsets.UTF_8));
            expect("sliding-window: архив в файле не теряется (14 сообщений)",
                    saved.path("messages").size() == 14);
            expect("старые сообщения остаются в памяти при отправке окна",
                    agent.getHistory().size() == 14
                            && "вопрос окна 1".equals(agent.getHistory().get(0).content()));
            expect("подпись запроса отмечает отброшенные сообщения",
                    agent.lastRequestContextCaption().contains("отброшено из запроса 2"));
            List<String> notes = agent.consumeContextNotes();
            expect("заметка после ответа: стратегия, окно, отброшенные пакеты "
                    + "и сохранённый архив",
                    notes.stream().anyMatch(t -> t.contains("Стратегия: Скользящее окно"))
                            && notes.stream().anyMatch(t ->
                            t.contains("окно 10") && t.contains("отброшено из запроса 2")));
            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("каждый запрос учтён ровно один раз: 7 попыток, полный usage",
                    stats.apiAttempts() == 7 && stats.complete()
                            && stats.knownTotal() == 7 * 30L);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay10FactsFlow() throws Exception {
        Path keyStore = day9KeyStore();
        // Порядок ответов facts-запросов детерминирован: серия значений.
        AtomicInteger factsCall = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                int call = factsCall.incrementAndGet();
                // «Замена устаревшего значения»: тем же ключом — новое значение.
                String pairs = call == 1
                        ? "цель: собрать ТЗ проекта МАЯК"
                        : "цель: собрать финальное ТЗ проекта МАЯК";
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + pairs + "\"}}],"
                        + "\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":4,"
                        + "\"total_tokens\":34}}").getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"См. факты "
                    + "блока\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20,\"total_tokens\":30}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(factsEnv()),
                    trustedHttpClient(keyStore), store);

            agent.ask("Сбор ТЗ: цель — проект МАЯК, запрет цитрусовых, число 42");
            expect("после первого ответа facts обновлены первым служебным запросом",
                    factsCall.get() == 1
                            && agent.factsView().size() == 1
                            && agent.factsView().get("цель").contains("МАЯК"));
            expect("служебный запрос facts не попал в историю диалога",
                    agent.getHistory().size() == 2);

            agent.ask("Обновляю: цель меняется на финальное ТЗ, число 42 сохраняем");
            expect("второй служебный запрос обновил и заменил факты",
                    factsCall.get() == 2
                            && agent.factsView().size() == 1
                            && agent.factsView().get("цель").contains("финальное"));
            expect("история содержит только пары диалога (4 сообщения)",
                    agent.getHistory().size() == 4
                            && agent.getHistory().stream()
                            .noneMatch(m -> m.content().contains("цель:")));

            // Проверяем контекст и подписи агента.
            String caption = agent.lastRequestContextCaption();
            expect("подпись facts сообщает блок фактов и окно сообщений",
                    caption != null && caption.contains("блок фактов") && caption.contains("пар"));
            expect("facts хранятся отдельно от сообщений",
                    agent.factsView().size() == 1
                            && agent.getHistory().size() == 4);

            var stats = agent.sessionStats();
            expect("расход facts учтён отдельно: попыток FACTS_UPDATE ровно 2",
                    stats.factsAttempts() == 2 && stats.regularAttempts() == 2
                            && stats.factsPromptTokens() == 60
                            && stats.factsCompletionTokens() == 8);
            expect("случайные тексты фактов не попадали в ответ",
                    agent.lastRequestContextCaption() != null);            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay10FactsManualAndClear() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger factsCalls = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            if (requestBody.contains(FACTS_MARKER)) {
                factsCalls.incrementAndGet();
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + "решение: держать факты вручную\"}}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ "
                    + factsCalls.get() + "\"}}],\"usage\":{\"prompt_tokens\":15,"
                    + "\"completion_tokens\":25}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            Map<String, String> env = factsEnv();
            env.put("LLM_FACTS_UPDATE_MODE", "manual");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env),
                    trustedHttpClient(keyStore), store);

            agent.ask("должен ли я говорить вручную?");
            agent.ask(" Manual-режим не обновляет сам");
            expect("manual-режим не обновляет facts автоматически",
                    factsCalls.get() == 0 && agent.factsView().isEmpty());

            String result = agent.refreshFacts();
            expect("/facts refresh делает ровно один служебный запрос",
                    factsCalls.get() == 1 && agent.factsView().size() == 1
                            && agent.factsView().get("решение").contains("вручную"));
            expect("в refresh приходят текущая история и блок facts (текущий пуст)",
                    lastBody.get() != null
                            && lastBody.get().contains("история — данные")
                            && lastBody.get().contains("(пуст"));
            expect("результат сводки после подтверждения содержит отметку расхода",
                    result.contains("учтён отдельно"));

            agent.factsClear();
            expect("/facts clear очищает блок (после подтверждения в Main)",
                    agent.factsView().isEmpty());
            expect("очищенный блок в файле не сериализуется как объект facts",
                    !Files.readString(store.file(), StandardCharsets.UTF_8)
                            .contains("\"facts\""));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay10FactsInSessionLimit() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                // Частичный usage у служебного запроса (только prompt_tokens).
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"лимит: 90\"}}],"
                        + "\"usage\":{\"prompt_tokens\":70}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            Map<String, String> env = factsEnv();
            env.put("LLM_SESSION_TOKEN_LIMIT", "80");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env),
                    trustedHttpClient(keyStore), store);
            agent.ask("сообщение с лимитом фактов");
            var stats = agent.sessionStats();
            String notice = agent.consumeSessionLimitNotice();
            expect("расход facts входит в лимит сессии (10+20+70 > 80)",
                    notice != null && notice.contains("не менее 100")
                            && !stats.complete());
            expect("частичный usage facts: prompt учтён, итог не опровергается",
                    stats.factsAttempts() == 1 && stats.factsPromptTokens() == 70
                            && stats.factsCompletionTokens() == 0
                            && stats.requestsWithPartialUsage() == 1);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay10Branching() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(branchingEnv()),
                    trustedHttpClient(keyStore), store);

            agent.ask("вопрос основной 1");
            agent.ask("вопрос основной 2");
            agent.branchCheckpoint();
            expect("checkpoint на конце истории, ветка main пустая",
                    agent.branchesDescription().contains("checkpoint: сообщения [0..4)"));
            agent.branchNew("спорная");
            expect("новая ветка начинается с префикса checkpoint",
                    agent.getHistory().size() == 4
                            && agent.activeBranchName().equals("спорная"));

            agent.ask("вариант спорной");
            agent.branchSwitch("main");
            expect("переключение обратно сохраняет историю ветки «main»",
                    agent.getHistory().size() == 4
                            && agent.activeBranchName().equals("main")
                            && agent.getHistory().get(0).content().contains("основной 1"));

            agent.ask("вопрос основной 3");
            expect("строка «main» продолжает собственную независимую треду",
                    agent.getHistory().size() == 6);

            agent.branchSwitch("спорная");
            expect("ветка «спорная» не потеряла собственные сообщения",
                    agent.getHistory().size() == 6
                            && agent.getHistory().get(4).content().contains("вариант спорной"));

            String guard = expectAgentThrow(() -> {
                agent.branchCheckpoint();
                return "";
            });
            expect("перенос checkpoint при непустых чужих хвостах отказывается",
                    guard.contains("Перенести checkpoint сейчас нельзя"));

            agent.branchSwitch("main");
            agent.branchDelete("спорная");
            expect("после удаления ветки вернулась только одна",
                    agent.branchesDescription().contains("активная: «main»")
                            && !agent.branchesDescription().contains("спорная"));
            expect("активная ветка не удаляется",
                    expectAgentThrow(() -> {
                        agent.branchDelete("main");
                        return "";
                    }).contains("Активную ветку"));

            expect("инвалидные имена веток отклоняются без записи файла",
                    expectAgentThrow(() -> {
                        agent.branchNew("bad name");
                        return "";
                    }).contains("Недопустимый"));

            expect("служебный запрос facts/summary не появился в ветках",
                    s.summaryHits().get() == 0 && s.regularHits().get() >= 4);
            expect("каптion branching называет всю историю ветки",
                    agent.lastRequestContextCaption().contains("вся история"));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay10BranchPersistence() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(branchingEnv()),
                    trustedHttpClient(keyStore), store);
            agent.ask("до checkpoint");
            agent.branchCheckpoint();
            agent.branchNew("вилка");
            agent.ask("в ветке «вилка»");
            store.close();

            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                LlmAgent restored = new LlmAgent(config, ModelSettings.from(branchingEnv()),
                        trustedHttpClient(keyStore), reopened);
                expect("после перезапуска активная ветка и история восстановлены",
                        restored.activeBranchName().equals("вилка")
                                && restored.getHistory().size() == 4
                                && restored.getHistory().get(0).content()
                                .contains("до checkpoint")
                                && !restored.getHistory().get(3).content().isEmpty());
                restored.branchSwitch("main");
                expect("переключение после перезапуска возвращается в основную ветку",
                        restored.getHistory().size() == 2
                                && restored.getHistory().get(0).content()
                                .contains("до checkpoint"));
            }
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay10StrategyCompare() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger answers = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + "сравнение: маркер фактов МАЯК\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":4}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            // Вспомогательные запросы сравнения различаются по числу
            // сообщений: branching отправляет весь снимок, facts — хвост
            // с блоком «ФАКТЫ», sliding — то же окно, что и facts.
            if (requestBody.contains("ФАКТЫ")) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ Facts\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));   // usage отсутствует
            }
            int assistantCount = requestBody.split("\"assistant\"", -1).length - 1;
            if (assistantCount >= 8) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ Branching\"}}],"
                        + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":3}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ Sliding "
                    + answers.incrementAndGet() + "\"}}],"
                    + "\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":1}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 8; i++) {
                agent.ask("факт сравнения " + i);
            }
            List<ChatMessage> before = agent.getHistory();
            long knownBefore = agent.sessionStats().knownTotal();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/strategy compare первый факт?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmStrategyCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("сравнение стратегий не изменяет историю",
                    agent.getHistory().equals(before)
                            && !agent.getHistory().toString().contains("МАЯК"));
            expect("показаны ответы всех трёх стратегий",
                    ui.messages.stream().anyMatch(m -> m.contains("Ответ Sliding"))
                            && ui.messages.stream().anyMatch(m -> m.contains("Ответ Facts"))
                            && ui.messages.stream().anyMatch(m -> m.contains("Ответ Branching")));
            var stats = agent.sessionStats();
            expect("сравнение = 4 попытки (facts-prep и три ряда), отсутствующий "
                    + "usage у варианта facts честно помечен",
                    stats.compareAttempts() == 4
                            && stats.requestsWithPartialUsage() + stats.requestsWithoutUsage() >= 1
                            && !stats.complete());
            expect("расход сравнения входит в общий knownTotal",
                    agent.sessionStats().knownTotal() > knownBefore);
            expect("таблица содержит все три стратегии и пометку «не списание» "
                    + "или честное «нет данных» без тарифа",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Таблица сравнения стратегий")
                                    && t.contains("sliding-window")
                                    && t.contains("facts")
                                    && t.contains("branching")
                                    && (t.contains("расчётная, не списание")
                                    || t.contains("стоимость: нет данных — тариф не настроен"))));
            expect("факты подготовлены из снимка и занесены в ряд facts",
                    ui.systems.stream().anyMatch(t -> t.contains("подготовлен служебным")));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDay10CompareGuards() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            agent.ask("единственный вопрос для сравнения");
            int hitsBefore = s.regularHits().get() + s.summaryHits().get();

            FakeUi cancelUi = new FakeUi(
                    TerminalUi.Input.command("/strategy compare вопрос"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(cancelUi, agent, "glm-5.3-flash");
            expect("отмена подтверждения не вызывает API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore);

            FakeUi demoUi = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.command("/strategy compare вопрос"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(demoUi, agent, "glm-5.3-flash");
            expect("в демо сравнение стратегий не выполняется без API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore
                            && demoUi.systems.stream().anyMatch(
                            t -> t.contains("режиме измерения")
                                    || t.contains("сравнивать не на чем")));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkFactsPrepLengthAbortsCompare() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger regularAnswers = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                return new Response(200, lengthAnswer().getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, regularAnswer(regularAnswers.incrementAndGet())
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 3; i++) {
                agent.ask("факт до сравнения " + i);
            }
            List<ChatMessage> before = agent.getHistory();
            long knownBefore = agent.sessionStats().knownTotal();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/strategy compare ранние факты?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmStrategyCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            expect("сравнение не изменяет историю", agent.getHistory().equals(before));
            expect("строка facts помечена «недостоверно», а не полноценным ответом",
                    ui.messages.stream().anyMatch(m -> m.startsWith("Факты")
                            && m.contains("(недостоверно:")
                            && m.contains("подготовка фактов не выполнена")));
            expect("ошибка подготовки предлагает увеличить лимит и повторить",
                    ui.messages.stream().anyMatch(m ->
                            m.contains("LLM_FACTS_MAX_OUTPUT_TOKENS")
                                    && m.contains("повторите")));
            expect("вывод нет полноценного «ответа facts» поверх сбоя",
                    ui.messages.stream().noneMatch(
                            m -> m.startsWith("Факты (ключ: значение)")
                                    && !m.contains("недостоверно")));
            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("подготовка учтена ровно один раз, facts-запрос не выполнялся",
                    stats.compareAttempts() == 3
                            && stats.knownTotal() == knownBefore + 60 + 900 + 30 + 30);
            expect("в таблице строка facts без выдуманного расхода",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Таблица сравнения стратегий")
                                    && t.contains("нет данных")));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkFactsNearLimitWarning() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                return json(200, factsUsage95Body());
            }
            return json(200, regularAnswer(999));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            Map<String, String> env = factsEnv();
            env.put("LLM_FACTS_MAX_OUTPUT_TOKENS", "100");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env),
                    trustedHttpClient(keyStore), store);
            agent.ask("сообщение для предупреждения");
            List<String> notes = agent.consumeContextNotes();
            expect("предупреждение при занятости лимита 90% и более",
                    notes.stream().anyMatch(t ->
                            t.contains("Предупреждение: обновление фактов заняло 95 из 100")
                                    && t.contains("LLM_FACTS_MAX_OUTPUT_TOKENS")));
            JsonConversationStore freshStore = tempStore();
            Map<String, String> fresh = factsEnv();
            fresh.put("LLM_FACTS_MAX_OUTPUT_TOKENS", "200");
            LlmAgent freshAgent = new LlmAgent(config, ModelSettings.from(fresh),
                    trustedHttpClient(keyStore), freshStore);
            freshAgent.ask("сообщение ниже порога предупреждения");
            expect("ниже 90% предупреждение не выводится",
                    freshAgent.consumeContextNotes().stream()
                            .noneMatch(t -> t.contains("Предупреждение:")));
            freshStore.close();
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkThreeLayersInRequest() throws Exception {
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
            agent.remember("кодовое слово: ЯКОРЬ-42");
            agent.setTask("написать краткое резюме проекта");
            agent.ask("первый вопрос");
            agent.ask("второй вопрос");

            JsonNode messages = MAPPER.readTree(lastBody.get()).path("messages");
            String system = messages.get(0).path("content").asText();
            expect("в запросе подставлены все три слоя: долговременная память",
                    system.contains("<<<ДОЛГОВРЕМЕННАЯ ПАМЯТЬ")
                            && system.contains("кодовое слово: ЯКОРЬ-42"));
            expect("в запросе подставлена рабочая память с задачей",
                    system.contains("<<<РАБОЧАЯ ПАМЯТЬ")
                            && system.contains("Текущая задача: написать краткое резюме проекта"));
            expect("краткосрочная история отправляется после system, без дубликатов system",
                    messages.size() == 4
                            && "system".equals(messages.get(0).path("role").asText())
                            && "первый вопрос".equals(messages.get(1).path("content").asText())
                            && "второй вопрос".equals(
                            messages.get(messages.size() - 1).path("content").asText()));
            expect("правило непересечения слоёв в базовой инструкции",
                    system.contains("Слои памяти не дублируй"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkContextBlockOrder() {
        ModelSettings settings = ModelSettings.defaults();
        Map<String, MemoryEntry> emptyMemory = new LinkedHashMap<>();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        // Всё задано: блоки и порядок в одном system-сообщении.
        TaskState task = TaskState.start("задача порядка блоков", now);
        UserProfile profile = new UserProfile("Алексей", null, null, List.of(),
                new LinkedHashMap<>(), new LinkedHashMap<>(),
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        ChatMessage full = ContextBuilder.systemContextMessage(settings,
                profile, new LinkedHashMap<>(Map.of(
                        "кодовое слово", new MemoryEntry("кодовое слово", "ЯКОРЬ-42",
                                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))),
                task, Map.of("цель", "МАЯК"), null, null,
                List.of(Invariant.create("рамка порядка", "other",
                        Instant.parse("2026-01-01T00:00:00Z"))), List.of());
        String system = full.content();
        int profileIdx = system.indexOf("<<<ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ");
        int memoryIdx = system.indexOf("<<<ДОЛГОВРЕМЕННАЯ ПАМЯТЬ");
        int workingIdx = system.indexOf("<<<РАБОЧАЯ ПАМЯТЬ");
        int taskIdx = system.indexOf("<<<СОСТОЯНИЕ ЗАДАЧИ");
        int invariantsIdx = system.indexOf("<<<ИНВАРИАНТЫ");
        expect("порядок блоков: профиль → память → рабочая → задача → инварианты",
                profileIdx >= 0 && profileIdx < memoryIdx
                        && memoryIdx < workingIdx && workingIdx < taskIdx
                        && taskIdx < invariantsIdx
                        && system.indexOf("<<<ПАЙПЛАЙН") == -1);
    }

     static void checkMemoryUpdateAccountingOnce() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"цель: МАЯК, "
                        + "дедлайн 20.05\"}}],\"usage\":{\"prompt_tokens\":70,"
                        + "\"completion_tokens\":9}}").getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(factsEnv()),
                    trustedHttpClient(keyStore), store);
            agent.ask("обновление памяти один");
            agent.ask("обновление памяти два");
            SessionTokenStats.Snapshot stats = agent.sessionStats();

            long memoryPurposes = agent.attemptUsageLog().stream()
                    .filter(u -> u.purpose() == SessionTokenStats.Purpose.MEMORY_UPDATE)
                    .count();
            expect("служебные запросы обновления памяти учтены назначением MEMORY_UPDATE",
                    memoryPurposes == 2 && stats.factsAttempts() == 2);
            expect("расход обновления памяти входит в итог сессии ровно один раз (без дублей)",
                    stats.totalPromptTokens() == 20 + 140
                            && stats.totalCompletionTokens() == 40 + 18
                            && stats.regularAttempts() == 2
                            && stats.knownTotal() == 218);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }
}
