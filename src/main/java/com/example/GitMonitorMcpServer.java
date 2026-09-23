package com.example;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only MCP facade for explicit monitor-store commands. */
public final class GitMonitorMcpServer implements AutoCloseable {
    private final MonitorStore store;
    private final McpSyncServer server;

    public GitMonitorMcpServer() {
        this.store = MonitorStore.openDefault();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapperSupplier().get());
        McpServerFeatures.SyncToolSpecification[] tools = {
                tool("schedule-git-monitor", "Создать явное расписание Git-мониторинга",
                        Map.of("repoPath", Map.of("type", "string"),
                                "interval", Map.of("type", "string"),
                                "summaryInterval", Map.of("type", "string")),
                        List.of("repoPath", "interval"), this::schedule),
                tool("list-git-monitors", "Показать сохранённые расписания Git-мониторинга",
                        Map.of(), List.of(), this::list),
                tool("get-git-monitor-summary", "Получить последнюю агрегированную сводку Git-мониторинга",
                        Map.of("scheduleId", Map.of("type", "string")), List.of("scheduleId"), this::summary),
                tool("disable-git-monitor", "Отключить расписание Git-мониторинга",
                        Map.of("scheduleId", Map.of("type", "string")), List.of("scheduleId"), this::disable)
        };
        this.server = McpServer.sync(transport)
                .serverInfo("git-monitor", "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, String description,
            Map<String, Object> properties, List<String> required,
            java.util.function.Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name(name).description(description)
                        .inputSchema(schema).build())
                .callHandler((exchange, request) -> handler.apply(request)).build();
    }

    private McpSchema.CallToolResult schedule(McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = request.arguments();
            if (args == null || args.size() < 2 || args.size() > 3) return error("Неверные аргументы.");
            GitRepositoryReader.RepositoryLocation location = GitRepositoryReader.resolveLocation(
                    Path.of(String.valueOf(args.get("repoPath"))));
            Duration interval = MonitorSchedule.parseInterval(String.valueOf(args.get("interval")),
                    "interval", 30, 86_400);
            Duration summary = args.containsKey("summaryInterval")
                    ? MonitorSchedule.parseInterval(String.valueOf(args.get("summaryInterval")),
                    "summaryInterval", 60, 86_400) : Duration.ofHours(1);
            return success(store.create(location.repositoryRoot().toString(), interval, summary));
        } catch (Exception e) { return error(safe(e)); }
    }

    private McpSchema.CallToolResult list(McpSchema.CallToolRequest request) {
        return success(Map.of("schedules", store.schedules()));
    }

    private McpSchema.CallToolResult summary(McpSchema.CallToolRequest request) {
        try {
            String id = required(request, "scheduleId");
            List<MonitorSummary> values = store.summaries(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scheduleId", id);
            result.put("summary", values.isEmpty() ? null : values.get(values.size() - 1));
            return success(result);
        } catch (Exception e) { return error(safe(e)); }
    }

    private McpSchema.CallToolResult disable(McpSchema.CallToolRequest request) {
        try { return success(store.setEnabled(required(request, "scheduleId"), false)); }
        catch (Exception e) { return error(safe(e)); }
    }

    private static String required(McpSchema.CallToolRequest request, String key) {
        Object value = request.arguments() == null ? null : request.arguments().get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new MonitorException(key + " обязателен.");
        return text;
    }

    private static McpSchema.CallToolResult success(Object value) {
        return McpSchema.CallToolResult.builder().structuredContent(value)
                .addTextContent(value.toString()).build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    private static String safe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Ошибка Git monitor." : message;
    }

    @Override
    public void close() { server.closeGracefully(); }

    public static String command() {
        try {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Path location = Path.of(GitMonitorMcpServer.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath();
            String cp = location.toString();
            if (java.nio.file.Files.isDirectory(location)) cp = System.getProperty("java.class.path", cp);
            return quote(javaExecutable) + " -cp " + quote(cp) + " " + GitMonitorMcpServer.class.getName();
        } catch (Exception e) { throw new IllegalStateException("Не удалось определить Git monitor MCP.", e); }
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    public static void main(String[] args) {
        try (GitMonitorMcpServer ignored = new GitMonitorMcpServer()) {
            Thread.currentThread().join();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (RuntimeException e) { System.err.println("Git monitor MCP: не удалось запустить сервер."); }
    }
}
