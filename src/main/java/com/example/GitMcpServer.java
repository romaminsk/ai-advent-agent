package com.example;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Собственный MCP stdio-сервер для чтения состояния одного Git-репозитория. */
public final class GitMcpServer implements AutoCloseable {
    static final String TOOL_NAME = "get-repository-status";
    static final String ROOT_ARGUMENT = "--repo-root";
    private final GitRepositoryReader reader;
    private final McpSyncServer server;

    public GitMcpServer(Path allowedRoot) throws GitRepositoryReader.GitRepositoryException {
        this.reader = new GitRepositoryReader(allowedRoot);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapperSupplier().get());
        McpServerFeatures.SyncToolSpecification specification =
                McpServerFeatures.SyncToolSpecification.builder()
                        .tool(toolDefinition())
                        .callHandler((exchange, request) -> handle(request))
                        .build();
        this.server = McpServer.sync(transport)
                .serverInfo("local-git", "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specification)
                .build();
    }

    static McpSchema.Tool toolDefinition() {
        return McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("Возвращает текущее состояние локального Git-репозитория")
                .inputSchema(McpSchema.JsonSchema.builder()
                        .type("object")
                        .properties(Map.of("repoPath", Map.of("type", "string",
                                "description", "Абсолютный путь к разрешённому Git-репозиторию")))
                        .required(List.of("repoPath"))
                        .additionalProperties(false)
                        .build())
                .outputSchema(Map.of("type", "object", "properties", Map.of(
                        "repositoryRoot", Map.of("type", "string"),
                        "branch", Map.of("type", List.of("string", "null")),
                        "detachedHead", Map.of("type", "boolean"),
                        "headCommit", Map.of("type", List.of("string", "null")),
                        "clean", Map.of("type", "boolean"),
                        "staged", Map.of("type", "array", "items", Map.of("type", "string")),
                        "unstaged", Map.of("type", "array", "items", Map.of("type", "string")),
                        "untracked", Map.of("type", "array", "items", Map.of("type", "string")),
                        "conflicts", Map.of("type", "array", "items", Map.of("type", "string")))))
                .build();
    }

    McpSchema.CallToolResult handle(McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        Object value = arguments == null ? null : arguments.get("repoPath");
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("repoPath")) {
            return error("Неверные аргументы: разрешён только непустой абсолютный repoPath.");
        }
        if (!(value instanceof String repoPath) || repoPath.isBlank()) {
            return error("Неверные аргументы: требуется непустой абсолютный repoPath.");
        }
        try {
            GitRepositoryStatus status = reader.read(Path.of(repoPath));
            return McpSchema.CallToolResult.builder()
                    .structuredContent(status.toMap())
                    .addTextContent(new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(status.toMap()))
                    .build();
        } catch (GitRepositoryReader.GitRepositoryException | RuntimeException e) {
            return error(e.getMessage() == null ? "Не удалось прочитать Git-репозиторий." : e.getMessage());
        } catch (Exception e) {
            return error("Не удалось сформировать результат Git.");
        }
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    @Override
    public void close() {
        server.closeGracefully();
    }

    public static String command(Path allowedRoot) {
        try {
            Path location = Path.of(GitMcpServer.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath();
            if (java.nio.file.Files.isRegularFile(location) && commandExists("ai-agent")) {
                return "ai-agent --mcp-server git --repo-root " + quote(allowedRoot.toString());
            }
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = location.toString();
            if (java.nio.file.Files.isDirectory(location)) {
                String runtimeClasspath = System.getProperty("java.class.path", "");
                if (!runtimeClasspath.isBlank()) {
                    classpath = runtimeClasspath;
                }
            }
            return quote(javaExecutable) + " -cp " + quote(classpath) + " " + GitMcpServer.class.getName()
                    + " " + ROOT_ARGUMENT + " " + quote(allowedRoot.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось определить установленный MCP-сервер Git.", e);
        }
    }

    /** Команда для оркестрации: использует установленный shaded CLI, если он доступен. */
    static String orchestrationCommand(Path allowedRoot) {
        if (commandExists("ai-agent")) {
            return "ai-agent --mcp-server git --repo-root " + quote(allowedRoot.toString());
        }
        return command(allowedRoot);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean commandExists(String command) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (java.nio.file.Files.isExecutable(Path.of(directory, command))) return true;
        }
        return false;
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        if (args.length != 2 || !ROOT_ARGUMENT.equals(args[0])) {
            System.err.println("Git MCP: требуется " + ROOT_ARGUMENT + " <корень репозитория>");
            return 2;
        }
        try (GitMcpServer ignored = new GitMcpServer(Path.of(args[1]))) {
            Thread.currentThread().join();
            return 0;
        } catch (GitRepositoryReader.GitRepositoryException e) {
            System.err.println("Git MCP: " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (RuntimeException e) {
            System.err.println("Git MCP: не удалось запустить сервер.");
            return 2;
        }
    }
}
