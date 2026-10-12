package com.example.assistant;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** MCP stdio-сервер команды «ai-agent mcp-git»: git_branch, git_status, git_diff, list_files (только чтение). */
public final class GitToolsMcpServer {

    static final String BRANCH = "git_branch";
    static final String STATUS = "git_status";
    static final String DIFF = "git_diff";
    static final String LIST_FILES = "list_files";
    static final String REPO_ROOT_ARGUMENT = "--repo-root";

    private GitToolsMcpServer() {
    }

    public static int run(String[] args) {
        Path start = Path.of("").toAbsolutePath();
        if (args.length == 2 && REPO_ROOT_ARGUMENT.equals(args[0])) {
            start = Path.of(args[1]);
        } else if (args.length != 0) {
            System.err.println("mcp-git: использование: ai-agent mcp-git [" + REPO_ROOT_ARGUMENT + " <путь>]");
            return 2;
        }
        GitReadTools tools;
        try {
            tools = new GitReadTools(GitReadTools.repositoryRoot(start));
        } catch (Exception e) {
            System.err.println("mcp-git: " + e.getMessage());
            return 2;
        }
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapperSupplier().get());
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("assistant-git", "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(
                        specification(BRANCH, "Текущая ветка репозитория (или короткий хеш при detached HEAD)",
                                Map.of(), tools, (t, a) -> t.branch()),
                        specification(STATUS, "Короткий статус рабочего дерева (git status --short)",
                                Map.of(), tools, (t, a) -> t.status()),
                        specification(DIFF, "Статистика и diff до 8 КБ; staged — индекс",
                                Map.of("staged", Map.of("type", "boolean", "description", "Показать staged-изменения")),
                                tools, (t, a) -> t.diff(Boolean.TRUE.equals(a.get("staged")))),
                        specification(LIST_FILES, "Список отслеживаемых файлов (git ls-files), до 200 строк",
                                Map.of("prefix", Map.of("type", "string", "description", "Необязательный префикс пути")),
                                tools, (t, a) -> t.listFiles(a.get("prefix") instanceof String s ? s : "")))
                .build();
        try {
            Thread.currentThread().join();
            server.closeGracefully();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.closeGracefully();
            return 0;
        }
    }

    @FunctionalInterface
    interface ToolAction {
        String run(GitReadTools tools, Map<String, Object> arguments) throws Exception;
    }

    static McpServerFeatures.SyncToolSpecification specification(String name, String description,
                                                                 Map<String, Object> properties,
                                                                 GitReadTools tools, ToolAction action) {
        McpSchema.Tool definition = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(McpSchema.JsonSchema.builder()
                        .type("object")
                        .properties(properties)
                        .required(List.of())
                        .additionalProperties(false)
                        .build())
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(definition)
                .callHandler((exchange, request) -> {
                    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                    try {
                        String text = action.run(tools, arguments);
                        return McpSchema.CallToolResult.builder().addTextContent(text).build();
                    } catch (Exception e) {
                        String message = e.getMessage() == null ? "Ошибка чтения Git." : e.getMessage();
                        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
                    }
                })
                .build();
    }
}
