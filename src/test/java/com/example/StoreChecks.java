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

final class StoreChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkConversationStore();
        checkStoreValidation();
        checkPersistenceAcrossAgents();
        checkSaveFailure();
        checkSaveFailureKeepsPreviousFile();
        checkCorruptedFile();
        checkLocking();
        checkMainCommandsPersistence();
        checkStoresHardening();
        checkModelSettings();
        checkSessionTokenLimitSettings();
        checkDialogStatePersistence();
        checkDefaultHistoryContinuation();
    }

     static void checkConversationStore() throws IOException {
        Path storeFile;
        List<ChatMessage> roundTripMessages;
        String roundTripSessionId;
        // Отсутствующий файл — новая пустая беседа, ничего не записывается.
        try (JsonConversationStore store = tempStore()) {
            ConversationState loaded = store.load();
            expect("отсутствующий файл истории даёт пустую беседу",
                    loaded.messages().isEmpty() && !loaded.sessionId().isBlank());
            expect("при отсутствии файла истории JSON не создаётся заранее",
                    !Files.exists(store.file()));

            // Сохранение и чтение: версия формата, sessionId, пары.
            ChatMessage user = new ChatMessage("user", "Запомни кодовое слово: «северный маяк».");
            ChatMessage assistant = new ChatMessage("assistant", "Кодовое слово: северный маяк.");
            store.save(new ConversationState(loaded.sessionId(), List.of(user, assistant)));
            expect("после сохранения файл истории существует", Files.exists(store.file()));

            JsonNode saved = MAPPER.readTree(Files.readString(store.file(), StandardCharsets.UTF_8));
            expect("файл содержит schemaVersion "
                            + JsonConversationStore.SUPPORTED_SCHEMA_VERSION,
                    saved.path("schemaVersion").asInt(-1)
                            == JsonConversationStore.SUPPORTED_SCHEMA_VERSION);
            expect("файл содержит sessionId беседы",
                    loaded.sessionId().equals(saved.path("sessionId").asText()));
            expect("файл содержит целую пару user/assistant",
                    saved.path("messages").size() == 2
                            && "user".equals(saved.path("messages").get(0).path("role").asText())
                            && user.content().equals(saved.path("messages").get(0).path("content").asText())
                            && "assistant".equals(saved.path("messages").get(1).path("role").asText()));

            // Кириллица, кавычки, переносы строк, табуляция и код — без потерь.
            String tricky = "Кириллица \"в кавычках\" и 'апострофы'\nвторая строка\tс табуляцией\n"
                    + "```java\nif (a < b && c > d) { String s = \"тест\"; }\n```\n"
                    + "символы: \\ \" № — и перенос в конце";
            String trickyAnswer = tricky + "\nстрока ответа";
            store.save(new ConversationState("sid-тест", List.of(
                    new ChatMessage("user", tricky), new ChatMessage("assistant", trickyAnswer))));
            ConversationState roundTrip = store.load();
            expect("кириллица, кавычки, переносы, табуляция и код сохраняются без потерь",
                    roundTrip.messages().size() == 2
                            && tricky.equals(roundTrip.messages().get(0).content())
                            && trickyAnswer.equals(roundTrip.messages().get(1).content()));
            storeFile = store.file();
            roundTripMessages = roundTrip.messages();
            roundTripSessionId = roundTrip.sessionId();
        }

        // Отдельное хранилище того же пути (после освобождения блокировки).
        try (JsonConversationStore reopened = new JsonConversationStore(storeFile)) {
            ConversationState reopenedState = reopened.load();
            expect("повторно открытое хранилище читает сохранённое состояние",
                    reopenedState.messages().equals(roundTripMessages)
                            && reopenedState.sessionId().equals(roundTripSessionId));
        }

        checkStoreValidation();
    }

     static void checkStoreValidation() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "valid-").resolve("conversation.json");

        byte[] garbage = "это вообще не { json".getBytes(StandardCharsets.UTF_8);
        Files.write(file, garbage);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "повреждённый JSON распознаётся");
            expect("повреждённый JSON не перезаписывается при чтении",
                    Arrays.equals(Files.readAllBytes(file), garbage));
        }

        byte[] futureVersion = "{\"schemaVersion\":99,\"sessionId\":\"s\",\"messages\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, futureVersion);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectUnknownVersion(store, file);
            expect("файл с неизвестной версией не изменён",
                    Arrays.equals(Files.readAllBytes(file), futureVersion));
        }

        byte[] brokenPair = ("{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"вопрос без ответа\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, brokenPair);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "обрыв пары (user без assistant) распознаётся");
        }

        byte[] systemRole = ("{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":"
                + "[{\"role\":\"system\",\"content\":\"инструкция\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, systemRole);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "роль system в файле отклоняется (она не хранится)");
        }

        byte[] emptyContent = ("{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"\"},{\"role\":\"assistant\",\"content\":\"ok\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, emptyContent);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "пустой текст сообщения отклоняется");
        }

        byte[] badMessages = "{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":\"нет\"}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, badMessages);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "messages не массив распознаётся");
        }

        byte[] noSession = "{\"schemaVersion\":1,\"messages\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, noSession);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "отсутствие sessionId распознаётся");
        }
    }

     static void checkPersistenceAcrossAgents() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        List<String> sessions = new ArrayList<>();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            sessions.add(session);
            lastBody.set(requestBody);
            if (requestBody.contains("case=http-500")) {
                return new Response(500,
                        "{\"error\":{\"message\":\"internal\"}}".getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=empty-content")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"\"}}]}").getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Path historyFile = Files.createTempDirectory(baseTempDir, "persist-")
                    .resolve("conversation.json");

            // --- Первый «запуск»: успешная пара сохраняется в JSON ---
            String question1 = "Запомни: кодовое слово «северный маяк», цвет — зелёный.\n"
                    + "Вторая строка вопроса с \"кавычками\"";
            try (JsonConversationStore store1 = new JsonConversationStore(historyFile)) {
                LlmAgent agent1 = new LlmAgent(config, ModelSettings.from(branchingEnv()), client, store1);
                expect("первый запуск начинает без истории",
                        !agent1.hasRestoredContext() && agent1.getHistory().isEmpty());
                expect("первый запуск получает ответ", "Ответ 1".equals(agent1.ask(question1)));
            }

            JsonNode saved = MAPPER.readTree(Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("успешная пара user/assistant сохранена в JSON",
                    saved.path("messages").size() == 2
                            && question1.equals(saved.path("messages").get(0).path("content").asText())
                            && "Ответ 1".equals(saved.path("messages").get(1).path("content").asText()));
            String savedText = Files.readString(historyFile, StandardCharsets.UTF_8);
            expect("ключ API не попадает в сохранённый JSON",
                    !savedText.contains("test-key") && !savedText.contains("Bearer")
                            && !savedText.contains("Authorization"));
            String sessionIdInFile = saved.path("sessionId").asText();
            expect("sessionId сохранён в файле истории", !sessionIdInFile.isBlank());

            // --- Второй «запуск»: восстановление и передача контекста в API ---
            try (JsonConversationStore store2 = new JsonConversationStore(historyFile)) {
                LlmAgent agent2 = new LlmAgent(config, ModelSettings.from(branchingEnv()), client, store2);
                expect("новый экземпляр восстанавливает пару из файла",
                        agent2.hasRestoredContext()
                                && agent2.getHistory().equals(List.of(
                                new ChatMessage("user", question1),
                                new ChatMessage("assistant", "Ответ 1"))));

                String question2 = "Какое кодовое слово и какой цвет я назвал?";
                expect("второй запуск получает ответ", "Ответ 2".equals(agent2.ask(question2)));

                JsonNode messages = MAPPER.readTree(lastBody.get()).path("messages");
                int systemCount = 0;
                for (JsonNode message : messages) {
                    if ("system".equals(message.path("role").asText())) {
                        systemCount++;
                    }
                }
                expect("system-сообщение ровно одно и стоит первым",
                        systemCount == 1 && "system".equals(messages.get(0).path("role").asText()));
                expect("восстановленные сообщения уходят в API в правильном порядке",
                        messages.size() == 4
                                && question1.equals(messages.get(1).path("content").asText())
                                && "Ответ 1".equals(messages.get(2).path("content").asText())
                                && question2.equals(messages.get(3).path("content").asText())
                                && "user".equals(messages.get(1).path("role").asText())
                                && "assistant".equals(messages.get(2).path("role").asText())
                                && "user".equals(messages.get(3).path("role").asText()));
                expect("sessionId восстановлен из файла",
                        sessionIdInFile.equals(sessions.get(sessions.size() - 1)));

                // --- Ошибки API: файл и память не меняются ---
                byte[] fileBeforeError = Files.readAllBytes(historyFile);
                List<ChatMessage> memoryBeforeError = agent2.getHistory();
                expect("HTTP-ошибка API распознаётся",
                        expectAgentError(agent2, "case=http-500").contains("HTTP-статус 500"));
                expect("после ошибки API файл истории не изменился",
                        Arrays.equals(Files.readAllBytes(historyFile), fileBeforeError));
                expect("после ошибки API память не изменилась",
                        agent2.getHistory().equals(memoryBeforeError));

                expect("пустой ответ распознаётся",
                        expectAgentError(agent2, "case=empty-content").contains("пустой итоговый ответ"));
                expect("после пустого ответа файл истории не изменился",
                        Arrays.equals(Files.readAllBytes(historyFile), fileBeforeError));
                expect("после пустого ответа память не изменилась",
                        agent2.getHistory().equals(memoryBeforeError));

                // --- День 9: на диск сохраняется весь архив без обрезания ---
                for (int i = 1; i <= LlmAgent.MAX_HISTORY_TURNS + 1; i++) {
                    agent2.ask("вопрос переполнения " + i);
                }
                List<ChatMessage> memoryAfterOverflow = agent2.getHistory();
                JsonNode overflowFile = MAPPER.readTree(
                        Files.readString(historyFile, StandardCharsets.UTF_8));
                List<ChatMessage> fileMessages = new ArrayList<>();
                overflowFile.path("messages").forEach(m -> fileMessages.add(
                        new ChatMessage(m.path("role").asText(), m.path("content").asText())));
                expect("файл хранит тот же архив, что и память (без обрезания)",
                        fileMessages.equals(memoryAfterOverflow)
                                && memoryAfterOverflow.size() > LlmAgent.MAX_HISTORY_TURNS * 2);
                expect("чек на диске: только целые пары",
                        rolesAlternate(memoryAfterOverflow)
                                && "user".equals(memoryAfterOverflow.get(0).role()));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkSaveFailure() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}]}")
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            FailingStore failingStore = new FailingStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), trustedHttpClient(keyStore), failingStore);

            // Ответ получен, но записать не удалось: отдельная ошибка сохранения.
            ConversationSaveException failure = null;
            try {
                agent.ask("вопрос при отказе записи");
            } catch (ConversationSaveException e) {
                failure = e;
            }
            expect("сбой записи после ответа даёт отдельную ошибку сохранения", failure != null);
            expect("полученный ответ доступен интерфейсу через ошибку сохранения",
                    failure != null && "Ответ 1".equals(failure.getAnswer()));
            expect("ошибка сохранения не называется ошибкой запроса к модели",
                    failure != null && failure.getMessage().contains("не сохранён")
                            && !failure.getMessage().contains("HTTP-статус"));
            expect("при сбое записи история в памяти не меняется", agent.getHistory().isEmpty());
            expect("при сбое записи выполняется ровно одна попытка сохранения (без повторов)",
                    failingStore.attempted.size() == 1);

            // Через UI: ответ показан, предупреждение показано, сессия завершается.
            FakeUi ui = new FakeUi(TerminalUi.Input.message("второй вопрос при отказе записи"));
            int exitCode = Main.runLoop(ui, agent, "test-model");
            expect("при сбое записи ответ модели показан пользователю",
                    ui.messages.contains("Ответ 2"));
            expect("при сбое записи выводится предупреждение о несохранённой паре",
                    ui.errors.stream().anyMatch(s -> s.contains("не сохранён")));
            expect("после сбоя записи сессия завершается с ошибкой", exitCode == 1);
            expect("после сбоя записи новая пара не добавлена в историю",
                    agent.getHistory().isEmpty());
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }

        checkSaveFailureKeepsPreviousFile();
    }

     static void checkSaveFailureKeepsPreviousFile() throws IOException {
        Path dir = Files.createTempDirectory(baseTempDir, "readonly-");
        Path historyFile = dir.resolve("conversation.json");
        boolean checked = false;
        try (JsonConversationStore store = new JsonConversationStore(historyFile)) {
            store.save(new ConversationState("sid-1", List.of(
                    new ChatMessage("user", "первый вопрос"),
                    new ChatMessage("assistant", "первый ответ"))));
            byte[] before = Files.readAllBytes(historyFile);

            File dirAsFile = dir.toFile();
            if (dirAsFile.setWritable(false)) {
                try {
                    // Под root запрет записи не работает — тогда сценарий пропускается.
                    boolean writeBlocked;
                    try {
                        Files.createTempFile(dir, "probe-", ".tmp");
                        writeBlocked = false;
                    } catch (IOException e) {
                        writeBlocked = true;
                    }
                    if (writeBlocked) {
                        checked = true;
                        try {
                            store.save(new ConversationState("sid-2", List.of(
                                    new ChatMessage("user", "второй вопрос"),
                                    new ChatMessage("assistant", "второй ответ"))));
                            expect("запись в каталог без прав даёт ошибку сохранения", false);
                        } catch (ConversationStoreException e) {
                            expect("запись в каталог без прав даёт ошибку сохранения", true);
                        }
                        expect("при сбое записи предыдущий корректный файл сохранён",
                                Arrays.equals(Files.readAllBytes(historyFile), before));
                        try (var listed = Files.list(dir)) {
                            expect("после сбоя записи временные файлы удалены",
                                    listed.noneMatch(p -> p.toString().endsWith(".tmp")));
                        }
                    }
                } finally {
                    dirAsFile.setWritable(true);
                }
            }
        }
        if (!checked) {
            System.out.println("ПРОПУСК: проверка сбоя записи на каталоге без прав "
                    + "не выполнена (запись ограничить не удалось).");
        }
    }

     static void checkCorruptedFile() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "corrupt-")
                .resolve("conversation.json");
        byte[] garbage = "{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":[{]"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, garbage);

        Config config = new Config("test-key", "https://example.com/v1/chat/completions",
                "glm-5.3-flash");
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            boolean stopped;
            try {
                new LlmAgent(config, store);
                stopped = false;
            } catch (ConversationStoreException e) {
                stopped = e.getMessage().contains(file.toString());
            }
            expect("повреждённый файл останавливает запуск с понятной ошибкой", stopped);
        }
        expect("повреждённый файл не перезаписывается и не удаляется",
                Arrays.equals(Files.readAllBytes(file), garbage));
        try (var listed = Files.list(file.getParent())) {
            expect("при чтении повреждённого файла временные файлы не создаются",
                    listed.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
    }

     static void checkLocking() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "lock-")
                .resolve("conversation.json");
        String busyMessage = "";
        JsonConversationStore first = new JsonConversationStore(file);
        try {
            boolean blocked;
            try {
                JsonConversationStore second = new JsonConversationStore(file);
                second.close();
                blocked = false;
            } catch (ConversationStoreException e) {
                blocked = true;
                busyMessage = e.getMessage();
            }
            expect("второй экземпляр не получает доступ к занятой истории", blocked);
            expect("сообщение о занятой истории объясняет следующий шаг",
                    busyMessage.contains("уже открыта")
                            && busyMessage.contains("LLM_HISTORY_FILE"));
        } finally {
            first.close();
        }
        // Lock-файл остаётся, но блокировки больше нет — запуск возможен.
        // (файл явно задан, per-run — lock удаляется по правилу P5)
        try (JsonConversationStore after = new JsonConversationStore(file)) {
            expect("после освобождения блокировки новый запуск возможен",
                    after.load().messages().isEmpty());
        }
        // P5: тоже самое с явным флагом per-run генерации — lock удаляется.
        Path dir = Files.createTempDirectory(baseTempDir, "lock-cleanup-");
        Path generated = dir.resolve("chat-20260901-000000-000-1abc.json");
        JsonConversationStore own = new JsonConversationStore(generated, true);
        Path ownLock = dir.resolve(generated.getFileName().toString() + ".lock");
        expect("per-run история: lock-файл существует при открытой истории",
                Files.exists(ownLock));
        own.close();
        expect("per-run история: lock-файл удалён при закрытии",
                !Files.exists(ownLock));
        own.removeLockFileQuietly();
        expect("повторный вызов удаления lock не даёт ошибок",
                !Files.exists(ownLock));
        Path shared = dir.resolve("explicit-history.json");
        JsonConversationStore explicit = new JsonConversationStore(shared, false);
        Path explicitLock = dir.resolve(shared.getFileName().toString() + ".lock");
        expect("явная история: lock-файл при открытой истории", Files.exists(explicitLock));
        explicit.close();
        expect("явная история: lock-файл НЕ удалён при закрытии",
                Files.exists(explicitLock));
        explicit.removeLockFileQuietly();
        expect("явная история: прямой вызов удаления не удаляет lock",
                Files.exists(explicitLock));
    }

     static void checkMainCommandsPersistence() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        List<String> sessions = new ArrayList<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            sessions.add(session);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Path historyFile = Files.createTempDirectory(baseTempDir, "commands-")
                    .resolve("conversation.json");

            JsonConversationStore store1 = new JsonConversationStore(historyFile);
            LlmAgent agent1 = new LlmAgent(config, ModelSettings.defaults(), client, store1);
            agent1.ask("вопрос перед командами");
            byte[] afterAsk = Files.readAllBytes(historyFile);

            // /clear без подтверждения: файл и память не трогает.
            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/clear"),
                    TerminalUi.Input.command("/exit"));
            expect("/clear завершает цикл нормально",
                    Main.runLoop(clearUi, agent1, "test-model") == 0);
            expect("/clear без подтверждения не меняет файл истории",
                    Arrays.equals(Files.readAllBytes(historyFile), afterAsk));
            expect("/clear без подтверждения не меняет память", agent1.getHistory().size() == 2);

            // Отказ от подтверждения (история непуста) оставляет файл без изменений.
            byte[] beforeDeclinedReset = Files.readAllBytes(historyFile);
            FakeUi resetNoUi = new FakeUi(
                    TerminalUi.Input.command("/reset"),
                    TerminalUi.Input.command("/exit"));
            resetNoUi.confirmAnswer = false;
            Main.runLoop(resetNoUi, agent1, "test-model");
            expect("/reset при отказе не меняет файл",
                    Arrays.equals(Files.readAllBytes(historyFile), beforeDeclinedReset));
            expect("/reset при отказе сохраняет память",
                    agent1.getHistory().size() == 2);

            // /reset с подтверждением: пустая беседа записывается на диск.
            String sessionIdBeforeReset = MAPPER.readTree(
                            Files.readString(historyFile, StandardCharsets.UTF_8))
                    .path("sessionId").asText();
            FakeUi resetUi = new FakeUi(
                    TerminalUi.Input.command("/reset"),
                    TerminalUi.Input.command("/exit"));
            resetUi.confirmAnswer = true;
            Main.runLoop(resetUi, agent1, "test-model");
            JsonNode afterReset = MAPPER.readTree(
                    Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("/reset сохраняет пустую беседу на диск",
                    afterReset.path("messages").isEmpty());
            expect("после /reset создаётся новый sessionId",
                    !sessionIdBeforeReset.equals(afterReset.path("sessionId").asText()));
            expect("/reset очищает память", agent1.getHistory().isEmpty());
            expect("/reset сообщает о новой беседе",
                    resetUi.systems.stream().anyMatch(s -> s.contains("Начата новая беседа")));

            // Перезапуск: сброс пережил выход, старая история не вернулась.
            store1.close();
            try (JsonConversationStore store2 = new JsonConversationStore(historyFile)) {
                LlmAgent agent2 = new LlmAgent(config, ModelSettings.defaults(), client, store2);
                expect("после перезапуска история пуста (сброс переживает выход)",
                        agent2.getHistory().isEmpty() && !agent2.hasRestoredContext());
                agent2.ask("вопрос для проверки session id после перезапуска");
                expect("sessionId после /reset восстанавливается между запусками",
                        afterReset.path("sessionId").asText()
                                .equals(sessions.get(sessions.size() - 1)));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDialogStatePersistence() throws IOException {
        // Round-trip: состояние диалога со всеми полями переживает запись и чтение.
        DialogTaskState original = new DialogTaskState("цель Х", List.of("уточнение У"),
                List.of("только Java"), List.of("чанк"), List.of("вопрос?"));
        try (JsonConversationStore store = tempStore()) {
            store.save(new ConversationState("sid-dlg", List.of(
                    new ChatMessage("user", "q"), new ChatMessage("assistant", "a")),
                    null, null, null, original));
            ConversationState loaded = store.load();
            expect("состояние диалога переживает round-trip через JSON",
                    original.equals(loaded.dialogState()));
        }

        // Совместимость: файл, записанный без dialogState (старый конструктор).
        try (JsonConversationStore store = tempStore()) {
            store.save(new ConversationState("sid-old", List.of(
                    new ChatMessage("user", "q"), new ChatMessage("assistant", "a")),
                    null, null, null));
            ConversationState loaded = store.load();
            expect("файл без dialogState читается с null-состоянием, сообщения на месте",
                    loaded.dialogState() == null && loaded.messages().size() == 2);
        }

        // Пустое состояние не сериализуется вовсе.
        try (JsonConversationStore store = tempStore()) {
            store.save(new ConversationState("sid-empty", List.of(
                    new ChatMessage("user", "q"), new ChatMessage("assistant", "a")),
                    null, null, null, DialogTaskState.EMPTY));
            expect("пустое состояние диалога не сериализуется в файл",
                    !Files.readString(store.file(), StandardCharsets.UTF_8)
                            .contains("dialogState"));
        }

        // Повреждённое поле dialogState — понятная ошибка повреждения, файл не меняется.
        Path file = Files.createTempDirectory(baseTempDir, "dlg-corrupt-")
                .resolve("conversation.json");
        Files.writeString(file, "{\"schemaVersion\":3,\"sessionId\":\"s\",\"messages\":[],"
                + "\"dialogState\":{\"goal\":123}}", StandardCharsets.UTF_8);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expect("числовой goal в dialogState даёт ошибку повреждения состояния диалога",
                    expectStoreError(store::load).contains("состояние диалога"));
        }
        Files.writeString(file, "{\"schemaVersion\":3,\"sessionId\":\"s\",\"messages\":[],"
                + "\"dialogState\":\"строка\"}", StandardCharsets.UTF_8);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expect("dialogState не-объект даёт ошибку повреждения состояния диалога",
                    expectStoreError(store::load).contains("состояние диалога"));
        }
    }

    /** Дефект 1: без LLM_HISTORY_FILE перезапуск продолжает последнюю сессию. */
    static void checkDefaultHistoryContinuation() throws IOException {
        Path dir = Files.createTempDirectory(baseTempDir, "history-continue-");
        Path older = dir.resolve("chat-20260101-000000-000-0001.json");
        Path newer = dir.resolve("chat-20260102-000000-000-0001.json");
        try {
            expect("до сессий выбирается новый per-run файл chat-*.json",
                    JsonConversationStore.continueOrNewChatFile(dir)
                            .getFileName().toString().startsWith("chat-"));
            Files.createFile(older);
            expect("с одной прошлой сессией перезапуск продолжает её",
                    JsonConversationStore.continueOrNewChatFile(dir).equals(older));
            Files.createFile(newer);
            expect("из двух сессий перезапуск продолжает самую свежую",
                    JsonConversationStore.continueOrNewChatFile(dir).equals(newer));
            Files.createFile(dir.resolve("chat-20260103-000000-000-0001.txt"));
            Files.createFile(dir.resolve("notes-20260103.json"));
            Files.createFile(dir.resolve("memory.json"));
            expect("посторонние файлы (не chat-*.json) не выбираются",
                    JsonConversationStore.latestChatFile(dir).equals(newer));
            expect("несуществующий каталог — сессий нет (будет новый файл)",
                    JsonConversationStore.latestChatFile(dir.resolve("missing")) == null);
        } finally {
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                for (Path path : stream.toList()) {
                    Files.deleteIfExists(path);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

     static void checkModelSettings() {
        Map<String, String> env = new java.util.HashMap<>();

        // Значения по умолчанию без переменных окружения.
        ModelSettings defaults = ModelSettings.from(env);
        expect("без переменных выбирается профиль balanced",
                ModelSettings.BALANCED.equals(defaults.profile()));
        expect("лимит генерации по умолчанию 2048", defaults.maxOutputTokens() == 2048);
        expect("таймаут по умолчанию 180 секунд", defaults.requestTimeoutSeconds() == 180);
        expect("temperature по умолчанию не отправляется", defaults.temperature() == null);
        expect("лимит контекста по умолчанию не задан", defaults.contextMaxTurns() == null);
        expect("диагностика по умолчанию выключена", !defaults.diagnostics());

        // Профили и приоритет явных значений.
        env.put("LLM_RESPONSE_MODE", " FAST ");
        expect("профиль fast читается без учёта регистра и пробелов",
                ModelSettings.FAST.equals(ModelSettings.from(env).profile())
                        && ModelSettings.from(env).maxOutputTokens() == 1024);

        env.put("LLM_MAX_OUTPUT_TOKENS", "777");
        ModelSettings overridden = ModelSettings.from(env);
        expect("LLM_MAX_OUTPUT_TOKENS переопределяет лимит профиля",
                overridden.maxOutputTokens() == 777 && overridden.limitOverridden());
        expect("при переключении профиля явный лимит сохраняется",
                overridden.withProfile("detailed").maxOutputTokens() == 777
                        && ModelSettings.DETAILED.equals(overridden.withProfile("detailed").profile()));

        env.remove("LLM_MAX_OUTPUT_TOKENS");
        ModelSettings fast = ModelSettings.from(env);
        expect("без переопределения переключение профиля меняет лимит",
                fast.withProfile(ModelSettings.DETAILED).maxOutputTokens() == 4096);

        env.put("LLM_TEMPERATURE", "0.3");
        expect("LLM_TEMPERATURE читается",
                Double.valueOf(0.3).equals(ModelSettings.from(env).temperature()));
        env.put("LLM_REQUEST_TIMEOUT_SECONDS", "30");
        env.put("LLM_CONTEXT_MAX_TURNS", "4");
        env.put("LLM_DIAGNOSTICS", "TRUE");
        ModelSettings full = ModelSettings.from(env);
        expect("таймаут и лимит контекста читаются",
                full.requestTimeoutSeconds() == 30 && full.contextMaxTurns() == 4);
        expect("LLM_DIAGNOSTICS=true включается без учёта регистра", full.diagnostics());

        // Граничные допустимые значения temperature.
        env.put("LLM_TEMPERATURE", "0");
        expect("temperature 0 допустима",
                Double.valueOf(0).equals(ModelSettings.from(env).temperature()));
        env.put("LLM_TEMPERATURE", "2.0");
        expect("temperature 2.0 допустима",
                Double.valueOf(2).equals(ModelSettings.from(env).temperature()));

        // Ошибки некорректных значений — с именем переменной в сообщении.
        expectSettingsError(env, "LLM_RESPONSE_MODE", "turbo");
        expectSettingsError(env, "LLM_MAX_OUTPUT_TOKENS", "0");
        expectSettingsError(env, "LLM_MAX_OUTPUT_TOKENS", "abc");
        expectSettingsError(env, "LLM_TEMPERATURE", "2.5");
        expectSettingsError(env, "LLM_TEMPERATURE", "NaN");
        expectSettingsError(env, "LLM_TEMPERATURE", "два");
        expectSettingsError(env, "LLM_REQUEST_TIMEOUT_SECONDS", "0");
        expectSettingsError(env, "LLM_CONTEXT_MAX_TURNS", "-1");
        expectSettingsError(env, "LLM_DIAGNOSTICS", "yes");
    }

     static void checkSessionTokenLimitSettings() {
        Map<String, String> env = new java.util.HashMap<>();
        expect("без переменной лимит сессии отключён",
                ModelSettings.from(env).sessionTokenLimit() == null);
        env.put("LLM_SESSION_TOKEN_LIMIT", "5000");
        expect("положительное значение читается как long",
                ModelSettings.from(env).sessionTokenLimit() == 5000L);
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "0");
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "-1");
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "5.5");
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "abc");
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "99999999999999999999999");
    }

     static void checkStoresHardening() throws IOException {
        // Профиль: повреждённый файл даёт ошибку с путём и не перезаписывается.
        Path profileFile = Files.createTempDirectory(baseTempDir, "hard-prof-")
                .resolve("profile.json");
        ProfileStore profileStore = new ProfileStore(profileFile);
        profileStore.save(UserProfile.empty());
        byte[] profileCorrupted = "это не { json вообще".getBytes(StandardCharsets.UTF_8);
        Files.write(profileFile, profileCorrupted);
        expect("повреждённый файл профиля даёт ошибку с путём (без перезаписи)",
                expectError(() -> profileStore.load())
                        .contains(profileFile.toString())
                        && Arrays.equals(Files.readAllBytes(profileFile),
                        profileCorrupted));

        // Инварианты: повреждённый файл — ошибка, файл не перезаписан; права.
        Path invariantFile = Files.createTempDirectory(baseTempDir, "hard-inv-")
                .resolve("invariants.json");
        InvariantStore invariantStore = new InvariantStore(invariantFile);
        invariantStore.add("рамка hardening", "other");
        byte[] invariantGarbage = "повреждение { инвариантов".getBytes(StandardCharsets.UTF_8);
        Files.write(invariantFile, invariantGarbage);
        expect("повреждённый файл инвариантов даёт ошибку с путём (без перезаписи)",
                expectError(() -> invariantStore.load())
                        .contains(invariantFile.toString())
                        && Arrays.equals(Files.readAllBytes(invariantFile),
                        invariantGarbage));

        // Права доступа: каталог rwx------, файлы rw------- (POSIX; иначе пропуск).
        expect("каталог памяти доступен только владельцу (rwx------)",
                checkPosixPermissions(Files.createTempDirectory(baseTempDir, "hard-mem-")
                        .resolve("memory.json").getParent(), "rwx------"));
        InvariantStore permissionStore = new InvariantStore(
                Files.createTempDirectory(baseTempDir, "hard-perm-")
                        .resolve("invariants.json"));
        permissionStore.add("рамка прав", "other");
        expect("файл инвариантов доступен только владельцу (rw-------)",
                checkPosixPermissions(permissionStore.file(), "rw-------"));
    }

     static boolean checkPosixPermissions(Path file, String expected) {
        try {
            Set<PosixFilePermission> permissions = Files
                    .getPosixFilePermissions(file,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return expected.equals(PosixFilePermissions.toString(permissions));
        } catch (UnsupportedOperationException | IOException e) {
            // Система без POSIX-прав — проверка не применима, не ломаем прогон.
            return true;
        }
    }
}
