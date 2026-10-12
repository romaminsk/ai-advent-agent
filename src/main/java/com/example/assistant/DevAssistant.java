package com.example.assistant;

import com.example.Config;
import com.example.JsonConversationStore;
import com.example.LlmAgent;
import com.example.ModelSettings;
import com.example.index.DocumentLoader;
import com.example.index.Embedder;
import com.example.index.IndexService;
import com.example.index.IndexStore;
import com.example.index.OpenAiEmbedder;
import com.example.rag.CitationValidator;
import com.example.rag.RagPromptBuilder;
import com.example.rag.RagRetriever;
import com.example.rag.RagService;
import com.example.rag.RagSettings;
import com.example.rag.RagSettingsStore;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Ассистент разработчика: индекс документации, справка с цитатами и данные Git через MCP. */
public final class DevAssistant {

    static final String INDEX_DIR_NAME = "dev-assistant-index";
    static final String STRATEGY = IndexService.STRATEGY_STRUCTURE;
    static final int TOP_K = 5;
    static final Duration MCP_TIMEOUT = Duration.ofSeconds(GitReadTools.TIMEOUT_SECONDS);
    /**
     * Порог top-1 только для help по docs-индексу. Получен калибровкой 2026-10-12
     * (artifacts/docs-calibration-2026-10-12.md): середина между минимумом релевантных 0.4975
     * и максимумом нерелевантных 0.4610. Глобальный idkThreshold RAG не меняется.
     */
    public static final double DOCS_IDK_THRESHOLD = 0.479250;
    static final int LIST_PROMPT_LINES = 40;
    public static final String NOT_IN_DOCS = "В документации проекта этого нет.";
    static final String MCP_TITLE = "Контекст проекта (MCP)";
    static final String SYSTEM_PROMPT = "Ты ассистент разработчика. Отвечай по-русски и только по фрагментам контекста, "
            + "ссылаясь на них как [n]. Каждый пункт — одно утверждение с короткой дословной цитатой из того же фрагмента. "
            + "Блок «Контекст проекта (MCP)» — факты о Git и файлах; на него [n] не ставь.";
    static final int MAX_CHUNKS = 4;
    static final int MAX_CHUNK_CHARS = 1200;
    static final int MAX_RETRIES = 2;
    static final long LLM_TIMEOUT_SECONDS = 60;
    private static final Pattern CITATION = Pattern.compile("\\[\\d+\\]");

    private static final Pattern BRANCH_QUESTION = Pattern.compile("ветк|branch");
    private static final Pattern CHANGE_QUESTION = Pattern.compile("измен|статус|diff|коммит|правк");
    private static final Pattern FILES_QUESTION = Pattern.compile("файл|структур|папк|модул|где лежит");

    private DevAssistant() {
    }

    /** Результат ответа: текст и источники в порядке нумерации [n]. */
    public record Answer(String text, List<RagRetriever.Chunk> sources, int retries) {
        public Answer(String text, List<RagRetriever.Chunk> sources) {
            this(text, sources, 0);
        }
    }

    /** Источник данных MCP: имя инструмента → текст результата. */
    @FunctionalInterface
    public interface McpSource {
        Map<String, String> call(List<String> tools) throws Exception;
    }

    static Path indexDirectory() {
        return Path.of(System.getProperty("user.home"), ".ai-advent-agent", INDEX_DIR_NAME);
    }

    static boolean isBranchQuestion(String question) {
        return BRANCH_QUESTION.matcher(question.toLowerCase(Locale.ROOT)).find();
    }

    public static boolean isMcpQuestion(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return BRANCH_QUESTION.matcher(lower).find() || CHANGE_QUESTION.matcher(lower).find()
                || FILES_QUESTION.matcher(lower).find();
    }

    /** git_branch всегда; статус и diff — по словам про изменения; list_files — по словам про файлы. */
    public static List<String> routeTools(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        List<String> tools = new ArrayList<>(List.of(GitToolsMcpServer.BRANCH));
        if (CHANGE_QUESTION.matcher(lower).find()) {
            tools.add(GitToolsMcpServer.STATUS);
            tools.add(GitToolsMcpServer.DIFF);
        }
        if (FILES_QUESTION.matcher(lower).find()) {
            tools.add(GitToolsMcpServer.LIST_FILES);
        }
        return tools;
    }

    public static int index(PrintStream out, PrintStream err) {
        String baseUrl = System.getenv("EMBEDDING_BASE_URL");
        String model = System.getenv("EMBEDDING_MODEL");
        if (baseUrl == null || model == null || baseUrl.isBlank() || model.isBlank()) {
            err.println("Ошибка: не заданы EMBEDDING_BASE_URL и EMBEDDING_MODEL (добавьте их в .env).");
            return 1;
        }
        try {
            Path root = projectRoot();
            IndexStore store = new IndexStore(indexDirectory());
            Embedder embedder = new OpenAiEmbedder(baseUrl, model);
            IndexService.BuildReport report = new IndexService(store).build(STRATEGY, root.toString(),
                    DocumentLoader.docsOnly(), embedder, model, line -> { });
            IndexStore.Index index = store.load(STRATEGY);
            out.println("Корпус: " + root + " (README*, docs/**/*.md, docs/**/*.txt)");
            out.println("Файлов: " + report.files());
            out.println("Чанков: " + report.chunks());
            Set<String> paths = new LinkedHashSet<>();
            for (IndexStore.IndexedChunk chunk : index.chunks()) {
                paths.add(chunk.meta().source());
            }
            out.println("Источники:");
            paths.forEach(path -> out.println("  " + path));
            out.println("Индекс: " + store.file(STRATEGY));
            return 0;
        } catch (Exception e) {
            err.println("Ошибка индексации: " + e.getMessage());
            return 1;
        }
    }

    public static int help(String[] args, PrintStream out, PrintStream err) {
        String question = String.join(" ", args).strip();
        if (question.isEmpty()) {
            out.println("Ассистент разработчика: отвечает по README и docs/ проекта и по данным Git.");
            out.println("Сначала соберите индекс: ai-agent index");
            out.println("Примеры: ai-agent help \"Какая у проекта структура?\"");
            out.println("         ai-agent help \"В какой я сейчас ветке?\"");
            out.println("         ai-agent help \"Какие файлы изменены?\"");
            return 0;
        }
        try {
            IndexStore store = new IndexStore(indexDirectory());
            if (!store.exists(STRATEGY)) {
                err.println("Индекс не найден. Соберите его командой: ai-agent index");
                return 1;
            }
            String baseUrl = System.getenv("EMBEDDING_BASE_URL");
            String model = System.getenv("EMBEDDING_MODEL");
            if (baseUrl == null || model == null || baseUrl.isBlank() || model.isBlank()) {
                err.println("Ошибка: не заданы EMBEDDING_BASE_URL и EMBEDDING_MODEL (добавьте их в .env).");
                return 1;
            }
            RagSettings settings = RagSettingsStore.defaultStore().load().settings().withRewrite(false)
                    .withMinScore(DOCS_IDK_THRESHOLD).withIdkThreshold(DOCS_IDK_THRESHOLD);
            LlmAgent agent = newAgent();
            RagService service = new RagService(
                    new RagRetriever(store, new OpenAiEmbedder(baseUrl, model), STRATEGY, TOP_K),
                    new RagPromptBuilder(), SYSTEM_PROMPT,
                    new RagService.LlmClient() {
                        @Override
                        public String complete(String system, String user, int maxOutputTokens) throws Exception {
                            return agent.statelessRagCall(system, user, maxOutputTokens).text();
                        }

                        @Override
                        public RagService.Completion completeWithUsage(String system, String user,
                                                                       int maxOutputTokens) throws Exception {
                            return agent.statelessRagCall(system, user, maxOutputTokens);
                        }
                    }, settings, null, LLM_TIMEOUT_SECONDS);
            Path root = projectRoot();
            long started = System.nanoTime();
            Answer answer = answer(question, service, gitSource(root));
            out.println(answer.text());
            err.println("диагностика: повторов LLM=" + answer.retries() + ", время="
                    + (System.nanoTime() - started) / 1_000_000 + " мс");
            if (!answer.sources().isEmpty()) {
                out.println();
                out.println("Источники:");
                for (int i = 0; i < answer.sources().size(); i++) {
                    RagRetriever.Chunk chunk = answer.sources().get(i);
                    out.println("[" + (i + 1) + "] " + chunk.source() + " › " + chunk.section());
                }
            }
            return 0;
        } catch (Exception e) {
            err.println("Ошибка ассистента: " + e.getMessage());
            return 1;
        }
    }

    /** Ответ по RAG с проверкой порога и цитат; MCP-вопросы при отказе RAG отвечаются данными Git. */
    public static Answer answer(String question, RagService service, McpSource mcp) throws Exception {
        boolean mcpQuestion = isMcpQuestion(question);
        RagService.Prepared prepared = service.prepare(question);
        if (service.shouldDecline(prepared)) {
            if (!mcpQuestion) {
                return new Answer(NOT_IN_DOCS, List.of());
            }
            return new Answer(gitText(question, mcp.call(routeTools(question))), List.of());
        }
        Map<String, String> data = collect(question, mcp);
        List<RagRetriever.Chunk> limited = prepared.prompt().chunks().stream()
                .limit(MAX_CHUNKS).map(DevAssistant::truncated).toList();
        RagPromptBuilder.Prompt base = new RagPromptBuilder().build(question, limited);
        RagPromptBuilder.Prompt prompt = new RagPromptBuilder.Prompt(SYSTEM_PROMPT,
                base.user() + "\n\n" + MCP_TITLE + ":\n" + format(data), base.context(), base.chunks());
        RagService.Prepared withContext = withPrompt(prepared, prompt);
        RagService.Result result = null;
        int retries = 0;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            result = service.complete(withContext);
            retries = attempt;
            if (hasCitation(result)) {
                break;
            }
        }
        if (!hasCitation(result)) {
            String note = "Модель не дала ответ с цитатами после повторов. Найденные фрагменты документации — в источниках.";
            if (mcpQuestion) {
                note = note + "\n" + gitText(question, data);
            }
            return new Answer(note, result.chunks(), retries);
        }
        String text = result.answer();
        if (result.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED) {
            text = text + "\n(Цитаты не подтверждены проверкой по фрагментам.)";
        }
        if (isBranchQuestion(question) && data.containsKey(GitToolsMcpServer.BRANCH)) {
            text = text + "\n" + data.get(GitToolsMcpServer.BRANCH);
        }
        return new Answer(text, result.chunks(), retries);
    }

    private static Map<String, String> collect(String question, McpSource mcp) {
        try {
            return mcp.call(routeTools(question));
        } catch (Exception e) {
            return Map.of(GitToolsMcpServer.BRANCH, "недоступно: " + e.getMessage());
        }
    }

    private static String gitText(String question, Map<String, String> data) {
        StringBuilder text = new StringBuilder("Данные из Git (MCP):");
        for (String tool : routeTools(question)) {
            if (data.containsKey(tool)) {
                text.append("\n").append(tool).append(":\n").append(data.get(tool));
            }
        }
        return text.toString();
    }

    private static String format(Map<String, String> data) {
        StringBuilder text = new StringBuilder();
        data.forEach((tool, value) -> {
            if (!text.isEmpty()) text.append("\n\n");
            text.append(tool).append(":\n")
                    .append(GitToolsMcpServer.LIST_FILES.equals(tool) ? promptListing(value) : value);
        });
        return text.toString();
    }

    /** Список файлов для промпта: каталоги и файлы верхнего уровня, первые 40 строк, остаток счётчиком. */
    public static String promptListing(String listing) {
        List<String> files = listing.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("…"))
                .toList();
        Set<String> top = new LinkedHashSet<>();
        for (String file : files) {
            int slash = file.indexOf('/');
            top.add(slash < 0 ? file : file.substring(0, slash + 1));
        }
        StringBuilder text = new StringBuilder("Корень: ").append(String.join(" ", top)).append('\n');
        files.stream().limit(LIST_PROMPT_LINES).forEach(file -> text.append(file).append('\n'));
        if (files.size() > LIST_PROMPT_LINES) {
            text.append("… ещё ").append(files.size() - LIST_PROMPT_LINES).append(" файлов");
        }
        return text.toString().strip();
    }

    private static RagService.Prepared withPrompt(RagService.Prepared prepared, RagPromptBuilder.Prompt prompt) {
        return new RagService.Prepared(prepared.question(), prompt, prepared.retrievedChunks(),
                prepared.retrieveMs(), prepared.ranking(), prepared.settings(), prepared.rewriteQuery(),
                prepared.rewriteFallback(), prepared.rewriteMs(), prepared.rewriteStatus());
    }

    private static RagRetriever.Chunk truncated(RagRetriever.Chunk chunk) {
        String text = chunk.text().length() > MAX_CHUNK_CHARS
                ? chunk.text().substring(0, MAX_CHUNK_CHARS) : chunk.text();
        return new RagRetriever.Chunk(chunk.source(), chunk.section(), chunk.chunkId(), chunk.score(), text);
    }

    static boolean hasCitation(RagService.Result result) {
        return result.status() == RagService.Status.OK && result.answer() != null
                && CITATION.matcher(result.answer()).find()
                && result.answerStatus() != CitationValidator.AnswerStatus.IDK_MODEL;
    }

    /** Клиент MCP: дочерний процесс «ai-agent mcp-git», initialize и tools/call, таймаут 5 с. */
    public static McpSource gitSource(Path root) {
        return gitSource(root, System.getProperty("java.class.path"));
    }

    public static McpSource gitSource(Path root, String classpath) {
        return tools -> {
            Map<String, String> data = new LinkedHashMap<>();
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ServerParameters parameters = ServerParameters.builder(java.toString())
                    .args(List.of("-cp", classpath, "com.example.Main",
                            "mcp-git", GitToolsMcpServer.REPO_ROOT_ARGUMENT, root.toString()))
                    .build();
            StdioClientTransport transport = new StdioClientTransport(parameters,
                    new JacksonMcpJsonMapperSupplier().get());
            try (McpSyncClient client = McpClient.sync(transport)
                    .initializationTimeout(MCP_TIMEOUT).requestTimeout(MCP_TIMEOUT).build()) {
                client.initialize();
                for (String tool : tools) {
                    McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                            .name(tool).arguments(Map.of()).build());
                    String text = result.content() == null ? "" : result.content().stream()
                            .filter(McpSchema.TextContent.class::isInstance)
                            .map(McpSchema.TextContent.class::cast)
                            .map(McpSchema.TextContent::text)
                            .reduce((a, b) -> a + "\n" + b).orElse("");
                    data.put(tool, Boolean.TRUE.equals(result.isError()) ? "ошибка: " + text : text);
                }
            }
            return data;
        };
    }

    private static LlmAgent newAgent() throws Exception {
        Path directory = Files.createTempDirectory("ai-agent-assistant-");
        JsonConversationStore store = new JsonConversationStore(directory.resolve("history.json"), true);
        return new LlmAgent(Config.fromEnv(), ModelSettings.fromEnv(), store);
    }

    private static Path projectRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        try {
            return GitReadTools.repositoryRoot(cwd);
        } catch (Exception e) {
            return cwd;
        }
    }
}
