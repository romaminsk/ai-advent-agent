package com.example;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Изолированный MCP-клиент: discovery и явный вызов tools/call. */
public final class McpClientComponent {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String AUTH_TOKEN_ENV = "MCP_AUTH_TOKEN";

    public record ToolInfo(String name, String description) {
    }

    public record ToolListResult(TransportType transport, List<ToolInfo> tools) {
    }

    public record ToolCallResult(TransportType transport, boolean error, String text,
                                 Object structuredContent) {
    }

    public enum TransportType {
        STDIO, STREAMABLE_HTTP
    }

    private final Duration timeout;

    public McpClientComponent() {
        this(TIMEOUT);
    }

    McpClientComponent(Duration timeout) {
        this.timeout = timeout;
    }

    /** URL означает HTTP, всё остальное разбирается как команда процесса. */
    public ToolListResult listTools(String server) throws McpClientException {
        if (server == null || server.isBlank()) {
            throw new McpClientException("Сервер MCP не задан.");
        }
        String value = server.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return listHttpTools(value);
        }
        if (value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) {
            throw new McpClientException("URL MCP должна начинаться с http:// или https://.");
        }
        return listStdioTools(value);
    }

    public ToolCallResult callTool(String server, String toolName, Map<String, Object> arguments)
            throws McpClientException {
        if (server == null || server.isBlank() || toolName == null || toolName.isBlank()) {
            throw new McpClientException("Сервер и имя инструмента должны быть заданы.");
        }
        String value = server.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return callHttpTool(value, toolName, arguments);
        }
        if (value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) {
            throw new McpClientException("URL MCP должна начинаться с http:// или https://.");
        }
        return callStdioTool(value, toolName, arguments);
    }

    private ToolCallResult callStdioTool(String commandLine, String toolName,
                                         Map<String, Object> arguments) throws McpClientException {
        return callToolOnTransport(createStdioTransport(commandLine), TransportType.STDIO,
                toolName, arguments, new AtomicReference<>(""));
    }

    private ToolCallResult callHttpTool(String endpoint, String toolName,
                                        Map<String, Object> arguments) throws McpClientException {
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(endpoint)
                .connectTimeout(timeout);
        String token = System.getenv(AUTH_TOKEN_ENV);
        if (token != null && !token.isBlank()) {
            builder.httpRequestCustomizer((request, method, uri, body, context) ->
                    request.header("Authorization", "Bearer " + token));
        }
        return callToolOnTransport(builder.build(), TransportType.STREAMABLE_HTTP, toolName,
                arguments, new AtomicReference<>(""));
    }

    private ToolCallResult callToolOnTransport(io.modelcontextprotocol.spec.McpClientTransport transport,
                                               TransportType transportType, String toolName,
                                               Map<String, Object> arguments,
                                               AtomicReference<String> stderr) throws McpClientException {
        try (McpSyncClient client = McpClient.sync(transport)
                .initializationTimeout(timeout).requestTimeout(timeout).build()) {
            try {
                client.initialize();
                McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                        .name(toolName).arguments(arguments == null ? Map.of() : arguments).build());
                String text = result.content() == null ? "" : result.content().stream()
                        .filter(McpSchema.TextContent.class::isInstance)
                        .map(McpSchema.TextContent.class::cast)
                        .map(McpSchema.TextContent::text).reduce((a, b) -> a + "\n" + b).orElse("");
                return new ToolCallResult(transportType, Boolean.TRUE.equals(result.isError()), text,
                        result.structuredContent());
            } catch (Exception e) {
                throw new McpClientException(classifyCallError(e));
            }
        } catch (McpClientException e) {
            throw e;
        } catch (Exception e) {
            throw new McpClientException(classifyConnectionError(e, stderr.get(), transportType));
        }
    }

    private StdioClientTransport createStdioTransport(String commandLine) throws McpClientException {
        List<String> parts;
        try {
            parts = splitCommand(commandLine);
        } catch (IllegalArgumentException e) {
            throw new McpClientException("Неверная команда MCP: " + e.getMessage());
        }
        if (parts.isEmpty() || !commandExists(parts.get(0))) {
            throw new McpClientException("Не удалось запустить MCP stdio-сервер. Проверьте команду или пакет.");
        }
        StdioClientTransport transport = new StdioClientTransport(ServerParameters.builder(parts.get(0))
                .args(parts.subList(1, parts.size())).build(), new JacksonMcpJsonMapperSupplier().get());
        return transport;
    }

    private static String classifyCallError(Exception e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (current instanceof java.util.concurrent.TimeoutException
                    || message.contains("timeout") || message.contains("timed out")) {
                return "Истекло время ожидания запроса tools/call MCP.";
            }
            current = current.getCause();
        }
        return "MCP-сервер вернул ошибку при выполнении tools/call.";
    }

    private ToolListResult listStdioTools(String commandLine) throws McpClientException {
        List<String> parts;
        try {
            parts = splitCommand(commandLine);
        } catch (IllegalArgumentException e) {
            throw new McpClientException("Неверная команда MCP: " + e.getMessage());
        }
        if (parts.isEmpty()) {
            throw new McpClientException("Команда MCP не задана.");
        }
        if (!commandExists(parts.get(0))) {
            throw new McpClientException("Не удалось запустить MCP stdio-сервер."
                    + " Проверьте команду или пакет.");
        }

        ServerParameters parameters = ServerParameters.builder(parts.get(0))
                .args(parts.subList(1, parts.size()))
                .build();
        StdioClientTransport transport = new StdioClientTransport(
                parameters, new JacksonMcpJsonMapperSupplier().get());
        AtomicReference<String> stderr = new AtomicReference<>("");
        transport.setStdErrorHandler(message -> {
            stderr.updateAndGet(previous -> previous + message + "\n");
        });
        return discover(transport, TransportType.STDIO, stderr);
    }

    private ToolListResult listHttpTools(String endpoint) throws McpClientException {
        try {
            URI uri = new URI(endpoint);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new McpClientException("URL MCP должна начинаться с http:// или https://.");
            }
        } catch (URISyntaxException e) {
            throw new McpClientException("Неверный URL MCP.");
        }

        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport.builder(endpoint)
                        .connectTimeout(timeout);
        String token = System.getenv(AUTH_TOKEN_ENV);
        if (token != null && !token.isBlank()) {
            builder.httpRequestCustomizer((request, method, uri, body, context) ->
                    request.header("Authorization", "Bearer " + token));
        }
        return discover(builder.build(), TransportType.STREAMABLE_HTTP, new AtomicReference<>(""));
    }

    private ToolListResult discover(io.modelcontextprotocol.spec.McpClientTransport transport,
                                    TransportType transportType,
                                    AtomicReference<String> stderr) throws McpClientException {
        try {
            try (McpSyncClient client = McpClient.sync(transport)
                    .initializationTimeout(timeout)
                    .requestTimeout(timeout)
                    .build()) {
                try {
                    client.initialize();
                } catch (Exception e) {
                    throw new McpClientException(classifyConnectionError(e, stderr.get(), transportType));
                }
                McpSchema.ListToolsResult result;
                try {
                    result = client.listTools();
                } catch (Exception e) {
                    throw new McpClientException(classifyToolsError(e));
                }
                List<ToolInfo> tools = new ArrayList<>();
                for (McpSchema.Tool tool : result.tools()) {
                    tools.add(new ToolInfo(tool.name(), tool.description()));
                }
                return new ToolListResult(transportType, List.copyOf(tools));
            }
        } catch (McpClientException e) {
            throw e;
        } catch (Exception e) {
            throw new McpClientException(classifyConnectionError(e, stderr.get(), transportType));
        }
    }

    private static String classifyConnectionError(Exception e, String stderr,
                                                  TransportType transportType) {
        String safeError = transportType == TransportType.STDIO ? safeStderr(stderr) : "";
        if (!safeError.isEmpty()) {
            return "Не удалось запустить MCP stdio-сервер: " + safeError;
        }
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage() == null
                    ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (current instanceof java.util.concurrent.TimeoutException
                    || message.contains("timeout") || message.contains("timed out")) {
                return "Истекло время ожидания подключения или запроса MCP.";
            }
            if (transportType == TransportType.STDIO && current instanceof java.io.IOException
                    || message.contains("cannot run program")
                    || message.contains("no such file")) {
                return "Не удалось запустить MCP stdio-сервер. Проверьте команду или пакет.";
            }
            current = current.getCause();
        }
        return "Не удалось подключиться к MCP-серверу или получить tools/list."
                + " Проверьте сервер и его транспорт.";
    }

    private static String classifyToolsError(Exception e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage() == null
                    ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (current instanceof java.util.concurrent.TimeoutException
                    || message.contains("timeout") || message.contains("timed out")) {
                return "Истекло время ожидания запроса tools/list MCP.";
            }
            current = current.getCause();
        }
        return "MCP-сервер вернул ошибку при выполнении tools/list.";
    }

    private static String safeStderr(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String clean = stderr.replaceAll("\\u001B\\[[;\\d]*m", "")
                .replaceAll("\\s+", " ").trim();
        String token = System.getenv(AUTH_TOKEN_ENV);
        if (token != null && !token.isBlank()) {
            clean = clean.replace(token, "<скрыто>");
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("token") || lower.contains("secret") || lower.contains("password")
                || lower.contains("authorization") || lower.contains("api_key")
                || lower.contains("bearer ") || lower.contains("sk-")) {
            return "сервер завершился с ошибкой (подробности stderr скрыты).";
        }
        return clean.length() > 240 ? clean.substring(0, 240) + "..." : clean;
    }

    private static boolean commandExists(String command) {
        Path path = Path.of(command);
        if (path.getNameCount() > 1) {
            return Files.isExecutable(path);
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        for (String directory : pathEnv.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(directory, command))) {
                return true;
            }
        }
        return false;
    }

    static String format(ToolListResult result) {
        StringBuilder output = new StringBuilder("✓ MCP подключён (tools/list).\n");
        if (result.tools().isEmpty()) {
            return output.append("Инструментов нет.").toString();
        }
        output.append("Инструменты (" + result.tools().size() + "):\n");
        for (ToolInfo tool : result.tools()) {
            output.append("  - ").append(tool.name());
            if (tool.description() != null && !tool.description().isBlank()) {
                output.append(" — ").append(tool.description());
            }
            output.append('\n');
        }
        return output.toString().stripTrailing();
    }

    /** Минимальный shell-free разбор аргументов: процесс запускается напрямую. */
    static List<String> splitCommand(String commandLine) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (char c : commandLine.toCharArray()) {
            if (escaped) {
                token.append(c);
                escaped = false;
            } else if (c == '\\' && quote != '\'') {
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (token.length() > 0) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(c);
            }
        }
        if (escaped || quote != 0) throw new IllegalArgumentException("незакрытая кавычка");
        if (token.length() > 0) result.add(token.toString());
        return result;
    }

    public static final class McpClientException extends Exception {
        public McpClientException(String message) {
            super(message);
        }
    }
}
