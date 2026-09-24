package com.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Собственный MCP stdio-сервер с тремя инструментами композиции:
 * search (поиск по файлам), summarize (детерминированная сводка),
 * saveToFile (сохранение Markdown). Между шагами целостность входа
 * подтверждается SHA-256; модель не вызывается.
 */
public final class PipelineMcpServer implements AutoCloseable {

    static final String SEARCH_TOOL = FileSearcher.TOOL_NAME;
    static final String SUMMARIZE_TOOL = SearchSummarizer.TOOL_NAME;
    static final String SAVE_TOOL = ResultFileWriter.TOOL_NAME;
    static final String RESULTS_ARGUMENT = "--results-dir";
    static final List<String> TOOL_NAMES = List.of(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TOOL);

    private final ResultFileWriter writer;
    private final McpSyncServer server;

    /** Каталог результатов: явный путь или ~/.ai-advent-agent/pipeline-results по умолчанию. */
    public PipelineMcpServer(Path resultsDir) throws IOException {
        this.writer = new ResultFileWriter(resultsDir);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapperSupplier().get());
        McpServerFeatures.SyncToolSpecification[] tools = {
                tool(SEARCH_TOOL, "Литеральный регистронезависимый поиск по текстовым файлам каталога",
                        Map.of("root", Map.of("type", "string"),
                                "query", Map.of("type", "string"),
                                "maxResults", Map.of("type", "integer")),
                        List.of("root", "query"), PipelineMcpServer::search),
                tool(SUMMARIZE_TOOL, "Детерминированная сводка по результату search (модель не вызывается)",
                        Map.of("searchResult", Map.of("type", "object",
                                "description", "Объект ровно как вернул search")),
                        List.of("searchResult"), PipelineMcpServer::summarize),
                tool(SAVE_TOOL, "Сохранить сводку summarize в Markdown-файл результатов",
                        Map.of("summary", Map.of("type", "object",
                                "description", "Объект ровно как вернул summarize"),
                                "fileName", Map.of("type", "string")),
                        List.of("summary"), this::save)
        };
        this.server = McpServer.sync(transport)
                .serverInfo("pipeline", "1.0")
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

    /** search: root, query, maxResults (необязательный). */
    static McpSchema.CallToolResult search(McpSchema.CallToolRequest request) {
        Map<String, Object> args = arguments(request);
        Set<String> keys = new LinkedHashSet<>(args.keySet());
        boolean validKeys = keys.equals(new LinkedHashSet<>(List.of("root", "query")))
                || keys.equals(new LinkedHashSet<>(List.of("root", "query", "maxResults")));
        try {
            if (args == null || !validKeys) {
                return error("Неверные аргументы search: требуются root и query,"
                        + " maxResults не обязателен.");
            }
            Integer maxResults = SearchSummarizer.integer(args.get("maxResults"));
            if (args.get("maxResults") != null && maxResults == null) {
                throw new PipelineToolException("maxResults должен быть целым числом.");
            }
            Path root = Path.of(SearchSummarizer.string(args.get("root")));
            FileSearcher.SearchResult result = FileSearcher.search(root,
                    SearchSummarizer.string(args.get("query")), maxResults);
            return success(result.toMap());
        } catch (PipelineToolException e) {
            return error(message(e));
        } catch (Exception e) {
            return error("Не удалось выполнить поиск.");
        }
    }

    /** summarize: searchResult — объект ровно как вернул search. */
    static McpSchema.CallToolResult summarize(McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = arguments(request);
            if (args == null || args.size() != 1 || !args.containsKey("searchResult")) {
                return error("Неверные аргументы summarize: требуется searchResult.");
            }
            Map<String, Object> searchResult = object(args.get("searchResult"));
            if (searchResult == null) {
                return error("searchResult должен быть объектом результата search.");
            }
            SearchSummarizer.Summary summary = SearchSummarizer.summarize(searchResult);
            return success(summary.toMap());
        } catch (PipelineToolException e) {
            return error(message(e));
        } catch (Exception e) {
            return error("Не удалось построить сводку.");
        }
    }

    /** saveToFile: summary — объект ровно как вернул summarize; fileName не обязателен. */
    McpSchema.CallToolResult save(McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = arguments(request);
            Set<String> keys = new LinkedHashSet<>(args.keySet());
            boolean validKeys = keys.equals(new LinkedHashSet<>(List.of("summary")))
                    || keys.equals(new LinkedHashSet<>(List.of("summary", "fileName")));
            if (args == null || !validKeys) {
                return error("Неверные аргументы saveToFile: требуется summary,"
                        + " fileName не обязателен.");
            }
            Map<String, Object> summary = object(args.get("summary"));
            if (summary == null) {
                return error("summary должен быть объектом результата summarize.");
            }
            ResultFileWriter.SaveResult result = writer.save(summary,
                    SearchSummarizer.string(args.get("fileName")));
            return success(result.toMap());
        } catch (PipelineToolException e) {
            return error(message(e));
        } catch (Exception e) {
            return error("Не удалось сохранить файл результата.");
        }
    }

    private static Map<String, Object> arguments(McpSchema.CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) map);
        }
        return null;
    }

    private static McpSchema.CallToolResult success(Object value) {
        return McpSchema.CallToolResult.builder().structuredContent(value)
                .addTextContent(PipelineCanonicalJson.canonical(value)).build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    private static String message(PipelineToolException e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? "Ошибка инструмента pipeline." : e.getMessage();
    }

    @Override
    public void close() {
        server.closeGracefully();
    }

    /** Каталог результатов по умолчанию (конфигурацией не задан: без переменных окружения). */
    public static Path defaultResultsDir() {
        return Path.of(System.getProperty("user.home"), ".ai-advent-agent", "pipeline-results");
    }

    /**
     * Команда запуска сервера: установленный ai-agent, иначе java из classpath.
     * Аналог GitMcpServer.command. Для SelfTest — javaCommand, всегда java.
     */
    public static String command(Path resultsDir) {
        try {
            if (commandExists("ai-agent")) {
                return "ai-agent --mcp-server pipeline " + RESULTS_ARGUMENT
                        + " " + quote(resultsDir.toString());
            }
            return javaCommand(resultsDir);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось определить Pipeline MCP-сервер.", e);
        }
    }

    /** Команда запуска java -cp из текущего classpath (независимо от установленного CLI). */
    public static String javaCommand(Path resultsDir) {
        try {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Path location = Path.of(PipelineMcpServer.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath();
            String classpath = location.toString();
            if (Files.isDirectory(location)) {
                classpath = System.getProperty("java.class.path", classpath);
            }
            return quote(javaExecutable) + " -cp " + quote(classpath) + " "
                    + PipelineMcpServer.class.getName() + " " + RESULTS_ARGUMENT
                    + " " + quote(resultsDir.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось определить Pipeline MCP-сервер java.", e);
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
        Path resultsDir = defaultResultsDir();
        if (args.length == 2 && RESULTS_ARGUMENT.equals(args[0])) {
            resultsDir = Path.of(args[1]);
        } else if (args.length != 0) {
            System.err.println("Pipeline MCP: требуется " + RESULTS_ARGUMENT + " <каталог результатов>"
                    + " или ничего.");
            return 2;
        }
        try (PipelineMcpServer ignored = new PipelineMcpServer(resultsDir)) {
            Thread.currentThread().join();
            return 0;
        } catch (IOException e) {
            System.err.println("Pipeline MCP: не удалось создать каталог результатов.");
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (RuntimeException e) {
            System.err.println("Pipeline MCP: не удалось запустить сервер.");
            return 2;
        }
    }
}
