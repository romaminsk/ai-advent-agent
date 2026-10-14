package com.example.support;

import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Pattern;

/** HTTP-API мини-CRM: только 127.0.0.1, только GET, Bearer-токен из CRM_API_TOKEN. */
public final class CrmHttpServer implements AutoCloseable {

    static final int DEFAULT_PORT = 8085;
    static final String API_PREFIX = "/api/v1/";
    static final String TOKEN_VARIABLE = "CRM_API_TOKEN";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,40}");

    private final HttpServer server;

    private CrmHttpServer(HttpServer server) {
        this.server = server;
    }

    public static CrmHttpServer start(int port, String token, CrmStore store) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> handle(exchange, token, store));
        server.start();
        return new CrmHttpServer(server);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Команда «ai-agent crm-server [--port N]»: блокирует поток до остановки процесса. */
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
            err.println("crm-server: " + e.getMessage() + ". Использование: ai-agent crm-server [--port 8085]");
            return 2;
        }
        String token = System.getenv(TOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            err.println("crm-server: " + TOKEN_VARIABLE + " не задан в .env проекта.");
            return 2;
        }
        Path directory = SupportPaths.crmDir();
        if (!Files.isDirectory(directory)) {
            err.println("crm-server: каталог данных " + directory + " не найден. Выполните: ai-agent crm-init");
            return 2;
        }
        try (CrmHttpServer server = start(port, token.strip(), new CrmStore(directory))) {
            out.println("crm-server: http://127.0.0.1:" + server.port() + ", данные: " + directory
                    + " (остановка: Ctrl+C)");
            Thread.currentThread().join();
            return 0;
        } catch (IOException e) {
            err.println("crm-server: не удалось запустить на 127.0.0.1:" + port + ": " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static void handle(HttpExchange exchange, String token, CrmStore store) throws IOException {
        try (exchange) {
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"), token)) {
                send(exchange, 401, error("Нужен заголовок Authorization: Bearer <токен>"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("Допускается только GET"));
                return;
            }
            Reply reply;
            try {
                reply = route(exchange.getRequestURI().getPath(), store);
            } catch (IOException e) {
                reply = new Reply(500, error("Не удалось прочитать данные CRM"));
            }
            send(exchange, reply.status(), reply.body());
        }
    }

    record Reply(int status, JsonNode body) {
    }

    static Reply route(String path, CrmStore store) throws IOException {
        if (path == null || !path.startsWith(API_PREFIX)) {
            return new Reply(404, error("Маршрут не найден"));
        }
        String[] parts = path.substring(API_PREFIX.length()).split("/");
        if (parts.length == 2 && "customers".equals(parts[0]) && ID.matcher(parts[1]).matches()) {
            return found(store.customer(parts[1]), "Клиент не найден");
        }
        if (parts.length == 2 && "tickets".equals(parts[0]) && ID.matcher(parts[1]).matches()) {
            return found(store.ticket(parts[1]), "Тикет не найден");
        }
        if (parts.length == 3 && "customers".equals(parts[0]) && "tickets".equals(parts[2])
                && ID.matcher(parts[1]).matches()) {
            if (store.customer(parts[1]).isEmpty()) {
                return new Reply(404, error("Клиент не найден"));
            }
            ArrayNode tickets = JsonSupport.MAPPER.createArrayNode();
            store.ticketsOf(parts[1]).forEach(tickets::add);
            return new Reply(200, tickets);
        }
        return new Reply(404, error("Маршрут не найден"));
    }

    private static Reply found(Optional<JsonNode> record, String notFound) {
        return record.map(node -> new Reply(200, node))
                .orElseGet(() -> new Reply(404, error(notFound)));
    }

    static boolean authorized(String header, String token) {
        String expected = "Bearer " + token;
        if (header == null) {
            return false;
        }
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode error(String message) {
        ObjectNode node = JsonSupport.MAPPER.createObjectNode();
        node.put("error", message);
        return node;
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
