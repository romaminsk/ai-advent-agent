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

final class IntegrationChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkTwoProcessIntegration();
        checkProfilePersistenceAcrossProcesses();
        checkOldHistoryCompatible();
    }

     static void checkOldHistoryCompatible() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":9,"
                        + "\"completion_tokens\":3}}").getBytes(StandardCharsets.UTF_8)));
        try {
            Path historyFile = Files.createTempDirectory(baseTempDir, "compat-")
                    .resolve("conversation.json");
            String sessionId = "compat-session-id";
            String oldJson = "{\"schemaVersion\":1,\"sessionId\":\"" + sessionId + "\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"старый вопрос\"},"
                    + "{\"role\":\"assistant\",\"content\":\"старый ответ\"}]}";
            Files.write(historyFile, oldJson.getBytes(StandardCharsets.UTF_8));

            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            try (JsonConversationStore store = new JsonConversationStore(historyFile)) {
                LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                        trustedHttpClient(keyStore), store);
                expect("история старого формата восстанавливается без изменений",
                        agent.getHistory().equals(List.of(
                                new ChatMessage("user", "старый вопрос"),
                                new ChatMessage("assistant", "старый ответ"))));
                agent.ask("новый вопрос");
                JsonNode saved = MAPPER.readTree(
                        Files.readString(historyFile, StandardCharsets.UTF_8));
                expect("новая запись сохраняет прежнюю схему и sessionId без новых полей",
                        saved.path("schemaVersion").asInt(-1)
                                == JsonConversationStore.SUPPORTED_SCHEMA_VERSION
                                && sessionId.equals(saved.path("sessionId").asText())
                                && saved.path("messages").size() == 4);
            }
            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                expect("файл, записанный после учёта токенов, читается прежним форматом",
                        reopened.load().messages().size() == 4);
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkTwoProcessIntegration() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        Path trustStore = createTrustStore(keyStore);
        AtomicInteger successCounter = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            bodies.add(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            String url = "https://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions";
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = buildClasspath();
            Path historyFile = Files.createTempDirectory(baseTempDir, "process-")
                    .resolve("conversation.json");

            // Первый запуск: ответ и пара user/assistant сохраняются на диск.
            String question = "Запомни кодовое слово: ЯКОРЬ-42. Ответь одним словом.";
            RunResult run1 = runAgentProcess(javaBin, classpath, trustStore, url, historyFile,
                    List.of(question, "/exit"));
            expect("первый запуск процесса завершился успешно"
                            + (run1.exitCode() == 0 ? "" : " — stderr: " + run1.stderr()),
                    run1.exitCode() == 0);
            expect("первый запуск не издаёт лишнего шума (новая беседа очевидна)",
                    !run1.stderr().contains("Начата новая беседа."));
            expect("первый запуск получил ответ от локального сервера",
                    run1.stdout().contains("Ответ 1"));

            // Второй запуск: контекст восстановлен и уходит в API.
            String followUp = "Какое кодовое слово я просил запомнить?";
            RunResult run2 = runAgentProcess(javaBin, classpath, trustStore, url, historyFile,
                    List.of(followUp, "/exit"));
            expect("второй запуск процесса завершился успешно"
                            + (run2.exitCode() == 0 ? "" : " — stderr: " + run2.stderr()),
                    run2.exitCode() == 0);
            expect("второй запуск сообщает о восстановлении контекста",
                    run2.stderr().contains("Восстановлено: 1 обмен"));
            expect("второй запуск получил ответ", run2.stdout().contains("Ответ 2"));

            expect("второй процесс отправил ровно один запрос к API", bodies.size() == 2);
            JsonNode secondRequest = MAPPER.readTree(bodies.get(1));
            JsonNode messages = secondRequest.path("messages");
            expect("второй запуск отправил восстановленную пару в API",
                    messages.size() == 4
                            && "system".equals(messages.get(0).path("role").asText())
                            && question.equals(messages.get(1).path("content").asText())
                            && "Ответ 1".equals(messages.get(2).path("content").asText())
                            && "user".equals(messages.get(3).path("role").asText())
                            && followUp.equals(messages.get(3).path("content").asText()));
            expect("история процесса не содержит ключ API",
                    !Files.readString(historyFile, StandardCharsets.UTF_8).contains("test-key"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
            Files.deleteIfExists(trustStore);
        }
    }

     static void checkProfilePersistenceAcrossProcesses() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = buildClasspath();
        // URL валиден по форме (HTTPS), эндпоинт недоступен — но API не вызывается.
        String apiUrl = "https://127.0.0.1:1/v1/chat/completions";
        Path profileFile = Files.createTempDirectory(baseTempDir, "prof-live-")
                .resolve("profile.json");
        Path memoryFile = Files.createTempDirectory(baseTempDir, "mem-live-")
                .resolve("memory.json");
        Path historyFile = Files.createTempDirectory(baseTempDir, "hist-live-")
                .resolve("conversation.json");
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_PROFILE_FILE", profileFile.toAbsolutePath().toString());
        env.put("LLM_MEMORY_FILE", memoryFile.toAbsolutePath().toString());

        // Первый «запуск»: задают имя, стиль, ограничение, скилл, пайплайн и память.
        RunResult run1 = runAgentProcess(javaBin, classpath, null, apiUrl, historyFile,
                List.of(
                        "/profile name ЯКОРЬ-42",
                        "/profile style кратко, по делу",
                        "/profile constraint только русский",
                        "/skill add \"карточка фичи\" название, цель, критерии, шаги",
                        "/pipeline \"напиши фичу\" карточка фичи",
                        "/remember код: ЯКОРЬ-42",
                        "/exit"),
                env);
        expect("первый запуск персистенции профиля завершился успешно"
                        + (run1.exitCode() == 0 ? "" : " — stderr: " + run1.stderr()),
                run1.exitCode() == 0);
        expect("боевой путь пишет в файл профиля по LLM_PROFILE_FILE",
                Files.exists(profileFile));
        expect("файл профиля содержит заданные поля (schemaVersion 1)",
                MAPPER.readTree(Files.readString(profileFile, StandardCharsets.UTF_8))
                        .path("profile").path("name").asText().equals("ЯКОРЬ-42")
                        && MAPPER.readTree(Files.readString(profileFile, StandardCharsets.UTF_8))
                        .path("schemaVersion").asInt() == ProfileStore.SUPPORTED_SCHEMA_VERSION);

        // Второй «запуск»: тот же файл LLM_PROFILE_FILE, новый процесс.
        RunResult run2 = runAgentProcess(javaBin, classpath, null, apiUrl, historyFile,
                List.of("/profile", "/memory", "/pipeline list", "/exit"), env);
        expect("второй запуск персистенции профиля завершился успешно"
                        + (run2.exitCode() == 0 ? "" : " — stderr: " + run2.stderr()),
                run2.exitCode() == 0);
        // PlainTerminalUi печатает ответы команд в stderr, а не stdout.
        String secondOut = run2.stdout() + "\n" + run2.stderr();
        expect("профиль переживает перезапуск: имя читается из файла",
                secondOut.contains("«ЯКОРЬ-42»"));
        expect("профиль переживает перезапуск: стиль читается",
                secondOut.contains("кратко, по делу"));
        expect("профиль переживает перезапуск: ограничение читается",
                secondOut.contains("только русский"));
        expect("скилл и пайплайн переживают перезапуск",
                secondOut.contains("карточка фичи")
                        && secondOut.contains("«напиши фичу»"));
        expect("долговременная память по LLM_MEMORY_FILE переживает перезапуск",
                secondOut.contains("код: ЯКОРЬ-42"));
        expect("файл памяти по LLM_MEMORY_FILE существует",
                Files.exists(memoryFile));
    }
}
