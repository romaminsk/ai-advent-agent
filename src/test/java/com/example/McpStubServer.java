package com.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Минимальный JSON-RPC stdio-сервер только для SelfTest MCP-клиента. */
public final class McpStubServer {

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "tools" : args[0];
        if ("stderr".equals(mode)) {
            System.err.println("npm ERR! 404 Not Found - package does not exist");
            return;
        }
        if ("exit".equals(mode)) {
            return;
        }
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.contains("\"method\":\"initialize\"")) {
                    if ("timeout".equals(mode) || "long-timeout".equals(mode)) {
                        Thread.sleep("long-timeout".equals(mode) ? 20_000 : 2_000);
                    }
                    reply(line, "{\"protocolVersion\":\"2025-11-25\","
                            + "\"capabilities\":{\"tools\":{}},"
                            + "\"serverInfo\":{\"name\":\"self-test\",\"version\":\"1\"}}");
                } else if (line.contains("\"method\":\"tools/list\"")) {
                    String tools = "empty".equals(mode) ? "[]"
                            : "[{\"name\":\"greet\",\"description\":\"Returns a greeting\","
                            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]";
                    reply(line, "{\"tools\":" + tools + "}");
                } else if (line.contains("\"method\":\"tools/call\"")) {
                    if (line.contains("missing")) {
                        reply(line, "{\"content\":[{\"type\":\"text\",\"text\":\"tool not found\"}],\"isError\":true}");
                    } else {
                        reply(line, "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}],"
                                + "\"structuredContent\":{\"ok\":true}}");
                    }
                }
            }
        }
    }

    private static void reply(String request, String result) {
        int idStart = request.indexOf("\"id\":") + 5;
        int idEnd = request.indexOf(',', idStart);
        if (idEnd < 0) idEnd = request.indexOf('}', idStart);
        String id = request.substring(idStart, idEnd).trim();
        String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"result\":" + result + "}";
        System.out.println(response);
        System.out.flush();
    }

    private McpStubServer() {
    }
}
