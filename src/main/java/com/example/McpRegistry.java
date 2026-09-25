package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ограниченный каталог MCP-серверов для оркестрации. */
public final class McpRegistry implements AutoCloseable {
    public record ToolDescriptor(String server, String name, String description,
                                 Map<String, Object> schema) {
        public String qualifiedName() { return server + "." + name; }
    }

    public record ServerStatus(String name, boolean available, String reason) {}

    private final McpClientComponent client;
    private final Path repoRoot;
    private final Map<String, String> commands;
    private final Map<String, ServerStatus> statuses = new LinkedHashMap<>();
    private final Map<String, ToolDescriptor> tools = new LinkedHashMap<>();

    private McpRegistry(Map<String, String> commands, McpClientComponent client, Path repoRoot) {
        this.commands = new LinkedHashMap<>(commands);
        this.client = client;
        this.repoRoot = canonical(repoRoot);
    }

    public static McpRegistry open(Path repoRoot, Path resultsDir) {
        Map<String, String> commands = new LinkedHashMap<>();
        try {
            commands.put("git", GitMcpServer.orchestrationCommand(repoRoot));
        } catch (RuntimeException e) {
            commands.put("git", "");
        }
        try {
            commands.put("pipeline", PipelineMcpServer.command(resultsDir));
        } catch (RuntimeException e) {
            commands.put("pipeline", "");
        }
        McpRegistry registry = new McpRegistry(commands, new McpClientComponent(), repoRoot);
        registry.discover();
        return registry;
    }

    /** Тестовый конструктор сохраняет реальный stdio-клиент и позволяет подменить команды. */
    static McpRegistry open(Map<String, String> commands, McpClientComponent client) {
        McpRegistry registry = new McpRegistry(commands, client, null);
        registry.discover();
        return registry;
    }

    private void discover() {
        for (Map.Entry<String, String> entry : commands.entrySet()) {
            String server = entry.getKey();
            try {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    throw new McpClientComponent.McpClientException("команда сервера не определена");
                }
                McpClientComponent.ToolListResult listed = client.listTools(entry.getValue());
                statuses.put(server, new ServerStatus(server, true, null));
                for (McpClientComponent.ToolInfo tool : listed.tools()) {
                    tools.put(server + "." + tool.name(), descriptor(server, tool.name(), tool.description()));
                }
            } catch (Exception e) {
                statuses.put(server, new ServerStatus(server, false, safeReason(e)));
            }
        }
    }

    private static ToolDescriptor descriptor(String server, String name, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if ("git".equals(server)) {
            schema.put("repoPath", "string, absolute path");
        } else if ("search".equals(name)) {
            schema.put("root", "string, absolute directory");
            schema.put("query", "string");
            schema.put("maxResults", "integer, optional");
        } else if ("summarize".equals(name)) {
            schema.put("inputRef", "step:N");
        } else if ("saveToFile".equals(name)) {
            schema.put("inputRef", "step:N");
            schema.put("fileName", "string, optional");
        }
        return new ToolDescriptor(server, name, description, Map.copyOf(schema));
    }

    public List<ServerStatus> statuses() { return List.copyOf(statuses.values()); }
    public List<ToolDescriptor> tools() { return List.copyOf(tools.values()); }
    ServerStatus status(String name) { return statuses.get(name); }
    String repoRoot() { return repoRoot == null ? null : repoRoot.toString(); }
    boolean allowsRoot(String root) {
        if (repoRoot == null) return true;
        try { return Path.of(root).toRealPath().startsWith(repoRoot); }
        catch (Exception e) { return false; }
    }
    private static Path canonical(Path path) {
        if (path == null) return null;
        try { return path.toRealPath(); }
        catch (Exception e) { return path.toAbsolutePath().normalize(); }
    }
    public boolean has(String qualifiedName) { return tools.containsKey(qualifiedName); }
    ToolDescriptor tool(String qualifiedName) { return tools.get(qualifiedName); }

    McpClientComponent.ToolCallResult call(String server, String tool, Map<String, Object> args)
            throws McpClientComponent.McpClientException {
        if (!statuses.containsKey(server)) throw new McpClientComponent.McpClientException("Неизвестный сервер: " + server);
        if (!statuses.get(server).available()) throw new McpClientComponent.McpClientException(
                "Сервер " + server + " недоступен: " + statuses.get(server).reason());
        if (!has(server + "." + tool)) throw new McpClientComponent.McpClientException(
                "Неизвестный инструмент: " + server + "." + tool);
        return client.callTool(commands.get(server), tool, args);
    }

    private static String safeReason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "неизвестная причина" : message.replaceAll("[\\r\\n]+", " ");
    }

    @Override public void close() { /* stdio-клиенты закрываются после каждого call/list */ }
}
