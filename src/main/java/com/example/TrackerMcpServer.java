package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP stdio-сервер с единственным инструментом чтения задачи из Трекера. */
public final class TrackerMcpServer implements AutoCloseable {
    static final String TOOL_NAME = "get-issue";
    static final String TRACKER_API = "https://api.tracker.yandex.net/v2";
    static final String TOKEN_ENV = "TRACKER_OAUTH_TOKEN";
    static final String IAM_TOKEN_ENV = "TRACKER_IAM_TOKEN";
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient;
    private final URI apiBase;
    private final String token;
    private final Duration requestTimeout;
    private final McpSyncServer server;

    public TrackerMcpServer() {
        this(HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build(),
                URI.create(System.getenv().getOrDefault("TRACKER_API_BASE", TRACKER_API)),
                trackerToken());
    }

    private static String trackerToken() {
        String oauth = System.getenv(TOKEN_ENV);
        if (oauth != null && !oauth.isBlank()) {
            return "OAuth " + oauth;
        }
        String iam = System.getenv(IAM_TOKEN_ENV);
        return iam != null && !iam.isBlank() ? "Bearer " + iam : null;
    }

    TrackerMcpServer(HttpClient httpClient, URI apiBase, String token) {
        this(httpClient, apiBase, token, HTTP_TIMEOUT);
    }

    TrackerMcpServer(HttpClient httpClient, URI apiBase, String token, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.apiBase = apiBase;
        this.token = token;
        this.requestTimeout = requestTimeout;
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapperSupplier().get());
        McpServerFeatures.SyncToolSpecification specification = McpServerFeatures.SyncToolSpecification.builder()
                .tool(toolDefinition())
                .callHandler((exchange, request) -> handle(request))
                .build();
        this.server = McpServer.sync(transport)
                .serverInfo("yandex-tracker", "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specification)
                .build();
    }

    static McpSchema.Tool toolDefinition() {
        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("Возвращает задачу из Яндекс.Трекера по ключу (название, статус, исполнитель, приоритет)")
                .inputSchema(McpSchema.JsonSchema.builder()
                        .type("object")
                        .properties(Map.of("issueKey", Map.of("type", "string",
                                "description", "Ключ задачи, например TEST-123")))
                        .required(List.of("issueKey"))
                        .additionalProperties(false)
                        .build())
                .outputSchema(Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string"),
                        "title", Map.of("type", "string"),
                        "status", Map.of("type", "string"),
                        "assignee", Map.of("type", "string"),
                        "priority", Map.of("type", "string"))))
                .build();
    }

    McpSchema.CallToolResult handle(McpSchema.CallToolRequest request) {
        Object rawKey = request.arguments() == null ? null : request.arguments().get("issueKey");
        if (!(rawKey instanceof String issueKey) || issueKey.isBlank()) {
            return error("Неверные аргументы: требуется непустой issueKey.");
        }
        if (token == null || token.isBlank()) {
            return error("Токен Яндекс.Трекера не задан.");
        }
        String key = issueKey.trim();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(apiBase.resolve("issues/" + URI.create(key)))
                    .timeout(requestTimeout)
                    .header("Authorization", token.startsWith("OAuth ") || token.startsWith("Bearer ")
                            ? token : "OAuth " + token)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            return trackerResponse(key, response);
        } catch (java.net.http.HttpTimeoutException e) {
            return error("Таймаут запроса к API Яндекс.Трекера.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("Запрос к API Яндекс.Трекера прерван.");
        } catch (IOException | IllegalArgumentException e) {
            return error("API Яндекс.Трекера недоступен.");
        }
    }

    private McpSchema.CallToolResult trackerResponse(String key, HttpResponse<String> response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return error("Токен Яндекс.Трекера неверен.");
        }
        if (response.statusCode() == 404) {
            return error("Задача " + key + " не найдена.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return error("API Яндекс.Трекера вернул HTTP-статус " + response.statusCode() + ".");
        }
        if (response.body() == null || response.body().isBlank()) {
            return error("API Яндекс.Трекера вернул пустой ответ.");
        }
        try {
            JsonNode issue = JSON.readTree(response.body());
            if (issue == null || issue.isNull()) {
                return error("API Яндекс.Трекера вернул пустой ответ.");
            }
            Map<String, String> result = new LinkedHashMap<>();
            result.put("key", text(issue, "key", key));
            result.put("title", text(issue, "summary", ""));
            result.put("status", nestedText(issue, "status", "name"));
            result.put("assignee", nestedText(issue, "assignee", "display"));
            result.put("priority", nestedText(issue, "priority", "name"));
            if (result.get("title").isBlank() || result.get("status").isBlank()) {
                return error("API Яндекс.Трекера вернул неполные данные задачи.");
            }
            return McpSchema.CallToolResult.builder()
                    .structuredContent(result)
                    .addTextContent(JSON.writeValueAsString(result))
                    .build();
        } catch (Exception e) {
            return error("API Яндекс.Трекера вернул некорректный JSON.");
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText("");
    }

    private static String nestedText(JsonNode node, String field, String nested) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : text(value, nested, value.isValueNode() ? value.asText("") : "");
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    @Override
    public void close() {
        server.closeGracefully();
    }

    public static void main(String[] args) {
        try (TrackerMcpServer ignored = new TrackerMcpServer()) {
            // StdioServerTransportProvider owns stdin/stdout and keeps the process alive.
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
