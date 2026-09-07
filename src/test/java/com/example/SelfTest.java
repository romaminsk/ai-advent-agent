package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Локальный самотест без платных запросов.
 * Поднимает встроенный JDK HTTPS-сервер с самоподписанным сертификатом
 * (Config требует HTTPS) и проверяет:
 * - ошибки конфигурации и пустой ввод;
 * - многошаговый диалог: состав и порядок messages, владение историей;
 * - неизменность истории при ошибках запроса;
 * - resetConversation, session ID, лимит истории целыми парами;
 * - снимок getHistory;
 * - локальные команды CLI без вызова API.
 *
 * Запуск: mvn test-compile exec:java@self-test
 */
public final class SelfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    /** Ожидаемый текст системной инструкции агента. */
    private static final String SYSTEM_PROMPT_TEXT =
            "Ты полезный ассистент. Учитывай историю диалога. "
                    + "Отвечай на языке пользователя, если он не попросил иначе. "
                    + "Если информации недостаточно, уточни вопрос.";

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        checkConfigErrors();
        checkEmptyQueryNoApiCall();
        checkDialogOnLocalServer();

        System.out.println("OK: все проверки пройдены (" + passed + ").");
    }

    // ---------- Проверки конфигурации ----------

    private static void checkConfigErrors() {
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

    private static String expectConfigError(String key, String url, String model) {
        try {
            new Config(key, url, model);
        } catch (AgentException e) {
            return e.getMessage();
        }
        return "";
    }

    // ---------- Пустой ввод: API не вызывается ----------

    private static void checkEmptyQueryNoApiCall() {
        Config config = new Config("test-key", "https://127.0.0.1:1/v1/chat/completions", "test-model");
        LlmAgent agent = new LlmAgent(config);
        // Порт 1 закрыт: если бы API вызывался, получили бы сетевую ошибку,
        // а не сообщение о пустом запросе.
        String message = expectAgentError(agent, "   ");
        expect("пустой ввод не вызывает API", message.contains("Пустой запрос"));
    }

    // ---------- Многошаговый диалог на локальном сервере ----------

    private static void checkDialogOnLocalServer() throws Exception {
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
                LlmAgent agent = new LlmAgent(config, trustedHttpClient(keyStore));

                // --- Первый запрос: system + user ---
                String question1 = "Ответь одним словом: столица Франции?";
                String answer1 = agent.ask(question1);
                expect("первый ответ получен от тестового сервера", "Ответ 1".equals(answer1));

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

                // --- Лимит истории: только целые старые пары ---
                for (int i = 1; i <= LlmAgent.MAX_HISTORY_TURNS + 1; i++) {
                    agent.ask("вопрос " + i);
                }
                // После сброса-запроса было 1 пара + 21 новая = 22 → старейшая удалена.
                List<ChatMessage> fullHistory = agent.getHistory();
                expect("история ограничена MAX_HISTORY_TURNS парами",
                        fullHistory.size() == LlmAgent.MAX_HISTORY_TURNS * 2);
                // Удалены пары «вопрос после сброса» и «вопрос 1»; остались целые пары 2..21.
                expect("удалены только самые старые целые пары",
                        "user".equals(fullHistory.get(0).role())
                                && "вопрос 2".equals(fullHistory.get(0).content())
                                && "user".equals(fullHistory.get(fullHistory.size() - 2).role())
                                && "вопрос 21".equals(fullHistory.get(fullHistory.size() - 2).content()));
                expect("пары в истории не разорваны (чередование user/assistant)",
                        rolesAlternate(fullHistory));

                String overflowQuestion = "вопрос " + (LlmAgent.MAX_HISTORY_TURNS + 2);
                agent.ask(overflowQuestion);
                JsonNode overflowBody = MAPPER.readTree(lastBody.get());
                JsonNode overflowMessages = overflowBody.path("messages");
                expect("запрос при полной истории содержит system, 20 пар и нового user",
                        overflowMessages.size() == 1 + LlmAgent.MAX_HISTORY_TURNS * 2 + 1);
                expect("в запросе остались только последние целые пары",
                        "system".equals(overflowMessages.get(0).path("role").asText())
                                && "вопрос 2".equals(overflowMessages.get(1).path("content").asText())
                                && "assistant".equals(overflowMessages.get(overflowMessages.size() - 2).path("role").asText())
                                && overflowQuestion.equals(overflowMessages.get(overflowMessages.size() - 1).path("content").asText())
                                && streamContents(overflowMessages).noneMatch("вопрос 1"::equals));

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
                        snapshot.size() == LlmAgent.MAX_HISTORY_TURNS * 2);

                // --- Локальные команды CLI не вызывают API ---
                checkCliCommands(config, hitCounter);
            } finally {
                server.stop(0);
            }
        } finally {
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Проверки локальных команд CLI ----------

    private static void checkCliCommands(Config config, AtomicInteger hitCounter) {
        int hitsBefore = hitCounter.get();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Main.run(reader("/help\n/history\n/reset\n/history\n/exit\n"),
                new PrintStream(output, true, StandardCharsets.UTF_8), config);
        String text = output.toString(StandardCharsets.UTF_8);
        expect("CLI: команды не вызывают API",
                hitCounter.get() == hitsBefore && !text.contains("Ошибка запроса"));
        expect("CLI: приветствие сообщает об истории и командах",
                text.contains("Агент учитывает историю текущей беседы.")
                        && text.contains("Команды: /help, /history, /reset, /exit."));
        expect("CLI: /help выводит справку",
                text.contains("Доступные команды:") && text.contains("/history"));
        expect("CLI: /history при пустой истории выводит заглушку",
                text.contains("История диалога пуста."));
        expect("CLI: /reset очищает историю",
                text.contains("Начата новая беседа. История очищена."));
        expect("CLI: /exit завершает приложение",
                text.contains("Работа завершена. История диалога не сохраняется."));

        output.reset();
        Main.run(reader("quit\n"), new PrintStream(output, true, StandardCharsets.UTF_8), config);
        expect("CLI: quit завершает работу без вызова API",
                hitCounter.get() == hitsBefore
                        && output.toString(StandardCharsets.UTF_8).contains("Работа завершена."));

        output.reset();
        Main.run(reader(""), new PrintStream(output, true, StandardCharsets.UTF_8), config);
        expect("CLI: EOF корректно завершает приложение",
                hitCounter.get() == hitsBefore
                        && output.toString(StandardCharsets.UTF_8).contains("Работа завершена."));
    }

    private static BufferedReader reader(String input) {
        return new BufferedReader(new StringReader(input));
    }

    // ---------- Сервисные методы тестового сервера ----------

    /** Генерирует временное PKCS12-хранилище с самоподписанным сертификатом. */
    private static Path createSelfSignedKeyStore() throws IOException, InterruptedException {
        Path keyStore = Files.createTempFile("selftest-keystore", ".p12");
        // keytool не перезаписывает существующее (пустое) хранилище — убираем файл.
        Files.deleteIfExists(keyStore);
        ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "selftest", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=localhost",
                "-ext", "san=ip:127.0.0.1",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-noprompt");
        if (pb.inheritIO().start().waitFor() != 0) {
            throw new IllegalStateException("Не удалось создать тестовый сертификат через keytool.");
        }
        return keyStore;
    }

    private static HttpsServer startHttpsServer(Path keyStorePath, Handler handler) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStorePath)) {
            keyStore.load(in, KEYSTORE_PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/v1/chat/completions", exchange -> {
            // Тело читаем один раз и используем и для захвата, и для выбора сценария.
            String requestBody =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response response = handler.handle(requestBody,
                    exchange.getRequestHeaders().getFirst("x-opencode-session"),
                    exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), response.body().length);
            try (var out = exchange.getResponseBody()) {
                out.write(response.body());
            }
        });
        server.start();
        return server;
    }

    /** HttpClient, доверяющий тестовому самоподписанному сертификату. */
    private static HttpClient trustedHttpClient(Path keyStorePath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStorePath)) {
            keyStore.load(in, KEYSTORE_PASSWORD);
        }
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder()
                .sslContext(clientContext)
                // JDK HttpsServer поддерживает только HTTP/1.1.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Простая пара «статус — тело ответа» тестового сервера. */
    private record Response(int status, byte[] body) {
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(String requestBody, String sessionHeader, String authHeader);
    }

    // ---------- Вспомогательные проверки ----------

    private static boolean historyEquals(List<ChatMessage> actual, ChatMessage... expected) {
        return actual.equals(List.of(expected));
    }

    private static boolean historyEquals(List<ChatMessage> actual, List<ChatMessage> expected) {
        return actual.equals(expected);
    }

    private static boolean rolesAlternate(List<ChatMessage> history) {
        for (int i = 0; i < history.size(); i++) {
            String expectedRole = (i % 2 == 0) ? "user" : "assistant";
            if (!expectedRole.equals(history.get(i).role())) {
                return false;
            }
        }
        return true;
    }

    private static java.util.stream.Stream<String> streamContents(JsonNode messages) {
        List<String> contents = new ArrayList<>();
        messages.forEach(m -> contents.add(m.path("content").asText()));
        return contents.stream();
    }

    private static String expectAgentError(LlmAgent agent, String marker) {
        try {
            agent.ask(marker);
        } catch (AgentException e) {
            return e.getMessage();
        }
        return "";
    }

    private static void expect(String description, boolean condition) {
        if (!condition) {
            throw new AssertionError("Проверка не пройдена: " + description);
        }
        passed++;
        System.out.println("OK: " + description);
    }

    private SelfTest() {
    }
}
