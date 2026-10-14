package com.example.support;

import com.example.JsonSupport;
import com.example.review.ReviewLlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * HTTP-сервер support: POST /ask и GET /health на 127.0.0.1. Тот же поток, что у команды support
 * (SupportPipeline). Доступ по Bearer-токену SUPPORT_API_TOKEN, одновременно не больше одного запроса к LLM.
 */
public final class SupportServer implements AutoCloseable {

    static final int DEFAULT_PORT = 8090;
    static final String TOKEN_VARIABLE = "SUPPORT_API_TOKEN";
    public static final int MAX_BODY_BYTES = 4096;
    public static final int MAX_QUESTION_CHARS = 500;
    private static final Pattern TICKET_ID = Pattern.compile("[A-Za-z0-9-]{1,40}");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private record Reply(int status, JsonNode body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Semaphore llmSlot = new Semaphore(1);
    private final String token;
    private final Supplier<SupportCommand.Options> options;
    private final PrintStream log;
    private final SupportChatService chat;

    private SupportServer(HttpServer server, String token, Supplier<SupportCommand.Options> options,
                          PrintStream log) {
        this.server = server;
        this.token = token;
        this.options = options;
        this.log = log;
        this.chat = new SupportChatService(log);
    }

    /** Запускает сервер; options вызывается на каждый запрос (свежий бюджет LLM). */
    public static SupportServer start(int port, String token, Supplier<SupportCommand.Options> options,
                                      PrintStream log) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        SupportServer instance = new SupportServer(http, token, options, log);
        http.createContext("/", instance::handle);
        http.setExecutor(instance.executor);
        http.start();
        return instance;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** Команда «ai-agent support-server [--port 8090]»: блокирует поток до остановки процесса. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        int port = DEFAULT_PORT;
        try {
            for (int i = 0; i < args.length; i++) {
                if ("--port".equals(args[i]) && i + 1 < args.length) {
                    port = Integer.parseInt(args[++i]);
                } else if (args[i].startsWith("--port=")) {
                    port = Integer.parseInt(args[i].substring("--port=".length()));
                } else {
                    throw new IllegalArgumentException("неизвестный аргумент: " + args[i]);
                }
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("порт должен быть в диапазоне 1..65535");
            }
        } catch (IllegalArgumentException e) {
            err.println("support-server: " + e.getMessage() + ". Использование: ai-agent support-server [--port 8090]");
            return 2;
        }
        String token = System.getenv(TOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            err.println("support-server: " + TOKEN_VARIABLE + " не задан в .env.");
            return 2;
        }
        try {
            SupportCommand.Options.fromEnvironment(err);
        } catch (IllegalStateException | com.example.AgentException e) {
            err.println("support-server: " + e.getMessage());
            return 2;
        }
        try (SupportServer server = start(port, token.strip(),
                () -> SupportCommand.Options.fromEnvironment(err), err)) {
            out.println("support-server: http://127.0.0.1:" + server.port()
                    + " (POST /ask, GET /health; остановка: Ctrl+C)");
            Thread.currentThread().join();
            return 0;
        } catch (IOException e) {
            err.println("support-server: не удалось запустить на 127.0.0.1:" + port + ": " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try (exchange) {
            Reply reply = route(exchange, path, method);
            send(exchange, reply);
            log.println(method + " " + path + " " + reply.status() + " "
                    + (System.nanoTime() - started) / 1_000_000 + " мс");
        }
    }

    private Reply route(HttpExchange exchange, String path, String method) throws IOException {
        if ("/health".equals(path)) {
            return "GET".equals(method) ? new Reply(200, object("status", "ok"))
                    : new Reply(405, error("Допускается только GET"));
        }
        if ("/ask".equals(path)) {
            return ask(exchange, method, false);
        }
        if ("/chat".equals(path)) {
            return ask(exchange, method, true);
        }
        return new Reply(404, error("Маршрут не найден"));
    }

    private Reply ask(HttpExchange exchange, String method, boolean chat) throws IOException {
        if (!"POST".equals(method)) {
            return new Reply(405, error("Допускается только POST"));
        }
        if (!CrmHttpServer.authorized(exchange.getRequestHeaders().getFirst("Authorization"), token)) {
            return new Reply(401, error("Нужен заголовок Authorization: Bearer <токен>"));
        }
        byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            return new Reply(413, error("Тело запроса больше " + MAX_BODY_BYTES + " байт"));
        }
        JsonNode body;
        try {
            body = JsonSupport.MAPPER.readTree(raw);
        } catch (IOException e) {
            return new Reply(400, error("Тело запроса должно быть JSON"));
        }
        if (body == null || !body.isObject()) {
            return new Reply(400, error("Тело запроса должно быть JSON-объектом"));
        }
        String field = chat ? "message" : "question";
        String question = body.path(field).isTextual() ? body.get(field).asText().strip() : "";
        if (question.isEmpty()) {
            return new Reply(400, error("Нужно непустое поле " + field));
        }
        if (question.codePointCount(0, question.length()) > MAX_QUESTION_CHARS) {
            return new Reply(400, error(field + " длиннее " + MAX_QUESTION_CHARS + " символов"));
        }
        String sessionId = null;
        if (chat && body.has("sessionId") && !body.get("sessionId").isNull()) {
            if (!body.get("sessionId").isTextual() || !SESSION_ID.matcher(body.get("sessionId").asText()).matches()) {
                return new Reply(400, error("sessionId: 8–64 символа: буквы, цифры и дефис"));
            }
            sessionId = body.get("sessionId").asText();
        }
        String ticketId = null;
        if (body.has("ticketId") && !body.get("ticketId").isNull()) {
            if (!body.get("ticketId").isTextual() || !TICKET_ID.matcher(body.get("ticketId").asText()).matches()) {
                return new Reply(400, error("ticketId: буквы, цифры и дефис, например T-1001"));
            }
            ticketId = body.get("ticketId").asText();
        }
        if (!llmSlot.tryAcquire()) {
            return new Reply(429, error("Уже обрабатывается другой запрос, повторите позже"));
        }
        try {
            return chat ? chatTurn(sessionId, question, ticketId) : answer(question, ticketId);
        } finally {
            llmSlot.release();
        }
    }

    private Reply answer(String question, String ticketId) {
        long started = System.nanoTime();
        try {
            return answerReply(SupportPipeline.ask(options.get(), question, ticketId, null, false, log),
                    started, null);
        } catch (SupportPipeline.NotFoundException e) {
            return new Reply(404, error(e.getMessage()));
        } catch (Exception e) {
            log.println("support: внутренняя ошибка (" + e.getClass().getSimpleName() + ")");
            return new Reply(500, error("Внутренняя ошибка support"));
        }
    }

    private Reply chatTurn(String sessionId, String message, String ticketId) {
        long started = System.nanoTime();
        try {
            SupportChatService.ChatResult turn = chat.turn(sessionId, message, ticketId, options);
            return answerReply(turn.result(), started, turn.sessionId());
        } catch (SupportPipeline.NotFoundException e) {
            return new Reply(404, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return new Reply(400, error(e.getMessage()));
        } catch (Exception e) {
            log.println("support: внутренняя ошибка (" + e.getClass().getSimpleName() + ")");
            return new Reply(500, error("Внутренняя ошибка support"));
        }
    }

    /** Общий JSON для /ask и /chat; sessionId добавляется только в чате. */
    private static Reply answerReply(SupportPipeline.Result result, long started, String sessionId) {
        SupportService.Answer answer = result.answer();
        if (answer.decision() == SupportService.Decision.LLM_FAILED) {
            return new Reply(503, error(answer.text()));
        }
        ObjectNode out = JsonSupport.MAPPER.createObjectNode();
        out.put("answer", answer.text());
        ArrayNode sources = out.putArray("sources");
        answer.sources().forEach(chunk -> sources.add(chunk.source() + "#" + chunk.section()));
        JsonNode ticket = result.ticket();
        if (ticket == null) {
            out.putNull("ticket");
        } else {
            ObjectNode summary = out.putObject("ticket");
            summary.put("id", ticket.path("id").asText());
            summary.put("subject", ticket.path("subject").asText());
            summary.put("status", ticket.path("status").asText());
        }
        out.put("crmAvailable", !result.crmUnavailable());
        out.put("escalated", answer.escalation());
        String source = llmSource(result.llmMode());
        if (source == null) {
            out.putNull("llmSource");
        } else {
            out.put("llmSource", source);
        }
        out.put("durationMs", (System.nanoTime() - started) / 1_000_000);
        if (sessionId != null) {
            out.put("sessionId", sessionId);
        }
        return new Reply(200, out);
    }

    /** cloud — ответ облака; local — локальная модель (облако недоступно); null — LLM не вызывалась. */
    static String llmSource(String mode) {
        if (mode == null) {
            return null;
        }
        if (mode.startsWith(ReviewLlmConfig.MODE_CLOUD)) {
            return "cloud";
        }
        return mode.startsWith(ReviewLlmConfig.MODE_LOCAL) ? "local" : null;
    }

    private static JsonNode error(String message) {
        return object("error", message);
    }

    private static ObjectNode object(String name, String value) {
        ObjectNode node = JsonSupport.MAPPER.createObjectNode();
        node.put(name, value);
        return node;
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(reply.body());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
