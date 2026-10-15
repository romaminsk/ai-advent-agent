package com.example;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP stdio-сервер файловых инструментов проекта: files_list, files_read, files_search, files_diff, files_write. */
public final class FilesMcpServer implements AutoCloseable {
    static final String PROJECT_ARGUMENT = "--project-root";
    static final List<String> TOOL_NAMES = List.of("files_list", "files_read", "files_search",
            "files_diff", "files_write");
    private final FilesTools tools;
    private final McpSyncServer server;

    public FilesMcpServer(Path projectRoot) {
        this.tools = new FilesTools(new FilesSandbox(projectRoot));
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapperSupplier().get());
        McpServerFeatures.SyncToolSpecification[] specs = {
                tool("files_list", "Список файлов проекта (путь, размер), отсортирован по пути",
                        Map.of("path", prop("string", "Каталог относительно корня проекта"),
                                "glob", prop("string", "Маска пути или имени файла"),
                                "maxResults", prop("integer", "Не больше 1000")),
                        List.of(), request -> list(request)),
                tool("files_read", "Текст файла по диапазону строк, sha256 всего файла",
                        Map.of("path", prop("string", "Путь к файлу относительно корня"),
                                "fromLine", prop("integer", "Первая строка, с 1"),
                                "toLine", prop("integer", "Последняя строка включительно"),
                                "maxChars", prop("integer", "Лимит символов ответа")),
                        List.of("path"), request -> read(request)),
                tool("files_search", "Поиск по текстовым файлам проекта: путь, строка, текст",
                        Map.of("query", prop("string", "Литеральная строка или регулярное выражение"),
                                "regex", prop("boolean", "Считать query регулярным выражением"),
                                "caseSensitive", prop("boolean", "Учитывать регистр"),
                                "glob", prop("string", "Маска пути или имени файла"),
                                "maxResults", prop("integer", "Не больше 500")),
                        List.of("query"), request -> search(request)),
                tool("files_diff", "Unified diff между файлом и предлагаемым содержимым (без записи)",
                        Map.of("path", prop("string", "Путь к файлу относительно корня"),
                                "newContent", prop("string", "Предлагаемое полное содержимое")),
                        List.of("path", "newContent"), request -> diff(request)),
                tool("files_write", "Атомарная запись текстового файла внутри песочницы",
                        Map.of("path", prop("string", "Путь к файлу относительно корня"),
                                "content", prop("string", "Полное содержимое файла"),
                                "expectedSha256", prop("string", "sha256 текущего файла для защиты от гонки"),
                                "createOnly", prop("boolean", "Отказать, если файл существует")),
                        List.of("path", "content"), request -> write(request))
        };
        this.server = McpServer.sync(transport)
                .serverInfo("local-files", "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specs)
                .build();
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
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
                .tool(McpSchema.Tool.builder().name(name).description(description).inputSchema(schema).build())
                .callHandler((exchange, request) -> handler.apply(request)).build();
    }

    McpSchema.CallToolResult list(McpSchema.CallToolRequest request) {
        return execute(request, args -> tools.list(string(args, "path"), string(args, "glob"),
                integer(args, "maxResults")), Set.of("path", "glob", "maxResults"));
    }

    McpSchema.CallToolResult read(McpSchema.CallToolRequest request) {
        return execute(request, args -> tools.read(string(args, "path"), integer(args, "fromLine"),
                integer(args, "toLine"), integer(args, "maxChars")),
                Set.of("path", "fromLine", "toLine", "maxChars"));
    }

    McpSchema.CallToolResult search(McpSchema.CallToolRequest request) {
        return execute(request, args -> tools.search(string(args, "query"), bool(args, "regex"),
                bool(args, "caseSensitive"), string(args, "glob"), integer(args, "maxResults")),
                Set.of("query", "regex", "caseSensitive", "glob", "maxResults"));
    }

    McpSchema.CallToolResult diff(McpSchema.CallToolRequest request) {
        return execute(request, args -> tools.diff(string(args, "path"), string(args, "newContent")),
                Set.of("path", "newContent"));
    }

    McpSchema.CallToolResult write(McpSchema.CallToolRequest request) {
        return execute(request, args -> tools.write(string(args, "path"), string(args, "content"),
                string(args, "expectedSha256"), bool(args, "createOnly")),
                Set.of("path", "content", "expectedSha256", "createOnly"));
    }

    private interface Action {
        Map<String, Object> apply(Map<String, Object> args);
    }

    private static McpSchema.CallToolResult execute(McpSchema.CallToolRequest request, Action action,
                                                    Set<String> allowed) {
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) return error("Неизвестный аргумент: " + key);
        }
        try {
            Map<String, Object> data = action.apply(args);
            return McpSchema.CallToolResult.builder()
                    .structuredContent(data)
                    .addTextContent(JsonSupport.MAPPER.writeValueAsString(data))
                    .build();
        } catch (FilesToolException e) {
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(e.getMessage() == null ? "неверные аргументы" : e.getMessage());
        } catch (Exception e) {
            return error("внутренняя ошибка файлового инструмента");
        }
    }

    private static McpSchema.CallToolResult error(String message) {
        String json;
        try {
            json = JsonSupport.MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            json = "{\"error\":\"внутренняя ошибка\"}";
        }
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(json).build();
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw new IllegalArgumentException("аргумент " + key + " должен быть строкой");
    }

    private static Integer integer(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        throw new IllegalArgumentException("аргумент " + key + " должен быть целым числом");
    }

    private static Boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Boolean flag) return flag;
        throw new IllegalArgumentException("аргумент " + key + " должен быть true или false");
    }

    @Override
    public void close() {
        server.closeGracefully();
    }

    /** Сервер живёт, пока жив клиент-родитель; при его завершении процесс не остаётся сиротой. */
    private static void awaitParentExit() throws InterruptedException {
        long parent = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
        while (ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L) == parent) {
            Thread.sleep(500);
        }
    }

    public static String command(Path projectRoot) {
        try {
            if (commandExists("ai-agent")) {
                return "ai-agent --mcp-server files " + PROJECT_ARGUMENT + " " + quote(projectRoot.toString());
            }
            return javaCommand(projectRoot);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось определить файловый MCP-сервер.", e);
        }
    }

    /** Запуск из текущего classpath: java -cp ... FilesMcpServer. */
    static String javaCommand(Path projectRoot) {
        try {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Path location = Path.of(FilesMcpServer.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath();
            String classpath = location.toString();
            if (Files.isDirectory(location)) {
                classpath = System.getProperty("java.class.path", classpath);
            }
            return quote(javaExecutable) + " -cp " + quote(classpath) + " " + FilesMcpServer.class.getName()
                    + " " + PROJECT_ARGUMENT + " " + quote(projectRoot.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось определить файловый MCP-сервер java.", e);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean commandExists(String command) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(directory, command))) return true;
        }
        return false;
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        if (args.length != 2 || !PROJECT_ARGUMENT.equals(args[0])) {
            System.err.println("Files MCP: требуется " + PROJECT_ARGUMENT + " <корень проекта>.");
            return 2;
        }
        try (FilesMcpServer ignored = new FilesMcpServer(Path.of(args[1]))) {
            awaitParentExit();
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Files MCP: корень проекта недоступен.");
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (RuntimeException e) {
            System.err.println("Files MCP: не удалось запустить сервер.");
            return 2;
        }
    }
}
