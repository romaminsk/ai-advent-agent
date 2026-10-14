package com.example;

import com.example.index.Embedder;
import com.example.rag.RagService;
import com.example.review.ReviewLlmConfig;
import com.example.review.ReviewRunner;
import com.example.support.CrmHttpServer;
import com.example.support.CrmInit;
import com.example.support.CrmMcpServer;
import com.example.support.CrmStore;
import com.example.support.SupportChatCli;
import com.example.support.SupportChatService;
import com.example.support.SupportCommand;
import com.example.support.SupportLlm;
import com.example.support.SupportServer;
import com.example.support.SupportService;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.example.SelfTestSupport.expect;
import static com.example.SelfTestSupport.group;
import static com.example.SelfTestSupport.withChildClasspath;

/** Группа support: CRM HTTP, MCP mcp-crm, команда support с замокованным LLM. Без платных запросов. */
final class SupportChecks {

    private static final String TOKEN = "selftest-crm-token-0123456789";
    private static final String CUSTOMER_EMAIL = "user1@example.com";

    private SupportChecks() {
    }

    static void run() throws Exception {
        Path base = Files.createTempDirectory("selftest-support-");
        Path crmDir = base.resolve("crm");
        copyDemo(crmDir);
        group("support: CRM HTTP");
        checkCrmHttp(crmDir);
        group("support: crm-init");
        checkCrmInit(base);
        group("support: MCP mcp-crm");
        checkMcp(crmDir, base);
        group("support: команда support");
        checkSupportCommand(crmDir, base);
        group("support: облако, откат и поисковый запрос");
        checkFailover();
        checkSearchQuery();
        group("support: support-server");
        checkSupportServer(crmDir, base);
        group("support: разбор escalated");
        checkEscalationParsing();
        group("support: чат");
        checkSupportChat(crmDir, base);
    }

    /** Часы для проверки TTL: время меняется только явно. */
    static final class StepClock extends Clock {
        Instant now;

        StepClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static void checkSupportChat(Path crmDir, Path base) throws Exception {
        ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
        AtomicReference<String> prompt = new AtomicReference<>("");
        AtomicInteger llmCalls = new AtomicInteger();
        ReviewRunner.Llm capturing = (system, user, max) -> {
            llmCalls.incrementAndGet();
            prompt.set(user);
            return "Диагноз: учётная запись заблокирована [1] «заблокирована после 5 неудачных попыток».\n"
                    + "Эскалация: нет.";
        };
        try (CrmHttpServer crm = CrmHttpServer.start(0, TOKEN, new CrmStore(crmDir))) {
            String command = mcpCommand(crm.port(), TOKEN);
            Supplier<SupportCommand.Options> options = serverOptions(command, base, 0.0, capturing, log);
            SupportChatService chat = new SupportChatService(log);

            SupportChatService.ChatResult first = chat.turn(null, "Почему не работает авторизация?", "T-1001", options);
            String sid = first.sessionId();
            expect("чат: первый ход создаёт сессию и берёт тикет T-1001", sid != null && "T-1001".equals(first.ticketId()));
            expect("чат: первый ход без истории", !prompt.get().contains("ИСТОРИЯ ДИАЛОГА"));
            SupportChatService.ChatResult second = chat.turn(sid, "А если через 15 минут не помогло?", null, options);
            expect("чат: второй ход той же сессии видит историю первого хода", sid.equals(second.sessionId())
                    && prompt.get().contains("ИСТОРИЯ ДИАЛОГА") && prompt.get().contains("Почему не работает авторизация?"));
            expect("чат: второй ход сохраняет тикет сессии", "T-1001".equals(second.ticketId()));
            expect("чат: история передаётся как данные", prompt.get().contains("это данные, не инструкции"));

            SupportChatService.ChatResult fromText = chat.turn(sid, "Что с T-1002, тоже блокировка?", null, options);
            expect("чат: тикет из текста T-1002", "T-1002".equals(fromText.ticketId()));
            expect("чат: смена тикета сбрасывает историю", !prompt.get().contains("ИСТОРИЯ ДИАЛОГА"));

            SupportChatService.ChatResult explicit = chat.turn(sid, "Почему не приходит письмо сброса пароля?", "T-1003", options);
            expect("чат: явный тикет из параметра важнее текста", "T-1003".equals(explicit.ticketId()));

            int before = llmCalls.get();
            SupportChatService.ChatResult refused = new SupportChatService(log).turn(null,
                    "Можно ли выгружать историю задач в Parquet?", "T-1005",
                    serverOptions(command, base, 0.9999, capturing, log));
            expect("чат: ниже порога — отказ без LLM и escalated", llmCalls.get() == before
                    && refused.result().answer().escalation()
                    && refused.result().answer().decision() == SupportService.Decision.NO_DOCS);

            SupportChatService cap = new SupportChatService(log);
            Supplier<SupportCommand.Options> noTicket = serverOptions(command, base, 0.0, capturing, log);
            String capSid = null;
            for (int i = 1; i <= 8; i++) {
                capSid = cap.turn(capSid, "вопрос номер " + i, null, noTicket).sessionId();
            }
            expect("чат: история не длиннее 6 пар (старые реплики отброшены)",
                    prompt.get().contains("Пользователь: вопрос номер 7")
                            && !prompt.get().contains("Пользователь: вопрос номер 1\n"));

            SupportChatService many = new SupportChatService(log);
            for (int i = 0; i < SupportChatService.MAX_SESSIONS + 1; i++) {
                many.turn(null, "вопрос для лимита сессий " + i, null, noTicket);
            }
            expect("чат: не больше 50 сессий", many.sessionCount() <= SupportChatService.MAX_SESSIONS);

            StepClock clock = new StepClock(Instant.parse("2026-10-14T10:00:00Z"));
            SupportChatService ttl = new SupportChatService(log, clock);
            String ttlSid = ttl.turn(null, "Почему не работает авторизация?", null, noTicket).sessionId();
            clock.now = clock.now.plus(Duration.ofMinutes(31));
            ttl.turn(ttlSid, "А если через 15 минут не помогло?", null, noTicket);
            expect("чат: сессия истекает через 30 минут без активности", !prompt.get().contains("ИСТОРИЯ ДИАЛОГА"));

            expect("чат: поисковый запрос дополняется предыдущим вопросом, если текущий короче 10 слов",
                    SupportService.searchQuery(new SupportService.Input("Что дальше?", JsonSupport.MAPPER.readTree(
                            "{\"subject\":\"Не работает авторизация\"}"), null, "Почему не работает авторизация?", ""))
                            .contains("Почему не работает авторизация?"));
            expect("чат: длинный вопрос не дополняется предыдущим", !SupportService.searchQuery(new SupportService.Input(
                    "Я уже несколько раз вводил пароль, и вход по-прежнему не проходит, что делать дальше?",
                    null, null, "Почему не работает авторизация?", "")).contains("Почему не работает авторизация?"));

            ByteArrayOutputStream serverLog = new ByteArrayOutputStream();
            PrintStream serverPrint = new PrintStream(serverLog, true, StandardCharsets.UTF_8);
            try (SupportServer server = SupportServer.start(0, "selftest-chat-token-0123",
                    serverOptions(command, base, 0.0, capturing, serverPrint), serverPrint)) {
                String url = "http://127.0.0.1:" + server.port() + "/chat";
                HttpClient http = HttpClient.newHttpClient();
                String auth = "Bearer selftest-chat-token-0123";
                expect("/chat без токена: 401", post(http, url, null, "{\"message\":\"Почему?\"}").statusCode() == 401);
                expect("/chat без message: 400", post(http, url, auth, "{\"sessionId\":\"abcdefgh\"}").statusCode() == 400);
                expect("/chat неверный sessionId: 400", post(http, url, auth,
                        "{\"sessionId\":\"x\",\"message\":\"Почему?\"}").statusCode() == 400);
                HttpResponse<String> r1 = post(http, url, auth,
                        "{\"message\":\"Почему не работает авторизация?\",\"ticketId\":\"T-1001\"}");
                JsonNode j1 = JsonSupport.MAPPER.readTree(r1.body());
                String serverSid = j1.path("sessionId").asText();
                expect("/chat: 200, sessionId и llmSource cloud", r1.statusCode() == 200 && !serverSid.isEmpty()
                        && "cloud".equals(j1.path("llmSource").asText()));
                HttpResponse<String> r2 = post(http, url, auth, "{\"sessionId\":\"" + serverSid
                        + "\",\"message\":\"А если через 15 минут не помогло?\"}");
                expect("/chat: второй запрос той же сессии видит историю", r2.statusCode() == 200
                        && JsonSupport.MAPPER.readTree(r2.body()).path("sessionId").asText().equals(serverSid)
                        && prompt.get().contains("ИСТОРИЯ ДИАЛОГА"));
                HttpResponse<String> ask = post(http, "http://127.0.0.1:" + server.port() + "/ask", auth,
                        "{\"question\":\"Почему не работает авторизация?\",\"ticketId\":\"T-1001\"}");
                expect("/ask без изменений: 200 и без sessionId", ask.statusCode() == 200
                        && !JsonSupport.MAPPER.readTree(ask.body()).has("sessionId"));
                expect("/chat: токен не попадает в ответ и лог", !r2.body().contains("selftest-chat-token-0123")
                        && !serverLog.toString(StandardCharsets.UTF_8).contains("selftest-chat-token-0123"));
            }
        }

        List<String> seen = new ArrayList<>();
        SupportChatCli.Backend backend = (sid, message, ticket) -> {
            seen.add(sid + "|" + ticket + "|" + message);
            return new SupportChatCli.Turn("S1", "ответ", List.of(), null, false, "cloud", 5);
        };
        ByteArrayOutputStream cliOut = new ByteArrayOutputStream();
        int code = SupportChatCli.loop(null, backend, new BufferedReader(new StringReader(
                "Почему?\n/ticket t-1002\n/new\nДалее?\n/exit\nне должно попасть\n")),
                new PrintStream(cliOut, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream(), true,
                        StandardCharsets.UTF_8));
        expect("CLI: /ticket выбирает тикет, /new сбрасывает сессию, /exit завершает",
                code == 0 && seen.equals(List.of("null|null|Почему?", "null|T-1002|Далее?")));
    }


    private static void checkEscalationParsing() {
        List<Object[]> cases = List.of(
                new Object[]{"4) Эскалация: да, блокировка не снимается", true},
                new Object[]{"Эскалация: Нужна", true},
                new Object[]{"# 4) Эскалация\n\nДа — ошибка SSO связана с сертификатом", true},
                new Object[]{"**4) Эскалация**\n- Нет, блокировка снимается автоматически", false},
                new Object[]{"4) Эскалация: Нет", false},
                new Object[]{"Эскалация: не нужна", false},
                new Object[]{"- **Эскалация:** да", true},
                new Object[]{"Эскалация: даже если сбросить пароль, эскалация не нужна", false},
                new Object[]{"1) Диагноз: блокировка.\n2) Шаги: подождать.", false},
                new Object[]{"# 4) Эскалация", false},
                new Object[]{"4) эскалация\nда", true},
                new Object[]{"Эскалация предусмотрена только при блокировке.\n4) Эскалация\nНет", false},
                new Object[]{"4) Эскалация: нет\nЭскалация: да", true});
        for (Object[] item : cases) {
            String text = (String) item[0];
            boolean expected = (Boolean) item[1];
            expect("escalated: " + text.replace("\n", " / ") + " → " + expected,
                    SupportService.escalationRequested(text) == expected);
        }
        expect("escalated: пустой ответ → false", !SupportService.escalationRequested(null));
    }

    private static void checkSupportServer(Path crmDir, Path base) throws Exception {
        String serverToken = "selftest-support-api-token-0123";
        ByteArrayOutputStream serverLog = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(serverLog, true, StandardCharsets.UTF_8);
        ReviewRunner.Llm answering = (system, user, max) -> "Диагноз: учётная запись заблокирована [1] "
                + "«заблокирована после 5 неудачных попыток».\nЭскалация: нет.";
        HttpClient http = HttpClient.newHttpClient();
        try (CrmHttpServer crm = CrmHttpServer.start(0, TOKEN, new CrmStore(crmDir))) {
            String command = mcpCommand(crm.port(), TOKEN);
            try (SupportServer server = SupportServer.start(0, serverToken,
                    serverOptions(command, base, 0.0, answering, log), log)) {
                String url = "http://127.0.0.1:" + server.port();
                HttpResponse<String> health = get(http, url + "/health");
                expect("support-server GET /health: 200", health.statusCode() == 200 && health.body().contains("ok"));
                String auth = "Bearer " + serverToken;
                HttpResponse<String> noAuth = post(http, url + "/ask", null, "{\"question\":\"Почему?\"}");
                expect("support-server без токена: 401 JSON", noAuth.statusCode() == 401 && noAuth.body().contains("error"));
                HttpResponse<String> wrong = post(http, url + "/ask", "Bearer wrong", "{\"question\":\"Почему?\"}");
                expect("support-server с неверным токеном: 401", wrong.statusCode() == 401);
                HttpResponse<String> notJson = post(http, url + "/ask", auth, "не json");
                expect("support-server не JSON: 400", notJson.statusCode() == 400);
                HttpResponse<String> noQuestion = post(http, url + "/ask", auth, "{\"ticketId\":\"T-1001\"}");
                expect("support-server без question: 400", noQuestion.statusCode() == 400);
                String longQuestion = "а".repeat(SupportServer.MAX_QUESTION_CHARS + 1);
                HttpResponse<String> tooLong = post(http, url + "/ask", auth,
                        "{\"question\":\"" + longQuestion + "\"}");
                expect("support-server вопрос длиннее лимита: 400", tooLong.statusCode() == 400);
                HttpResponse<String> badTicket = post(http, url + "/ask", auth,
                        "{\"question\":\"Почему?\",\"ticketId\":\"T 1\"}");
                expect("support-server неверный ticketId: 400", badTicket.statusCode() == 400);
                HttpResponse<String> huge = post(http, url + "/ask", auth,
                        "{\"question\":\"" + "x".repeat(SupportServer.MAX_BODY_BYTES) + "\"}");
                expect("support-server тело больше 4096 байт: 413", huge.statusCode() == 413);
                HttpResponse<String> wrongMethod = get(http, url + "/ask");
                expect("support-server GET /ask: 405", wrongMethod.statusCode() == 405);

                HttpResponse<String> ticket = post(http, url + "/ask", auth,
                        "{\"question\":\"Почему не работает авторизация?\",\"ticketId\":\"T-1001\"}");
                JsonNode answer = JsonSupport.MAPPER.readTree(ticket.body());
                expect("support-server T-1001: 200, ответ, источники и llmSource cloud",
                        ticket.statusCode() == 200 && answer.path("answer").asText().contains("заблокирована")
                                && answer.path("sources").size() > 0 && "cloud".equals(answer.path("llmSource").asText())
                                && "T-1001".equals(answer.path("ticket").path("id").asText())
                                && answer.path("durationMs").isNumber());
                expect("support-server ответ не содержит токен и email клиента",
                        !ticket.body().contains(serverToken) && !ticket.body().contains(CUSTOMER_EMAIL));
                HttpResponse<String> missing = post(http, url + "/ask", auth,
                        "{\"question\":\"Почему?\",\"ticketId\":\"T-9999\"}");
                expect("support-server неизвестный тикет: 404", missing.statusCode() == 404);
            }
            try (SupportServer declining = SupportServer.start(0, serverToken,
                    serverOptions(command, base, 0.9999, answering, log), log)) {
                HttpResponse<String> escalated = post(http, "http://127.0.0.1:" + declining.port() + "/ask",
                        "Bearer " + serverToken,
                        "{\"question\":\"Можно ли выгружать историю задач в Parquet?\",\"ticketId\":\"T-1005\"}");
                JsonNode body = JsonSupport.MAPPER.readTree(escalated.body());
                expect("support-server T-1005: escalated true, без LLM", escalated.statusCode() == 200
                        && body.path("escalated").asBoolean() && body.path("llmSource").isNull()
                        && body.path("answer").asText().contains("В документации нет ответа"));
            }
        }

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReviewRunner.Llm slow = (system, user, max) -> {
            entered.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("тест не освободил LLM");
            }
            return "Диагноз: [1] «заблокирована после 5 неудачных попыток».";
        };
        try (CrmHttpServer crm = CrmHttpServer.start(0, TOKEN, new CrmStore(crmDir));
             SupportServer busy = SupportServer.start(0, serverToken,
                     serverOptions(mcpCommand(crm.port(), TOKEN), base, 0.0, slow, log), log)) {
            String url = "http://127.0.0.1:" + busy.port() + "/ask";
            String auth = "Bearer " + serverToken;
            String body = "{\"question\":\"Почему не работает авторизация?\",\"ticketId\":\"T-1001\"}";
            var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return post(http, url, auth, body);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            expect("support-server: первый запрос занял LLM", entered.await(20, TimeUnit.SECONDS));
            HttpResponse<String> second = post(http, url, auth, body);
            release.countDown();
            expect("support-server: второй параллельный запрос: 429", second.statusCode() == 429);
            expect("support-server: первый запрос завершён успешно", first.get(60, TimeUnit.SECONDS).statusCode() == 200);
        }
        expect("support-server: в логе только метод, путь и код, без токена",
                !serverLog.toString(StandardCharsets.UTF_8).contains(serverToken));
    }

    private static Supplier<SupportCommand.Options> serverOptions(String command, Path base, double threshold,
                                                                  ReviewRunner.Llm cloud, PrintStream log) {
        Path index = base.resolve("server-index");
        return () -> new SupportCommand.Options(command, new HashEmbedder(), "selftest-embedder",
                SupportLlm.create(List.of(
                        new ReviewRunner.Model(ReviewLlmConfig.MODE_CLOUD, "cloud-test", cloud,
                                "selftest-cloud-key-0123456789"),
                        new ReviewRunner.Model(ReviewLlmConfig.MODE_LOCAL, "local-test",
                                (system, user, max) -> "локальный ответ", "ollama")),
                        log, 60, 180, 120),
                Path.of("support-docs"), index, threshold);
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(HttpClient http, String url, String auth, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void checkFailover() throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream logStream = new PrintStream(log, true, StandardCharsets.UTF_8);
        String secret = "selftest-cloud-key-0123456789";
        ReviewRunner.Llm failing = (system, user, max) -> {
            throw new IllegalStateException("Сервер вернул HTTP-статус 503. Тело ошибки не выводится.");
        };
        ReviewRunner.Llm slow = (system, user, max) -> {
            Thread.sleep(3000);
            return "облачный ответ";
        };
        ReviewRunner.Llm local = (system, user, max) -> "локальный ответ";
        ReviewRunner.Model localModel = new ReviewRunner.Model(ReviewLlmConfig.MODE_LOCAL, "local-test", local, "ollama");

        SupportLlm http = SupportLlm.create(List.of(
                new ReviewRunner.Model(ReviewLlmConfig.MODE_CLOUD, "cloud-test", failing, secret), localModel),
                logStream, 1, 10, 2);
        String answer = http.complete("system", "user", 16);
        String logged = log.toString(StandardCharsets.UTF_8);
        expect("откат: ответ локальной модели после облака", answer.equals("локальный ответ")
                && http.lastMode().startsWith(ReviewLlmConfig.MODE_LOCAL));
        expect("откат: строка на каждую неудачную облачную попытку с причиной HTTP 503",
                logged.split("облако, попытка", -1).length - 1 == 3 && logged.contains("HTTP 503"));
        expect("откат: ключ не печатается в логе", !logged.contains(secret));

        log.reset();
        SupportLlm timeout = SupportLlm.create(List.of(
                new ReviewRunner.Model(ReviewLlmConfig.MODE_CLOUD, "cloud-test", slow, secret), localModel),
                logStream, 1, 10, 2);
        timeout.complete("system", "user", 16);
        expect("откат: таймаут облачной попытки фиксируется как timeout",
                log.toString(StandardCharsets.UTF_8).contains("timeout"));
    }

    private static void checkSearchQuery() throws Exception {
        JsonNode blockedCustomer = JsonSupport.MAPPER.readTree(
                "{\"plan\":\"Pro\",\"status\":\"blocked\",\"authMethod\":\"password\"}");
        JsonNode ssoCustomer = JsonSupport.MAPPER.readTree(
                "{\"plan\":\"Business\",\"status\":\"active\",\"authMethod\":\"sso\",\"email\":\"user3@example.com\"}");
        JsonNode t1001 = JsonSupport.MAPPER.readTree("{\"id\":\"T-1001\",\"subject\":\"Не работает авторизация\","
                + "\"description\":\"Учётная запись заблокирована\",\"status\":\"open\"}");
        JsonNode t1002 = JsonSupport.MAPPER.readTree("{\"id\":\"T-1002\",\"subject\":\"Не работает авторизация\","
                + "\"description\":\"Вход через SSO перестал работать: ошибка проверки подписи\",\"status\":\"open\"}");
        String q1 = SupportService.searchQuery(new SupportService.Input(
                "Почему не работает авторизация?", t1001, blockedCustomer));
        String q2 = SupportService.searchQuery(new SupportService.Input(
                "Почему не работает авторизация?", t1002, ssoCustomer));
        expect("поисковый запрос T-1001 и T-1002 различается (тикет и клиент)", !q1.equals(q2));
        expect("поисковый запрос содержит способ входа и без email", q2.contains("способ входа: sso")
                && !q2.contains("@"));
    }

    private static void copyDemo(Path target) throws IOException {
        Files.createDirectories(target);
        for (String file : List.of("customers.json", "tickets.json")) {
            Files.copy(Path.of("demo", "crm", file), target.resolve(file));
        }
    }

    private static void checkCrmHttp(Path crmDir) throws Exception {
        try (CrmHttpServer server = CrmHttpServer.start(0, TOKEN, new CrmStore(crmDir))) {
            String base = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> ok = send(http, "GET", base + "/api/v1/customers/C-1", "Bearer " + TOKEN);
            expect("CRM GET клиента с токеном: 200", ok.statusCode() == 200);
            expect("CRM клиент содержит статус blocked", ok.body().contains("\"status\":\"blocked\""));
            HttpResponse<String> ticket = send(http, "GET", base + "/api/v1/tickets/T-1001", "Bearer " + TOKEN);
            expect("CRM GET тикета с токеном: 200", ticket.statusCode() == 200
                    && ticket.body().contains("T-1001"));
            HttpResponse<String> tickets = send(http, "GET", base + "/api/v1/customers/C-2/tickets", "Bearer " + TOKEN);
            expect("CRM тикеты клиента: 200 и массив", tickets.statusCode() == 200 && tickets.body().startsWith("["));
            HttpResponse<String> noAuth = send(http, "GET", base + "/api/v1/customers/C-1", null);
            expect("CRM без токена: 401 JSON", noAuth.statusCode() == 401 && noAuth.body().contains("error"));
            HttpResponse<String> wrong = send(http, "GET", base + "/api/v1/customers/C-1", "Bearer wrong");
            expect("CRM с неверным токеном: 401", wrong.statusCode() == 401);
            HttpResponse<String> missing = send(http, "GET", base + "/api/v1/customers/C-99", "Bearer " + TOKEN);
            expect("CRM неизвестный клиент: 404 JSON", missing.statusCode() == 404 && missing.body().contains("error"));
            HttpResponse<String> missingTicket = send(http, "GET", base + "/api/v1/tickets/T-9999", "Bearer " + TOKEN);
            expect("CRM неизвестный тикет: 404", missingTicket.statusCode() == 404);
            HttpResponse<String> post = send(http, "POST", base + "/api/v1/customers/C-1", "Bearer " + TOKEN);
            expect("CRM только GET: POST → 405", post.statusCode() == 405);
        }
    }

    private static void checkCrmInit(Path base) throws Exception {
        Path target = base.resolve("crm-init-target");
        String oldCrm = System.getProperty("ai-agent.crm-dir");
        String oldProject = System.getProperty("ai-agent.project-dir");
        System.setProperty("ai-agent.crm-dir", target.toString());
        System.setProperty("ai-agent.project-dir", Path.of("").toAbsolutePath().normalize().toString());
        try {
            int first = CrmInit.run(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            expect("crm-init копирует демо-данные в пустой каталог", first == 0
                    && Files.isRegularFile(target.resolve("customers.json"))
                    && Files.isRegularFile(target.resolve("tickets.json")));
            ByteArrayOutputStream second = new ByteArrayOutputStream();
            int again = CrmInit.run(new PrintStream(second, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            expect("crm-init не перезаписывает непустой каталог", again == 0
                    && second.toString(StandardCharsets.UTF_8).contains("пропущено"));
        } finally {
            restore("ai-agent.crm-dir", oldCrm);
            restore("ai-agent.project-dir", oldProject);
        }
    }

    private static void checkMcp(Path crmDir, Path base) throws Exception {
        try (CrmHttpServer server = CrmHttpServer.start(0, TOKEN, new CrmStore(crmDir))) {
            String command = mcpCommand(server.port(), TOKEN);
            McpClientComponent mcp = new McpClientComponent();
            Set<String> names = new java.util.HashSet<>();
            for (McpClientComponent.ToolInfo tool : mcp.listTools(command).tools()) {
                names.add(tool.name());
            }
            expect("MCP tools/list: три инструмента CRM", names.equals(Set.of(
                    "get_customer", "get_ticket", "list_customer_tickets")));
            McpClientComponent.ToolCallResult ticket = mcp.callTool(command, "get_ticket",
                    Map.of("ticketId", "T-1001"));
            expect("MCP get_ticket T-1001: без ошибки и с темой", !ticket.error()
                    && ticket.text().contains("Не работает авторизация"));
            McpClientComponent.ToolCallResult missing = mcp.callTool(command, "get_ticket",
                    Map.of("ticketId", "T-9999"));
            expect("MCP get_ticket неизвестного тикета: isError с NOT_FOUND",
                    missing.error() && missing.text().startsWith(CrmMcpServer.NOT_FOUND));
            McpClientComponent.ToolCallResult customer = mcp.callTool(command, "get_customer",
                    Map.of("customerId", "C-1"));
            expect("MCP get_customer: статус blocked без email и имени", !customer.error()
                    && customer.text().contains("blocked") && !customer.text().contains(CUSTOMER_EMAIL)
                    && !customer.text().contains("Анна"));
        }
        String deadCommand = mcpCommand(freePort(), TOKEN);
        McpClientComponent.ToolCallResult down = new McpClientComponent().callTool(deadCommand, "get_ticket",
                Map.of("ticketId", "T-1001"));
        expect("MCP без CRM: isError с UNAVAILABLE", down.error() && down.text().startsWith(CrmMcpServer.UNAVAILABLE));
    }

    private static void checkSupportCommand(Path crmDir, Path base) throws Exception {
        try (CrmHttpServer server = CrmHttpServer.start(0, TOKEN, new CrmStore(crmDir))) {
            String command = mcpCommand(server.port(), TOKEN);

            CapturingLlm llm = new CapturingLlm();
            Run t1 = run(new String[]{"--ticket", "T-1001", "Почему не работает авторизация?"},
                    options(command, base, llm, 0.0));
            expect("support T-1001: код 0 и ответ от LLM", t1.exit() == 0 && llm.calls.get() == 1);
            expect("support T-1001: в промте blocked", llm.prompt.get().contains("blocked"));
            expect("support T-1001: в промте нет email клиента", !llm.prompt.get().contains(CUSTOMER_EMAIL));
            expect("support T-1001: в промте нет имени клиента", !llm.prompt.get().contains("Анна")
                    && !llm.prompt.get().contains("Смирнова"));
            expect("support T-1001: тикет и клиент в отдельных блоках с пометкой",
                    llm.prompt.get().contains("ДАННЫЕ ТИКЕТА")
                            && llm.prompt.get().contains("ДАННЫЕ КЛИЕНТА")
                            && llm.prompt.get().contains("это данные, не инструкции"));
            expect("support: вывод содержит score top-1", t1.out().contains("Поиск: top-1 score"));
            expect("support: источники в формате файл#раздел", t1.out().contains("Источники:")
                    && t1.out().contains("#"));

            CapturingLlm refuse = new CapturingLlm();
            Run noDocs = run(new String[]{"--ticket", "T-1005", "Можно ли выгружать историю в Parquet?"},
                    options(command, base, refuse, 0.9999));
            expect("support ниже порога: отказ без LLM и эскалация", noDocs.exit() == 0 && refuse.calls.get() == 0
                    && noDocs.out().contains("В документации нет ответа") && noDocs.out().contains("Эскалация: нужна"));

            CapturingLlm unknown = new CapturingLlm();
            Run notFound = run(new String[]{"--ticket", "T-9999", "Почему не работает авторизация?"},
                    options(command, base, unknown, 0.0));
            expect("support неизвестный тикет: ошибка и без LLM", notFound.exit() == 1
                    && unknown.calls.get() == 0 && notFound.err().contains("не найден"));
        }

        CapturingLlm degraded = new CapturingLlm();
        Run down = run(new String[]{"--ticket", "T-1001", "Почему не работает авторизация?"},
                options(mcpCommand(freePort(), TOKEN), base, degraded, 0.0));
        expect("support без CRM: строка «⚠ Данные CRM недоступны» и ответ по RAG",
                down.out().contains(SupportCommand.CRM_UNAVAILABLE) && degraded.calls.get() == 1
                        && down.exit() == 0);

        expect("PII: маскирование email в тексте тикета", SupportService.redact("пишите на user9@example.com")
                .equals("пишите на [email скрыт]"));
        expect("порог: значение вне 0..1 отвергается", rejects(() -> SupportCommand.Options.parseThreshold("2")));
    }

    private static boolean rejects(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }

    private static SupportCommand.Options options(String mcpCommand, Path base, RagService.LlmClient llm,
                                                  double threshold) {
        return new SupportCommand.Options(mcpCommand, new HashEmbedder(), "selftest-embedder", llm,
                Path.of("support-docs"), base.resolve("index-" + System.nanoTime()), threshold);
    }

    private record Run(int exit, String out, String err) {
    }

    private static Run run(String[] args, SupportCommand.Options options) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = SupportCommand.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), options);
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Команда дочернего MCP-процесса с переменными CRM через env (только для тестов). */
    private static String mcpCommand(int port, String token) {
        return "/usr/bin/env CRM_URL=http://127.0.0.1:" + port + " CRM_API_TOKEN=" + token + " "
                + CrmMcpServer.commandLine("", withChildClasspath());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HttpResponse<String> send(HttpClient http, String method, String url, String auth)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    /** LLM-заглушка: считает вызовы и сохраняет промт пользователя. */
    static final class CapturingLlm implements RagService.LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> prompt = new AtomicReference<>("");

        @Override
        public String complete(String system, String user, int maxOutputTokens) {
            calls.incrementAndGet();
            prompt.set(system + "\n" + user);
            return "Диагноз: учётная запись заблокирована после пяти неудачных попыток [1] «заблокирована после 5 неудачных попыток».\n"
                    + "Шаги:\n1. Подождите 15 минут.\n"
                    + "Источники: auth.md#Блокировка учётной записи.\n"
                    + "Эскалация: нет, блокировка снимается автоматически.";
        }
    }

    /** Детерминированный эмбеддер для тестов: хеш слов в 128 измерениях. */
    static final class HashEmbedder implements Embedder {
        @Override
        public int batchSize() {
            return 16;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                vectors.add(vector(text));
            }
            return vectors;
        }

        private static float[] vector(String text) {
            float[] vector = new float[128];
            for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 3) {
                    vector[Math.floorMod(token.hashCode(), vector.length)] += 1f;
                }
            }
            double norm = 0;
            for (float value : vector) {
                norm += value * value;
            }
            if (norm == 0) {
                vector[0] = 1f;
                return vector;
            }
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
            return vector;
        }
    }
}
