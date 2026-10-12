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

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.function.Consumer;
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
    private static final Pattern OTHER_TOPIC = Pattern.compile(
            "файл|diff|структур|документ|readme|docs|папк|модул|где лежит|измен|статус|коммит|правк");

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

    public static int index(String[] args, PrintStream out, PrintStream err) {
        Project project;
        try {
            project = resolveProject(parseArgs(args).project());
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        }
        err.println(projectLine(project));
        String baseUrl = System.getenv("EMBEDDING_BASE_URL");
        String model = System.getenv("EMBEDDING_MODEL");
        if (baseUrl == null || model == null || baseUrl.isBlank() || model.isBlank()) {
            err.println("Ошибка: не заданы EMBEDDING_BASE_URL и EMBEDDING_MODEL (добавьте их в .env).");
            return 1;
        }
        try {
            Path root = project.path();
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
        try {
            Args parsed = parseArgs(args);
            String question = String.join(" ", parsed.rest()).strip();
            if (question.isEmpty()) {
                out.println("Ассистент разработчика: отвечает по README и docs/ проекта и по данным Git.");
                out.println("Сначала соберите индекс: ai-agent index");
                out.println("Примеры: ai-agent help \"Какая у проекта структура?\"");
                out.println("         ai-agent help \"В какой я сейчас ветке?\"");
                out.println("         ai-agent help \"Какие файлы изменены?\"");
                return 0;
            }
            Project project = resolveProject(parsed.project());
            err.println(projectLine(project));
            if (isBranchOnly(question)) {
                try (GitSession git = new GitSession(project.path(), System.getProperty("java.class.path"))) {
                    return branchAnswer(git, out, err);
                }
            }
            try (Session session = Session.open(project.path())) {
                return session.ask(question, out, err);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    /** Интерактивный режим: «Вопрос ›», выход по /exit, /quit или Ctrl+D; сессия (индекс, MCP) одна на все вопросы. */
    public static int chat(String[] args, BufferedReader in, PrintStream out, PrintStream err) {
        try {
            Project project = resolveProject(parseArgs(args).project());
            err.println(projectLine(project));
            try (Session session = Session.open(project.path())) {
                runChat(in, out, question -> session.ask(question, out, err));
                return 0;
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    /** Аргументы команды: --project <путь> (или --project=<путь>) в любой позиции; остальное — вопрос. */
    public record Args(String project, List<String> rest) {
    }

    public static Args parseArgs(String[] args) {
        String project = null;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--project")) {
                if (i + 1 >= args.length || args[i + 1].isBlank()) {
                    throw new IllegalArgumentException("Ошибка: после --project нужен путь к папке проекта.");
                }
                project = args[++i];
            } else if (arg.startsWith("--project=")) {
                String value = arg.substring("--project=".length());
                if (value.isBlank()) {
                    throw new IllegalArgumentException("Ошибка: после --project= нужен путь к папке проекта.");
                }
                project = value;
            } else {
                rest.add(arg);
            }
        }
        return new Args(project, rest);
    }

    /** Проект: путь к git-репозиторию и источник пути (--project, PROJECT_DIR или текущая папка). */
    public record Project(Path path, String source) {
    }

    public static Project resolveProject(String projectArg) {
        return resolveProject(projectArg, System.getenv("PROJECT_DIR"),
                Path.of("").toAbsolutePath().normalize());
    }

    /** Приоритет: --project, затем PROJECT_DIR (пустое значение игнорируется), затем текущая папка. */
    public static Project resolveProject(String projectArg, String envValue, Path cwd) {
        String raw;
        String source;
        if (projectArg != null) {
            raw = projectArg;
            source = "--project";
        } else if (envValue != null && !envValue.isBlank()) {
            raw = envValue.strip();
            source = "PROJECT_DIR";
        } else {
            raw = cwd.toString();
            source = "текущая папка";
        }
        Path path;
        try {
            path = normalizePath(raw, cwd);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Ошибка: папка проекта не найдена: " + raw + " (источник: " + source + ")");
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Ошибка: папка проекта не найдена: " + raw + " (источник: " + source + ")");
        }
        try {
            GitReadTools.repositoryRoot(path);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Ошибка: папка проекта не является Git-репозиторием: " + path
                    + " (источник: " + source + ")");
        }
        return new Project(path, source);
    }

    /** «~», относительные пути и симлинки приводятся к настоящему абсолютному пути. */
    public static Path normalizePath(String raw, Path cwd) throws IOException {
        String expanded = raw;
        if (raw.equals("~")) {
            expanded = System.getProperty("user.home");
        } else if (raw.startsWith("~/")) {
            expanded = System.getProperty("user.home") + raw.substring(1);
        }
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            path = cwd.resolve(path);
        }
        return path.normalize().toRealPath();
    }

    static String projectLine(Project project) {
        return "Проект: " + project.path() + " (источник: " + project.source() + ")";
    }

    public static void runChat(BufferedReader in, PrintStream out, Consumer<String> ask) throws IOException {
        while (true) {
            out.print("Вопрос › ");
            out.flush();
            String line = in.readLine();
            if (line == null) {
                out.println();
                return;
            }
            String text = line.strip();
            if (isExit(text)) {
                return;
            }
            String question = questionOf(text);
            if (!question.isEmpty()) {
                ask.accept(question);
            }
        }
    }

    public static boolean isExit(String line) {
        return line.equalsIgnoreCase("/exit") || line.equalsIgnoreCase("/quit");
    }

    /** «/help X» и «X» дают один и тот же вопрос; «/help» без текста — пустой вопрос. */
    public static String questionOf(String line) {
        String text = line.strip();
        if (text.equals("/help")) {
            return "";
        }
        if (text.startsWith("/help ") || text.startsWith("/help\t")) {
            return text.substring("/help".length()).strip();
        }
        return text;
    }

    /** Вопрос только про ветку: есть слово про ветку и нет слов про файлы, diff, структуру или документацию. */
    public static boolean isBranchOnly(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return BRANCH_QUESTION.matcher(lower).find() && !OTHER_TOPIC.matcher(lower).find();
    }

    /** Ответ одной строкой из MCP git_branch: без RAG, LLM и цитат. */
    public static int branchAnswer(McpSource git, PrintStream out, PrintStream err) {
        try {
            String value = git.call(List.of(GitToolsMcpServer.BRANCH)).get(GitToolsMcpServer.BRANCH);
            if (value == null || value.startsWith("ошибка:")) {
                throw new IllegalStateException();
            }
            out.println("Текущая git-ветка: " + value.replaceFirst("^ветка: ", ""));
            return 0;
        } catch (Exception e) {
            err.println("Не удалось определить ветку: MCP-сервер git недоступен или вернул ошибку. "
                    + "Проверьте, что команда запущена внутри Git-репозитория.");
            return 1;
        }
    }

    /** Сеанс ассистента: индекс, LLM и один MCP-клиент на весь сеанс. */
    static final class Session implements AutoCloseable {
        private final RagService service;
        private final GitSession git;

        private Session(RagService service, GitSession git) {
            this.service = service;
            this.git = git;
        }

        static Session open(Path root) {
            IndexStore store = new IndexStore(indexDirectory());
            if (!store.exists(STRATEGY)) {
                throw new IllegalStateException("Индекс не найден. Соберите его командой: ai-agent index");
            }
            String baseUrl = System.getenv("EMBEDDING_BASE_URL");
            String model = System.getenv("EMBEDDING_MODEL");
            if (baseUrl == null || model == null || baseUrl.isBlank() || model.isBlank()) {
                throw new IllegalStateException(
                        "Ошибка: не заданы EMBEDDING_BASE_URL и EMBEDDING_MODEL (добавьте их в .env).");
            }
            RagSettings settings;
            try {
                settings = RagSettingsStore.defaultStore().load().settings().withRewrite(false)
                        .withMinScore(DOCS_IDK_THRESHOLD).withIdkThreshold(DOCS_IDK_THRESHOLD);
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось прочитать настройки RAG: " + e.getMessage(), e);
            }
            LlmAgent agent;
            try {
                agent = newAgent();
            } catch (Exception e) {
                throw new IllegalStateException("Не удалось создать LLM-агент: " + e.getMessage(), e);
            }
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
            return new Session(service, new GitSession(root, System.getProperty("java.class.path")));
        }

        int ask(String question, PrintStream out, PrintStream err) {
            if (isBranchOnly(question)) {
                return branchAnswer(git, out, err);
            }
            try {
                long started = System.nanoTime();
                Answer answer = answer(question, service, git);
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

        @Override
        public void close() {
            git.close();
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
            try (GitSession session = new GitSession(root, classpath)) {
                return session.call(tools);
            }
        };
    }

    /** MCP-клиент «ai-agent mcp-git»: процесс открывается при первом вызове и закрывается в close(). */
    public static final class GitSession implements McpSource, AutoCloseable {
        private final Path root;
        private final String classpath;
        private McpSyncClient client;

        public GitSession(Path root, String classpath) {
            this.root = root;
            this.classpath = classpath;
        }

        @Override
        public Map<String, String> call(List<String> tools) throws Exception {
            try {
                if (client == null) {
                    client = open();
                }
                Map<String, String> data = new LinkedHashMap<>();
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
                return data;
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        private McpSyncClient open() {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ServerParameters parameters = ServerParameters.builder(java.toString())
                    .args(List.of("-cp", classpath, "com.example.Main",
                            "mcp-git", GitToolsMcpServer.REPO_ROOT_ARGUMENT, root.toString()))
                    .build();
            StdioClientTransport transport = new StdioClientTransport(parameters,
                    new JacksonMcpJsonMapperSupplier().get());
            McpSyncClient opened = McpClient.sync(transport)
                    .initializationTimeout(MCP_TIMEOUT).requestTimeout(MCP_TIMEOUT).build();
            try {
                opened.initialize();
            } catch (RuntimeException e) {
                opened.close();
                throw e;
            }
            return opened;
        }

        @Override
        public void close() {
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }

    private static LlmAgent newAgent() throws Exception {
        Path directory = Files.createTempDirectory("ai-agent-assistant-");
        JsonConversationStore store = new JsonConversationStore(directory.resolve("history.json"), true);
        return new LlmAgent(Config.fromEnv(), ModelSettings.fromEnv(), store);
    }

}
