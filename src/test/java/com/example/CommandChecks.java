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

final class CommandChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkMemoryLayerSeparation();
        checkRememberKeyFormats();
        checkRememberForgetMemoryCommands();
        checkForgetPartialMatches();
        checkLegacyRememberEntries();
        checkMemoryPersistsAcrossRestarts();
        checkClearKeepsLongTermMemory();
        checkProfileStoreLifecycle();
        checkProfileSubcommandUi();
        checkProfileBlockInSystemMessage();
        checkProfileBlockAcrossRestartsAndClear();
        checkSkillsAndPipelines();
        checkPipelineSubstitutionInRequest();
        checkWorkingCounterMatchesFacts();
        checkModeCommand();
        checkSystemInstructionRules();
        checkInvariantsStoreLifecycle();
        checkInvariantCommands();
        checkInvariantBlockInSystemMessage();
        checkInvariantBlockInRealRequest();
        checkInvariantAddForbiddenMarkers();
        checkTaskInvariantCommands();
        checkHelpForEveryCommand();
        checkUnknownSubcommands();
    }

     static void checkModeCommand() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), client, store);
            agent.ask("вопрос перед /mode");
            byte[] fileBeforeMode = Files.readAllBytes(store.file());
            int hitsAfterAsk = hitCounter.get();

            FakeUi modeUi = new FakeUi(
                    TerminalUi.Input.command("/mode"),
                    TerminalUi.Input.command("/mode fast"),
                    TerminalUi.Input.command("/mode turbo"),
                    TerminalUi.Input.command("/history"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(modeUi, agent, "glm-5.3-flash");
            expect("команды /mode не вызывают API", hitCounter.get() == hitsAfterAsk);
            expect("/mode показывает текущий профиль и лимит",
                    modeUi.systems.stream().anyMatch(
                            s -> s.contains("Профиль: balanced · лимит генерации: 2048")));
            expect("профиль переключается на fast с лимитом 1024",
                    modeUi.systems.stream().anyMatch(
                            s -> s.contains("Профиль: fast · лимит генерации: 1024"))
                            && ModelSettings.FAST.equals(agent.currentSettings().profile())
                            && agent.currentSettings().maxOutputTokens() == 1024);
            expect("неизвестный профиль даёт понятную ошибку",
                    modeUi.errors.stream().anyMatch(s -> s.contains("Неизвестный профиль")));
            expect("/mode не трогает файл истории",
                    Arrays.equals(Files.readAllBytes(store.file()), fileBeforeMode));
            expect("/mode не очищает память",
                    agent.getHistory().size() == 2 && modeUi.historyCalls == 1);
            store.close();

            // Явный LLM_MAX_OUTPUT_TOKENS сохраняет приоритет при /mode.
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_MAX_OUTPUT_TOKENS", "300");
            JsonConversationStore overriddenStore = tempStore();
            LlmAgent overriddenAgent = new LlmAgent(
                    config, ModelSettings.from(env), client, overriddenStore);
            FakeUi overrideUi = new FakeUi(
                    TerminalUi.Input.command("/mode"),
                    TerminalUi.Input.command("/mode fast"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(overrideUi, overriddenAgent, "glm-5.3-flash");
            expect("приветствие /mode отмечает переопределение лимита окружением",
                    overrideUi.systems.stream().anyMatch(
                            s -> s.contains("лимит задан LLM_MAX_OUTPUT_TOKENS")));
            expect("при переключении профиля лимит из окружения сохраняется",
                    overriddenAgent.currentSettings().maxOutputTokens() == 300
                            && ModelSettings.FAST.equals(overriddenAgent.currentSettings().profile()));
            overriddenStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkProfileStoreLifecycle() throws IOException {
        Path profileFile = Files.createTempDirectory(baseTempDir, "prof-")
                .resolve("profile.json");
        ProfileStore profileStore = new ProfileStore(profileFile);

        // Отсутствующий файл — пустой профиль, файл заранее не создаётся.
        expect("отсутствующий файл профиля даёт пустой профиль",
                profileStore.load().isEmpty());
        expect("при отсутствии файла профиля JSON не создаётся заранее",
                !Files.exists(profileFile));

        // Поля сохраняются и читаются циклом «запись — чтение».
        ProfileStore ps = new ProfileStore(profileFile);
        ps.save(new UserProfile("Алексей", "кратко, по делу", "списками",
                List.of("не используй смайлики", "только русский"),
                new LinkedHashMap<>(), new LinkedHashMap<>(),
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"));
        UserProfile loaded = ps.load();
        expect("профиль хранит обращение, стиль, формат и ограничения",
                "Алексей".equals(loaded.name())
                        && "кратко, по делу".equals(loaded.style())
                        && "списками".equals(loaded.format())
                        && loaded.constraints().contains("не используй смайлики")
                        && loaded.constraints().contains("только русский"));
        expect("метки времени профиля сохраняются",
                "2026-01-01T00:00:00Z".equals(loaded.createdAt()));
        expect("файл профиля отдельный от памяти (profile.json)",
                profileFile.getFileName().toString().equals("profile.json"));

        // Повреждённый файл — ошибка повреждения, файл не переписывается.
        byte[] garbage = "это вообще не { json".getBytes(StandardCharsets.UTF_8);
        Files.write(profileFile, garbage);
        try {
            ps.load();
            expect("повреждённый файл профиля даёт ошибку", false);
        } catch (ConversationStoreException e) {
            expect("повреждённый файл профиля даёт ошибку с путём",
                    e.getMessage().contains(profileFile.toString()));
        }
        expect("повреждённый файл профиля не перезаписывается",
                Arrays.equals(Files.readAllBytes(profileFile), garbage));

        // Путь по умолчанию и переменная окружения.
        expect("профиль по умолчанию: ~/.ai-advent-agent/profile.json",
                ProfileStore.defaultProfileFile(Map.of()).getFileName().toString()
                        .equals("profile.json")
                        && ProfileStore.defaultProfileFile(Map.of()).getParent()
                        .getFileName().toString().equals(".ai-advent-agent"));
        expect("LLM_PROFILE_FILE переопределяет путь профиля",
                ProfileStore.defaultProfileFile(Map.of("LLM_PROFILE_FILE",
                        "/tmp/selftest-profile-fix.json"))
                        .equals(Path.of("/tmp/selftest-profile-fix.json")));
        expect("относительный путь LLM_PROFILE_FILE отклоняется",
                expectStoreError(() -> ProfileStore
                        .defaultProfileFile(Map.of("LLM_PROFILE_FILE", "relative/path.json")))
                        .contains("относительный"));

        // Запись скиллов и пайплайнов в файл и чтение обратно.
        var skill = ProfileSkill.create("карточка фичи",
                "название, цель, критерии приёмки, шаги", java.time.Instant.now());
        LinkedHashMap<String, ProfileSkill> skills = new LinkedHashMap<>();
        skills.put(skill.name(), skill);
        LinkedHashMap<String, List<String>> pipelines = new LinkedHashMap<>();
        pipelines.put("напиши фичу", List.of("карточка фичи"));
        ps.save(new UserProfile("Алексей", null, null, List.of(), skills, pipelines,
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"));
        UserProfile reloaded = ps.load();
        expect("скиллы и пайплайны профиля перезапускаются",
                reloaded.skills().containsKey("карточка фичи")
                        && reloaded.skills().get("карточка фичи").instructions()
                        .contains("критерии приёмки")
                        && reloaded.pipelines().get("напиши фичу")
                        .contains("карточка фичи"));

        // Проверки валидации: пустые метки времени недопустимы.
        String brokenJson = "{\"schemaVersion\":1,\"profile\":{\"name\":\"x\","
                + "\"constraints\":[],\"createdAt\":\"2026-01-01T00:00:00Z\"}}";
        Files.write(profileFile, brokenJson.getBytes(StandardCharsets.UTF_8));
        boolean rejected;
        try {
            ps.load();
            rejected = false;
        } catch (ConversationStoreException e) {
            rejected = true;
        }
        expect("профиль без updatedAt отклоняется", rejected);
    }

     static void checkProfileBlockInSystemMessage() {
        Map<String, MemoryEntry> emptyMemory = new LinkedHashMap<>();
        ModelSettings settings = ModelSettings.defaults();

        ChatMessage emptySystem = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, null, Map.of(), null, null);
        expect("пустой профиль не подставляется в system-сообщение",
                !emptySystem.content().contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ"));

        ChatMessage fullSystem = ContextBuilder.systemContextMessage(settings,
                new UserProfile("Алексей", "кратко, по делу", "списками",
                        List.of("не используй смайлики"),
                        new LinkedHashMap<>(), new LinkedHashMap<>(),
                        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                emptyMemory, null, Map.of(), null, null);
        String block = fullSystem.content();
        expect("заданный профиль подставлен: заголовок блока",
                block.contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ"));
        expect("блок профиля содержит обращение",
                block.contains("Алексей"));
        expect("блок профиля содержит стиль",
                block.contains("кратко, по делу"));
        expect("блок профиля содержит формат",
                block.contains("списками"));
        expect("блок профиля содержит ограничение",
                block.contains("не используй смайлики"));
        expect("блок профиля формулируется как инструкции",
                ContextBuilder.renderProfile(new UserProfile("Алексей", null, null,
                        List.of(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))
                        .contains("называй пользователя"));
        expect("правило приоритета конкретного сообщения над профилем описано",
                block.contains("следуй его сообщению"));

        ChatMessage otherSystem = ContextBuilder.systemContextMessage(settings,
                new UserProfile("Шеф", "подробно", null, List.of(),
                        new LinkedHashMap<>(), new LinkedHashMap<>(),
                        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                emptyMemory, null, Map.of(), null, null);
        expect("разные профили дают разный текст system-сообщения",
                !otherSystem.content().equals(fullSystem.content())
                        && otherSystem.content().contains("Шеф")
                        && otherSystem.content().contains("подробно"));
    }

     static void checkProfileBlockAcrossRestartsAndClear() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            ProfileStore profileStore = new ProfileStore(
                    Files.createTempDirectory(baseTempDir, "prof-").resolve("profile.json"));
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(),
                    new MemoryStore(Files.createTempDirectory(baseTempDir, "mem-")
                            .resolve("memory.json")), profileStore);

            // Команды профиля не вызывают API и задают поля.
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/profile"),
                    TerminalUi.Input.command("/profile name Алексей"),
                    TerminalUi.Input.command("/profile style кратко, по делу"),
                    TerminalUi.Input.command("/profile format списками"),
                    TerminalUi.Input.command("/profile constraint не используй смайлики"),
                    TerminalUi.Input.command("/profile constraint clear"),
                    TerminalUi.Input.command("/profile constraint только русский"),
                    TerminalUi.Input.command("/profile"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/profile показывает заданные поля",
                    ui.systems.stream().anyMatch(t -> t.contains("Алексей")
                            && t.contains("кратко, по делу") && t.contains("списками")));
            expect("/profile помечает незаданные поля «не задано»",
                    ui.systems.stream().anyMatch(t -> t.contains("не задано")));
            expect("подтверждения команды в едином стиле",
                    ui.systems.stream().anyMatch(t -> t.contains("Профиль обновлён: name"))
                            && ui.systems.stream().anyMatch(t ->
                            t.contains("Профиль обновлён: constraint")));
            expect("пустые поля устанавливаются без API", agent.userProfile().name() != null);

            agent.ask("привет");
            String system = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("в запросе есть блок ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ",
                    system.contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ"));
            expect("в блоке профиля есть обращение и стиль",
                    system.contains("Алексей") && system.contains("кратко, по делу"));
            expect("очищенное ограничение не подставляется",
                    !system.contains("не используй смайлики"));
            expect("оставшееся ограничение подставляется",
                    system.contains("только русский"));

            // Перезапуск: новый агент с тем же файлом профиля видит поля.
            LlmAgent restarted = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(),
                    new MemoryStore(Files.createTempDirectory(baseTempDir, "mem-")
                            .resolve("memory.json")), profileStore);
            expect("профиль переживает перезапуск",
                    "Алексей".equals(restarted.userProfile().name()));

            // /profile clear убирает блок после подтверждения.
            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/profile clear"),
                    TerminalUi.Input.command("/exit"));
            clearUi.confirmProfileClearAnswer = true;
            Main.runLoop(clearUi, restarted, "glm-5.3-flash");
            expect("/profile clear сбрасывает профиль (подтверждение работает)",
                    restarted.userProfile().isEmpty());

            restarted.ask("вопрос после сброса");
            String clearedSystem = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("после /profile clear блока профиля в запросе нет",
                    !clearedSystem.contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkProfileSubcommandUi() throws IOException {
        // Тонкая проверка без API: отказ подтверждения сохраняет профиль.
        FakeUi ui = new FakeUi(TerminalUi.Input.command("/profile clear"));
        ui.confirmProfileClearAnswer = false;
        ProfileStore profileStore = new ProfileStore(
                Files.createTempDirectory(baseTempDir, "prof-").resolve("profile.json"));
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(
                        Files.createTempDirectory(baseTempDir, "hist-")
                                .resolve("conversation.json")),
                tempMemoryStore(), profileStore);
        agent.setProfileName("Шеф");
        Main.runLoop(ui, agent, "test-model");
        expect("отказ подтверждения сохраняет профиль",
                "Шеф".equals(agent.userProfile().name()));
    }

     static void checkSkillsAndPipelines() throws Exception {
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
                    TerminalUi.Input.command("/skill add \"карточка фичи\" название, цель, "
                            + "критерии приёмки, шаги"),
                    TerminalUi.Input.command("/skill add \"сборка корзины\" собери товары, "
                            + "сгруппируй, посчитай сумму"),
                    TerminalUi.Input.command("/skill list"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/skill add сохраняет скиллы",
                    agent.skillsView().containsKey("карточка фичи")
                            && agent.skillsView().containsKey("сборка корзины"));
            expect("/skill list показывает скиллы",
                    ui.systems.stream().anyMatch(t -> t.contains("карточка фичи")
                            && t.contains("критерии приёмки")));

            // Пайплайн задан командой; /pipeline list показывает.
            FakeUi pipelineUi = new FakeUi(
                    TerminalUi.Input.command("/pipeline \"напиши фичу\" "
                            + "\"карточка фичи\""),
                    TerminalUi.Input.command("/pipeline list"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(pipelineUi, agent, "glm-5.3-flash");
            expect("/pipeline задаёт триггер и порядок скиллов",
                    agent.userProfile().pipelinesView().get("напиши фичу")
                            .contains("карточка фичи"));
            expect("/pipeline list показывает пайплайн",
                    pipelineUi.systems.stream().anyMatch(t ->
                            t.contains("«напиши фичу»")));

            // Unknown skill отклоняется с понятной ошибкой.
            FakeUi unknownSkill = new FakeUi(
                    TerminalUi.Input.command("/pipeline \"оплата\" неизвестный"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(unknownSkill, agent, "glm-5.3-flash");
            expect("пайплайн с неизвестным скиллом отклоняется",
                    unknownSkill.errors.stream().anyMatch(t ->
                            t.contains("не найден")));

            // Удаление скилла вычищает и пайплайн.
            FakeUi removeUi = new FakeUi(
                    TerminalUi.Input.command("/skill remove карточка фичи"),
                    TerminalUi.Input.command("/skill list"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(removeUi, agent, "glm-5.3-flash");
            expect("/skill remove удаляет скилл",
                    !agent.skillsView().containsKey("карточка фичи"));
            expect("удалённый скилл вычищен из пайплайнов",
                    agent.userProfile().pipelinesView().containsKey("напиши фичу")
                            == false);

            // /pipeline clear требует подтверждения и чистит только пайплайны.
            FakeUi clearPipelineUi = new FakeUi(
                    TerminalUi.Input.command("/pipeline clear"),
                    TerminalUi.Input.command("/pipeline list"),
                    TerminalUi.Input.command("/exit"));
            clearPipelineUi.confirmProfileClearAnswer = true;
            Main.runLoop(clearPipelineUi, agent, "glm-5.3-flash");
            expect("/pipeline clear очищает пайплайны",
                    agent.userProfile().pipelinesView().isEmpty());
            expect("/pipeline clear сохраняет скиллы",
                    agent.skillsView().containsKey("сборка корзины"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkPipelineSubstitutionInRequest() throws Exception {
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
            agent.skillAdd("карточка фичи", "название, цель, критерии приёмки, шаги");
            agent.skillAdd("критерии", "три критерия приёмки с примерами");
            agent.setPipeline("напиши фичу", List.of("карточка фичи", "критерии"));

            // Совпадение триггера — ключевые слова присутствуют в запросе.
            List<ProfileSkill> matched = ContextBuilder.matchedPipeline(
                    agent.userProfile(), "пожалуйста, напиши фичу входа");
            expect("триггер распознаётся по ключевым словам среди текста запроса",
                    matched.size() == 2
                            && matched.get(0).name().equals("карточка фичи")
                            && matched.get(1).name().equals("критерии"));

            // Нет совпадения — пайплайн не подставляется.
            expect("несовпадающий запрос не активирует пайплайн",
                    ContextBuilder.matchedPipeline(agent.userProfile(), "как дела")
                            .isEmpty());

            agent.ask("напиши фичу входа");
            String system = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("в запросе есть блок ПАЙПЛАЙН",
                    system.contains("ПАЙПЛАЙН"));
            expect("пайплайн содержит порядок и инструкции скиллов",
                    system.contains("карточка фичи") && system.contains("критерии")
                            && system.contains("Порядок применения"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkRememberKeyFormats() {
        LlmAgent.MemoryKey explicit = LlmAgent.deriveMemoryKey("проект: ЯКОРЬ-42, дедлайн 20.05");
        expect("/remember с «:» даёт короткий ключ и полное значение",
                explicit.key().equals("проект")
                        && explicit.value().equals("ЯКОРЬ-42, дедлайн 20.05"));

        LlmAgent.MemoryKey equalsForm = LlmAgent.deriveMemoryKey("запрет= без Spring");
        expect("/remember с «=» тоже разбирается на ключ и значение",
                equalsForm.key().equals("запрет") && equalsForm.value().equals("без Spring"));

        // Реальный случай прогона: ключом раньше становился весь текст,
        // и /forget Проект не находил запись.
        LlmAgent.MemoryKey comma = LlmAgent.deriveMemoryKey(
                "Проект \"Север-17\", Java: 21, бюджет 620 рублей, срок 20 ноября");
        expect("/remember без явного «ключ:» выделяет ключ по запятой",
                comma.key().equals("Проект \"Север-17\"")
                        && comma.value().equals("Java: 21, бюджет 620 рублей, срок 20 ноября"));

        LlmAgent.MemoryKey plain = LlmAgent.deriveMemoryKey("кодовое слово ЯКОРЬ");
        expect("без разделителя и запятой ключ — первое слово записи",
                plain.key().equals("кодовое")
                        && plain.value().equals("слово ЯКОРЬ"));

        LlmAgent.MemoryKey words = LlmAgent.deriveMemoryKey(
                "экспериментальная настройка повышает информативность ответов модели");
        expect("длинный текст без разделителя: ключ — первое слово, значение — остаток",
                words.key().equals("экспериментальная")
                        && words.value().equals(
                        "настройка повышает информативность ответов модели"));
    }

     static void checkForgetPartialMatches() throws Exception {
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
            agent.remember("Проект: \"Север-17\"");
            agent.remember("Производительность: 95 кв. м");
            agent.remember("Запреты: цитрусовые в тизерах");

            // Короткий ключ находит и удаляет запись (сценарий прогона).
            LlmAgent.ForgetResult shortKey = agent.forget("Проект");
            expect("/forget по короткому ключу находит запись",
                    shortKey.removed() && !agent.memoryView().containsKey("Проект"));

            // Различие регистра/пробелов не мешает точному совпадению.
            agent.remember("Бюджет: 620 рублей");
            LlmAgent.ForgetResult caseKey = agent.forget(" бюджет ");
            expect("/forget работает без учёта регистра и лишних пробелов",
                    caseKey.removed() && !agent.memoryView().containsKey("Бюджет"));

            // Несколько совпадений: список ключей, удаление не угадыванием.
            agent.remember("срок: 20 ноября");
            agent.remember("срок сдачи: 25 ноября");
            // Запрос «сро» — не точный ключ, но подстрока обоих записей.
            LlmAgent.ForgetResult ambiguous = agent.forget("сро");
            expect("неоднозначный /forget перечисляет ключи и просит уточнить",
                    !ambiguous.removed() && ambiguous.matches() == 2
                            && ambiguous.message().contains("уточните"));
            expect("при неоднозначности ни одна запись не удалена",
                    agent.memoryView().containsKey("срок")
                            && agent.memoryView().containsKey("срок сдачи"));

            // Частичное совпадение с единственным кандидатом удаляется:
            // точный ключ «срок сдачи» удалён выше, остаётся одна запись «срок».
            LlmAgent.ForgetResult exactSecond = agent.forget("срок сдачи");
            expect("точный ключ второй записи удаляется",
                    exactSecond.removed());
            LlmAgent.ForgetResult partial = agent.forget("сро");
            expect("частичное совпадение с единственной записью удаляется",
                    partial.removed()
                            && !agent.memoryView().containsKey("срок")
                            && partial.message().contains("частичн"));

            LlmAgent.ForgetResult missing = agent.forget("несуществующе");
            expect("отсутствующий ключ даёт честное «нет записи»",
                    !missing.removed() && missing.message().contains("не найдена"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkLegacyRememberEntries() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "mem-")
                .resolve("memory.json");
        String legacyJson = "{\"schemaVersion\":1,\"entries\":{"
                + "\"Запрещены Spring и базы данных, хранение в JSON\":{"
                + "\"value\":\"Запрещены Spring и базы данных, хранение в JSON\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}}}";
        Files.write(file, legacyJson.getBytes(StandardCharsets.UTF_8));
        MemoryStore memory = new MemoryStore(file);
        LinkedHashMap<String, MemoryEntry> entries = memory.load();
        expect("старая запись без явного ключа читается без потерь",
                entries.get("Запрещены Spring и базы данных, хранение в JSON") != null
                        && entries.get("Запрещены Spring и базы данных, хранение в JSON")
                        .value().contains("хранение в JSON"));
        expect("старая запись отображается как есть, без дублирования «ключ: значение»",
                entries.values().iterator().next().renderLine()
                        .equals("Запрещены Spring и базы данных, хранение в JSON"));

        // Явная запись рядом со старой: формат «ключ: значение» в отображении.
        memory.put("код", "ЯКОРЬ-42");
        var merged = memory.load();
        expect("обе формы работают: старая как есть, новая с ключом",
                merged.get("код").renderLine().equals("код: ЯКОРЬ-42")
                        && merged.get("Запрещены Spring и базы данных, хранение в JSON")
                        .legacyWithoutKey());
    }

     static void checkSystemInstructionRules() {
        String prompt = ContextBuilder.BASE_SYSTEM_PROMPT;
        expect("инструкция: долговременная память — независимые записи",
                prompt.contains("независимых записей"));
        expect("инструкция: не объединять записи в один перечень",
                prompt.contains("не объединяй"));
        expect("инструкция: запрет выводов сверх текста записи",
                prompt.contains("не делай выводов, которых нет"));
        expect("инструкция: двусмысленная запись — переспросить/пометить",
                prompt.contains("переспроси") && prompt.contains("неоднозначную"));
        expect("инструкция: правило честности по обновлению рабочей памяти",
                prompt.contains("не заявляй")
                        && prompt.contains("/facts refresh"));
    }

     static void checkWorkingCounterMatchesFacts() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger factsCalls = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            if (requestBody.contains(FACTS_MARKER)) {
                int call = factsCalls.incrementAndGet();
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + (call == 1 ? "цель: МАЯК" : "цель: финальное ТЗ")
                        + "\"}}],\"usage\":{\"prompt_tokens\":10,"
                        + "\"completion_tokens\":2}}").getBytes(StandardCharsets.UTF_8));
            }
            // Модель заявляет добавление в рабочую память — подпись должна
            // показать фактическое состояние блока фактов.
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок (Добавлено в рабочую память: цель МАЯК)\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // В стратегии facts: автообновление делает факты реальными.
            JsonConversationStore factsStore = tempStore();
            LlmAgent factsAgent = new LlmAgent(config, ModelSettings.from(factsEnv()),
                    client, factsStore);
            factsAgent.ask("первое сообщение");
            expect("в стратегии facts счётчик подписи равен числу пар фактов",
                    Main.formatShortAnswerNote(factsAgent).contains("рабочая 1 пара")
                            && factsAgent.factsView().size() == 1);
            FakeUi factsCheck = new FakeUi(
                    TerminalUi.Input.command("/facts"), TerminalUi.Input.command("/exit"));
            Main.runLoop(factsCheck, factsAgent, "glm-5.3-flash");
            expect("подпись совпадает с /facts после автообновления",
                    factsAgent.factsView().get("цель").contains("МАЯК"));

            // В стратегии sliding-window: факты не обновляются служебным
            // запросом — «заявление» модели против фактического 0 пар.
            JsonConversationStore windowStore = tempStore();
            LlmAgent windowAgent = new LlmAgent(config, ModelSettings.defaults(),
                    client, windowStore);
            windowAgent.ask("сообщение в скользящем окне");
            expect("в sliding-window факты не обновляются и счётчик показывает 0",
                    factsCalls.get() == 1
                            && windowAgent.factsView().isEmpty()
                            && Main.formatShortAnswerNote(windowAgent)
                            .contains("рабочая 0 пар"));
            expect("системная инструкция содержит правило честности по рабочей памяти",
                    lastBody.get() != null
                            && lastBody.get().contains("не заявляй"));
            windowStore.close();
            factsStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkMemoryLayerSeparation() throws IOException {        Path memoryFile = Files.createTempDirectory(baseTempDir, "mem-")
                .resolve("memory.json");
        MemoryStore memory = new MemoryStore(memoryFile);

        // Отсутствующий файл — пустая память, файл заранее не создаётся.
        expect("отсутствующий файл памяти даёт пустую память", memory.load().isEmpty());
        expect("при отсутствии файла памяти JSON не создаётся заранее",
                !Files.exists(memoryFile));

        MemoryEntry entry = memory.put("кодовое слово", "ЯКОРЬ-42").get("кодовое слово");
        expect("запись памяти хранит значение и метки времени",
                "ЯКОРЬ-42".equals(entry.value())
                        && !entry.createdAt().isBlank() && !entry.updatedAt().isBlank());
        expect("файл памяти отдельный от истории (memory.json)",
                memoryFile.getFileName().toString().equals("memory.json"));

        // Повторная запись того же ключа — обновление значения, createdAt сохранён.
        String createdAt = entry.createdAt();
        memory.put("кодовое слово", "ЯКОРЬ-43");
        var reopened = memory.load();
        expect("повторная запись обновляет значение, createdAt сохраняется",
                reopened.get("кодовое слово").value().equals("ЯКОРЬ-43")
                        && reopened.get("кодовое слово").createdAt().equals(createdAt));

        // Содержимое JSON: отдельная сущность «entries», а не поле истории.
        JsonNode saved = MAPPER.readTree(
                Files.readString(memoryFile, StandardCharsets.UTF_8));
        expect("файл памяти имеет собственный формат (schemaVersion 1 и entries)",
                saved.path("schemaVersion").asInt(-1)
                        == MemoryStore.SUPPORTED_SCHEMA_VERSION
                        && saved.path("entries").path("кодовое слово")
                        .path("value").asText().equals("ЯКОРЬ-43"));

        // Удаление записи.
        expect("remove удаляет существующую запись", memory.remove("кодовое слово"));
        expect("remove не удаляет несуществующую запись", !memory.remove("нет"));

        // Повреждённый файл — ошибка повреждения, файл не переписывается.
        byte[] garbage = "это вообще не { json".getBytes(StandardCharsets.UTF_8);
        Files.write(memoryFile, garbage);
        try (var ignored = new java.io.ByteArrayOutputStream()) {
            boolean reported;
            try {
                memory.load();
                reported = false;
            } catch (ConversationStoreException e) {
                reported = e.getMessage().contains(memoryFile.toString());
            }
            expect("повреждённый файл памяти даёт ошибку с путём", reported);
            expect("повреждённый файл памяти не перезаписывается",
                    Arrays.equals(Files.readAllBytes(memoryFile), garbage));
        }
    }

     static void checkRememberForgetMemoryCommands() throws Exception {
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
            int hitsBefore = hitCounter.get();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/remember проект: ЯКОРЬ-42, дедлайн 20.05"),
                    TerminalUi.Input.command("/remember без ключа вообще"),
                    TerminalUi.Input.command("/memory"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("команды памяти не вызывают API", hitCounter.get() == hitsBefore);

            expect("/remember «ключ: значение» сохраняет пару в долговременную память",
                    agent.memoryView().get("проект") != null
                            && agent.memoryView().get("проект").value()
                            .equals("ЯКОРЬ-42, дедлайн 20.05"));
            expect("/remember текста без разделителя: ключ — первое слово",
                    agent.memoryView().get("без") != null
                            && agent.memoryView().get("без").value()
                            .equals("ключа вообще"));
            expect("/memory показывает записи в виде «ключ: значение»",
                    ui.systems.stream().anyMatch(t -> t.contains("Долговременная память")
                            && t.contains("проект: ЯКОРЬ-42, дедлайн 20.05")));

            // Второй прогон того же агента: удаление и повторная попытка.
            FakeUi forgetUi = new FakeUi(
                    TerminalUi.Input.command("/forget проект"),
                    TerminalUi.Input.command("/memory"),
                    TerminalUi.Input.command("/forget проект"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(forgetUi, agent, "glm-5.3-flash");
            expect("/forget удаляет запись долговременной памяти",
                    agent.memoryView().get("проект") == null);
            expect("повторный /forget сообщает об отсутствии записи",
                    forgetUi.errors.stream().anyMatch(t ->
                            t.contains("Запись не найдена")));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkMemoryPersistsAcrossRestarts() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore history1 = tempStore();
            MemoryStore memory = new MemoryStore(
                    Files.createTempDirectory(baseTempDir, "mem-").resolve("memory.json"));
            LlmAgent first = new LlmAgent(config, ModelSettings.defaults(),
                    client, history1, memory);
            first.remember("профиль: отвечает кратко, формат таблицы");
            expect("первый экземпляр сохранил запись",
                    first.memoryView().get("профиль") != null);

            // Новый «запуск»: новый агент и новое хранилище истории,
            // тот же файл долговременной памяти.
            JsonConversationStore history2 = tempStore();
            LlmAgent second = new LlmAgent(config, ModelSettings.defaults(),
                    client, history2, memory);
            expect("новый экземпляр агента видит долговременную память",
                    second.memoryView().get("профиль") != null
                            && second.memoryView().get("профиль").value()
                            .contains("отвечает кратко"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkClearKeepsLongTermMemory() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}],\"usage\":{\"prompt_tokens\":10,"
                        + "\"completion_tokens\":20}}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            agent.ask("вопрос перед очисткой");
            agent.remember("кодовое слово: ЯКОРЬ-42");
            agent.setTask("подготовить отчёт");

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/clear"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmClearAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            expect("/clear очищает краткосрочную память (историю)",
                    agent.getHistory().isEmpty());
            expect("/clear очищает рабочую память (задачу и факты)",
                    agent.currentTask() == null && agent.factsView().isEmpty());
            expect("/clear НЕ стирает долговременную память",
                    agent.memoryView().get("кодовое слово") != null);
            expect("сообщение /clear сообщает о сохранении долговременной памяти",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Долговременная память сохранена")));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkInvariantsStoreLifecycle() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "inv-")
                .resolve("invariants.json");
        InvariantStore store = new InvariantStore(file);

        expect("отсутствующий файл инвариантов даёт пустой список", store.list().isEmpty());
        expect("при отсутствии файла инвариантов JSON не создаётся заранее",
                !Files.exists(file));

        Invariant first = store.add("проект «Север-17» — модульный монолит на Java 21, "
                + "без Spring и БД", "architecture");
        Invariant second = store.add("только стандартная библиотека и Jackson", null);
        expect("инвариант хранит текст, категорию и метку времени",
                first.text().startsWith("проект «Север-17»") && first.category() != null
                        && first.createdAt() != null);
        expect("категория по умолчанию — other",
                "other".equals(second.category()));
        expect("идентификаторы инвариантов уникальны и не пусты",
                !first.id().isBlank() && !second.id().isBlank()
                        && !first.id().equals(second.id()));
        expect("файл инвариантов отдельный от истории и памяти (invariants.json)",
                file.getFileName().toString().equals("invariants.json") && Files.exists(file));
        expect("категория сохраняется как задана (нижний регистр)",
                "architecture".equals(first.category()));

        // Персистентность: запись → новый экземпляр стора → чтение.
        InvariantStore reopened = new InvariantStore(file);
        List<Invariant> loaded = reopened.list();
        expect("инварианты переживают переоткрытие хранилища",
                loaded.size() == 2
                        && loaded.get(0).text().equals(first.text())
                        && loaded.get(0).createdAt().equals(first.createdAt())
                        && loaded.get(1).text().equals(second.text()));

        // Удаление и очистка.
        expect("remove по id удаляет запись", reopened.remove(first.id()));
        expect("повторный remove по тому же id — false", !reopened.remove(first.id()));
        expect("после remove остаётся одна запись", reopened.list().size() == 1);
        reopened.clear();
        expect("clear опустошает список инвариантов", reopened.list().isEmpty());
        expect("пустой список переживает переоткрытие",
                new InvariantStore(file).list().isEmpty());

        // Формат файла: собственная сущность, а не поле истории или памяти.
        InvariantStore formatted = new InvariantStore(
                Files.createTempDirectory(baseTempDir, "inv-").resolve("invariants.json"));
        formatted.add("рамка", "stack");
        JsonNode saved = MAPPER.readTree(
                Files.readString(formatted.file(), StandardCharsets.UTF_8));
        expect("файл инвариантов имеет собственный формат (schemaVersion 1 и items)",
                saved.path("schemaVersion").asInt(-1)
                        == InvariantStore.SUPPORTED_SCHEMA_VERSION
                        && saved.path("items").size() == 1
                        && saved.path("items").get(0).path("category").asText()
                        .equals("stack"));

        // Пустой текст — ошибка, файл не трогается.
        try {
            store.add("   ", null);
            expect("пустой текст инварианта отклоняется", false);
        } catch (IllegalArgumentException e) {
            expect("пустой текст инварианта отклоняется", true);
        }

        // Обратная совместимость: старый JSON без forbiddenMarkers читается
        // как пустой список маркеров.
        Path legacyFile = Files.createTempDirectory(baseTempDir, "inv-legacy-")
                .resolve("invariants.json");
        Files.writeString(legacyFile, """
                {"schemaVersion": 1, "items": [
                  {"id": "aa11bb22", "text": "старая рамка без маркеров",
                   "category": "stack", "createdAt": "2026-01-01T00:00:00Z"}
                ]}
                """, StandardCharsets.UTF_8);
        Invariant legacy = new InvariantStore(legacyFile).list().get(0);
        expect("старые инварианты без маркеров читаются как пустой список",
                legacy.forbiddenMarkers().isEmpty());

        // Маркеры сериализуются и читаются обратно.
        InvariantStore roundtrip = new InvariantStore(
                Files.createTempDirectory(baseTempDir, "inv-roundtrip-")
                        .resolve("invariants.json"));
        roundtrip.add("рамка с маркерами", "stack", List.of("fastjson", "log4j"));
        JsonNode markersJson = MAPPER.readTree(Files.readString(
                roundtrip.file(), StandardCharsets.UTF_8));
        expect("маркеры попадают в JSON (forbiddenMarkers)",
                markersJson.path("items").get(0).path("forbiddenMarkers")
                        .size() == 2);
        expect("маркеры переживают переоткрытие хранилища",
                new InvariantStore(roundtrip.file()).list().get(0)
                        .forbiddenMarkers().equals(List.of("fastjson", "log4j")));
    }

     static void checkInvariantCommands() throws Exception {
        InvariantStore invariants = tempInvariantStoreForTests();
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(), invariants);

        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/invariant"),
                TerminalUi.Input.command(
                        "/invariant add проект «Север-17» — модульный монолит на Java 21, "
                                + "без Spring и БД architecture"),
                TerminalUi.Input.command(
                        "/invariant add только стандартная библиотека и Jackson"),
                TerminalUi.Input.command("/invariant"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "glm-5.3-flash");
        expect("пустой список инвариантов сообщает, как задать рамку",
                ui.systems.stream().anyMatch(t ->
                        t.contains("инвариантов нет")
                                && t.contains("/invariant add <текст> — задать")));
        expect("подтверждение добавления инварианта с категорией",
                ui.systems.stream().anyMatch(t -> t.contains("✓ Инвариант задан")
                        && t.contains("без Spring и БД") && t.contains("[architecture]")));
        expect("категория без явного указания — other",
                ui.systems.stream().anyMatch(t ->
                        t.contains("✓ Инвариант задан") && t.contains("[other]")));
        expect("после /invariant add подсказка следующего шага",
                ui.systems.stream().anyMatch(t -> t.contains(
                        "Дальше: /invariant — посмотреть все рамки")));
        expect("список показывает обе рамки с категориями",
                ui.systems.stream().anyMatch(t -> t.contains("[architecture]")
                        && t.contains("[other]"))
                        && !ui.systems.stream().anyMatch(t ->
                        t.contains("[stack]") && t.contains("Инварианты — жёсткие")));
        expect("инварианты добавлены в агент и хранилище",
                agent.invariantsView().size() == 2
                        && agent.invariantsView().get(0).category().equals("architecture")
                        && agent.invariantsFile().getFileName().toString()
                        .equals("invariants.json"));

        // Remove по номеру, затем по id (отдельные прогоны: состояние
        // проверяется между ними).
        String secondId = agent.invariantsView().get(1).id();
        FakeUi removeNumberUi = new FakeUi(
                TerminalUi.Input.command("/invariant remove 1"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(removeNumberUi, agent, "glm-5.3-flash");
        expect("remove по номеру удаляет рамку с подтверждением текста",
                removeNumberUi.systems.stream().anyMatch(t -> t.contains("✓ Инвариант удалён")
                        && t.contains("без Spring и БД"))
                        && agent.invariantsView().size() == 1);
        FakeUi removeIdUi = new FakeUi(
                TerminalUi.Input.command("/invariant remove " + secondId),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(removeIdUi, agent, "glm-5.3-flash");
        expect("remove по id удаляет рамку с подтверждением текста",
                removeIdUi.systems.stream().anyMatch(t -> t.contains("✓ Инвариант удалён")
                        && t.contains("стандартная библиотека"))
                        && agent.invariantsView().isEmpty());

        // Неизвестный id/номер — ошибка, ничего не удалено.
        agent.invariantAdd("рамка для удаления", "stack");
        FakeUi notFoundUi = new FakeUi(TerminalUi.Input.command("/invariant remove 99"));
        Main.runLoop(notFoundUi, agent, "glm-5.3-flash");
        expect("несуществующий номер — ошибка, рамка сохранена",
                notFoundUi.errors.stream().anyMatch(t -> t.contains("Инвариант не найден"))
                        && agent.invariantsView().size() == 1);

        // Clear с подтверждением и отказом от подтверждения.
        FakeUi clearNoUi = new FakeUi(TerminalUi.Input.command("/invariant clear"));
        clearNoUi.confirmProfileClearAnswer = false;
        Main.runLoop(clearNoUi, agent, "glm-5.3-flash");
        expect("отказ подтверждения сохраняет инварианты",
                clearNoUi.confirmProfileClearCount == 1 && clearNoUi.confirmProfileClearSubject
                        .contains("инварианты")
                        && agent.invariantsView().size() == 1
                        && clearNoUi.systems.stream().anyMatch(t ->
                        t.contains("Удаление отменено")));
        FakeUi clearYesUi = new FakeUi(
                TerminalUi.Input.command("/invariant clear"),
                TerminalUi.Input.command("/invariant"),
                TerminalUi.Input.command("/exit"));
        clearYesUi.confirmProfileClearAnswer = true;
        Main.runLoop(clearYesUi, agent, "glm-5.3-flash");
        expect("/invariant clear очищает все рамки и сообщает об этом",
                clearYesUi.confirmProfileClearCount == 1
                        && agent.invariantsView().isEmpty()
                        && clearYesUi.systems.stream().anyMatch(t ->
                        t.contains("✓ Инварианты очищены")));

        // Персистентность команд: запись → новый экземпляр агента со тем же файлом.
        FakeUi addUi = new FakeUi(TerminalUi.Input.command(
                "/invariant add персистентная рамка stack"));
        Main.runLoop(addUi, agent, "glm-5.3-flash");
        LlmAgent restarted = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(),
                new InvariantStore(invariants.file()));
        expect("инварианты переживают перезапуск (новый агент с тем же файлом)",
                restarted.invariantsView().size() == 1
                        && restarted.invariantsView().get(0).text()
                        .contains("персистентная рамка"));
    }

     static void checkInvariantBlockInSystemMessage() {
        ModelSettings settings = ModelSettings.defaults();
        Map<String, MemoryEntry> emptyMemory = new LinkedHashMap<>();

        Invariant architecture = Invariant.create(
                "проект «Север-17» — модульный монолит на Java 21, без Spring и БД",
                "architecture", Instant.parse("2026-01-01T00:00:00Z"));
        Invariant business = Invariant.create("CSV-экспорт — по RFC 4180, с BOM", "business",
                Instant.parse("2026-01-01T00:00:00Z"));

        ChatMessage emptySystem = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, null, Map.of(), null, null, List.of());
        expect("без инвариантов блок «ИНВАРИАНТЫ» опускается",
                !emptySystem.content().contains("ИНВАРИАНТЫ"));

        ChatMessage fullSystem = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, null, Map.of(), null, null,
                List.of(architecture, business));
        String system = fullSystem.content();
        expect("заданные инварианты подставлены: заголовок блока",
                system.contains("<<<ИНВАРИАНТЫ (жёсткие рамки, нарушать нельзя)"));
        expect("блок содержит инвариант с категорией architecture",
                system.contains("[architecture] проект «Север-17»"));
        expect("блок содержит инвариант с категорией business",
                system.contains("[business] CSV-экспорт — по RFC 4180"));
        expect("инструкция блока объявляет жёсткие ограничения",
                system.contains("Это жёсткие ограничения"));
        expect("инструкция запрещает решения, нарушающие инвариант",
                system.contains("НЕ предлагай решение, которое нарушает"));
        expect("инструкция требует назвать инвариант и предложить альтернативу",
                system.contains("назови инвариант")
                        && system.contains("альтернативу в рамках инварианта"));
        expect("инвариант не трактуется как исторические сведения",
                !system.contains("исторические сведения"));
    }

     static void checkInvariantBlockInRealRequest() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            InvariantStore invariantStore = new InvariantStore(
                    Files.createTempDirectory(baseTempDir, "inv-")
                            .resolve("invariants.json"));
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(), tempMemoryStore(),
                    tempProfileStoreForTests(), invariantStore);

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command(
                            "/invariant add без Spring и БД stack"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            // Нейтральный запрос уходит модели как обычно (проверка не мешает):
            // «добавь spring» теперь ловится до API (см. checkInvariantGuardNoApiInMessage).
            agent.ask("расскажи про структуру проекта");
            String system = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("в запросе есть блок «ИНВАРИАНТЫ (жёсткие рамки, нарушать нельзя)»",
                    system.contains("ИНВАРИАНТЫ (жёсткие рамки, нарушать нельзя)"));
            expect("блок содержит заданный инвариант",
                    system.contains("[stack] без Spring и БД")
                            && system.contains("НЕ предлагай решение, которое нарушает"));

            // /clear не стирает инварианты: они снова в запросе после очистки.
            agent.resetConversation();
            agent.ask("вопрос после /clear");
            String rebuilt = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("инварианты переживают /clear и остаются в запросе",
                    rebuilt.contains("[stack] без Spring и БД"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkInvariantAddForbiddenMarkers() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            InvariantStore invariantStore = tempInvariantStoreForTests();
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(), tempMemoryStore(),
                    tempProfileStoreForTests(), invariantStore);
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command(
                            "/invariant add без Spring и БД architecture "
                                    + "запрещено: spring, spring boot, hibernate"),
                    TerminalUi.Input.command("/invariant"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            Invariant stored = agent.invariantsView().get(0);
            expect("«запрещено: …» сохраняется в маркерах инварианта",
                    stored.forbiddenMarkers().equals(List.of(
                            "spring", "spring boot", "hibernate")));
            expect("список инвариантов показывает запрещённые слова",
                    ui.systems.stream().anyMatch(t -> t.contains("запрещено:")
                            && t.contains("spring boot")));
            expect("текст рамки чист от служебного хвоста «запрещено:»",
                    !stored.text().contains("запрещено"));
            expect("подсказка после add упоминает «запрещено:»",
                    ui.systems.stream().anyMatch(t -> t.contains("запрещено:")
                            && t.contains("до API")));

            LlmAgent restarted = new LlmAgent(new Config("test-key",
                    "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                    ModelSettings.defaults(),
                    java.net.http.HttpClient.newHttpClient(),
                    new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                            .resolve("conversation.json")),
                    tempMemoryStore(), tempProfileStoreForTests(),
                    new InvariantStore(invariantStore.file()));
            expect("маркеры переживают перезапуск",
                    restarted.invariantsView().get(0).forbiddenMarkers()
                            .equals(List.of("spring", "spring boot", "hibernate")));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkTaskInvariantCommands() throws Exception {
        InvariantStore globalStore = tempInvariantStoreForTests();
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(), globalStore);

        // Без задачи — ошибка, сначала /task start.
        FakeUi noTaskUi = new FakeUi(
                TerminalUi.Input.command("/task invariant add рамка задачи stack"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(noTaskUi, agent, "glm-5.3-flash");
        expect("/task invariant add без задачи — ошибка «сначала /task start»",
                noTaskUi.errors.stream().anyMatch(t -> t.contains("Задача не начата")
                        && t.contains("/task start")));

        // Глобальный инвариант для проверки разделения источников.
        agent.invariantAdd("глобальная рамка проекта", "architecture");

        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/task start запустить миграцию вставки"),
                TerminalUi.Input.command(
                        "/task invariant add локальная рамка задачи stack "
                                + "запрещено: fastjson"),
                TerminalUi.Input.command("/task invariant"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "glm-5.3-flash");
        expect("локальный инвариант добавляется к задаче",
                agent.taskInvariantsView().size() == 1
                        && agent.taskInvariantsView().get(0).text()
                        .equals("локальная рамка задачи")
                        && agent.taskInvariantsView().get(0).category()
                        .equals("stack")
                        && agent.taskInvariantsView().get(0).forbiddenMarkers()
                        .equals(List.of("fastjson")));
        expect("локальный инвариант НЕ в глобальном хранилище",
                agent.invariantsView().size() == 1
                        && agent.invariantsView().get(0).text()
                        .equals("глобальная рамка проекта"));
        String globalJson = Files.readString(globalStore.file(),
                StandardCharsets.UTF_8);
        expect("файл глобальных инвариантов не знает про локальную рамку",
                !globalJson.contains("локальная рамка задачи"));
        expect("список /task invariant показывает локальную рамку",
                ui.systems.stream().anyMatch(t -> t.contains("Локальные инварианты")
                        && t.contains("локальная рамка задачи")));
        expect("подсказка после /task invariant add",
                ui.systems.stream().anyMatch(t ->
                        t.contains("Дальше: /task invariant — посмотреть локальные рамки")));
        expect("локальный инвариант живёт вместе с этапами задачи",
                agent.taskState().stage() == TaskStage.PLANNING);

        // Этапы/шаги не теряют локальные инварианты.
        agent.taskStep("проверка контрактов");
        expect("шаг задачи сохраняет локальные инварианты",
                agent.taskInvariantsView().size() == 1);

        // Remove по номеру, затем по id.
        agent.taskInvariantAdd("второй локальный инвариант", "business");
        String firstId = agent.taskInvariantsView().get(0).id();
        FakeUi removeNumber = new FakeUi(TerminalUi.Input.command(
                "/task invariant remove 1"));
        Main.runLoop(removeNumber, agent, "glm-5.3-flash");
        expect("remove по номеру удаляет локальную рамку",
                agent.taskInvariantsView().size() == 1
                        && agent.taskInvariantsView().get(0).text()
                        .equals("второй локальный инвариант")
                        && removeNumber.systems.stream().anyMatch(t ->
                        t.contains("✓ Локальный инвариант удалён")));
        // Remove по id: сначала несуществующий id — ошибка, затем по id.
        FakeUi removeId = new FakeUi(TerminalUi.Input.command(
                "/task invariant remove " + firstId));
        // firstId уже удалён по номеру; проверяем remove несуществующего id.
        Main.runLoop(removeId, agent, "glm-5.3-flash");
        expect("remove по несуществующему id — ошибка",
                removeId.errors.stream().anyMatch(t ->
                        t.contains("Локальный инвариант не найден")));
        FakeUi removeLast = new FakeUi(TerminalUi.Input.command(
                "/task invariant remove "
                        + agent.taskInvariantsView().get(0).id()));
        Main.runLoop(removeLast, agent, "glm-5.3-flash");
        expect("remove по id удаляет последнюю локальную рамку",
                agent.taskInvariantsView().isEmpty());
        agent.taskInvariantAdd("рамка для clear", "other");
        FakeUi clearUi = new FakeUi(TerminalUi.Input.command(
                "/task invariant clear"));
        clearUi.confirmProfileClearAnswer = true;
        Main.runLoop(clearUi, agent, "glm-5.3-flash");
        expect("/task invariant clear очищает локальные, глобальные остаются",
                agent.taskInvariantsView().isEmpty()
                        && agent.invariantsView().size() == 1);

        // /task clear стирает локальные вместе с задачей; глобальные остаются.
        agent.taskStart("новая задача с рамками");
        agent.taskInvariantAdd("локальная рамка перед clear", "stack");
        FakeUi clearTaskUi = new FakeUi(TerminalUi.Input.command("/task clear"));
        Main.runLoop(clearTaskUi, agent, "glm-5.3-flash");
        expect("/task clear стирает локальные инварианты вместе с задачей",
                agent.taskState() == null
                        && agent.taskInvariantsView().isEmpty()
                        && agent.invariantsView().size() == 1);

        // Локальные рамки НЕ переживают перезапуск (TaskState — сессионное).
        agent.taskStart("задача для перезапуска");
        agent.taskInvariantAdd("сессионная рамка", "stack");
        LlmAgent restarted = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(),
                new InvariantStore(globalStore.file()));
        expect("перезапуск: локальная рамка исчезла (TaskState сессионный), "
                        + "глобальная на месте",
                restarted.taskInvariantsView().isEmpty()
                        && restarted.invariantsView().size() == 1);
    }

     static void checkHelpForEveryCommand() {
        for (String name : TerminalUi.chatCommandNames()) {
            String help = TerminalUi.chatCommandHelp(name);
            expect("справка /help " + name + " непустая",
                    help != null && !help.isBlank() && help.contains(name));
        }
    }

     static void checkUnknownSubcommands() throws Exception {
        InvariantStore invariantStore = tempInvariantStoreForTests();
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(), invariantStore);
        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/invariant совсем-не-субкоманда"),
                TerminalUi.Input.command("/task invariant совсем-не-субкоманда"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "glm-5.3-flash");
        String systems = String.join("\n", ui.systems);
        expect("неизвестная подкоманда /invariant даёт «Использование:»",
                systems.contains("Использование: /invariant"));
        expect("неизвестная подкоманда /task invariant даёт «Использование:»",
                systems.contains("Использование: /task invariant"));
        expect("неизвестные подкоманды не ломают агента и не создают историю",
                agent.getHistory().isEmpty() && agent.taskState() == null
                        && agent.invariantsView().isEmpty()
                        && agent.taskInvariantsView().isEmpty());
    }
}
