package com.example.review;

import com.example.Config;
import com.example.JsonConversationStore;
import com.example.LlmAgent;
import com.example.ModelSettings;
import com.example.assistant.DevAssistant;
import com.example.index.DocumentLoader;
import com.example.index.Embedder;
import com.example.index.IndexService;
import com.example.index.IndexStore;
import com.example.index.OpenAiEmbedder;
import com.example.rag.RagRetriever;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Команда «ai-agent review»: git diff base...head, RAG по документации и коду, ревью через LLM. */
public final class ReviewCommand {

    static final String STRATEGY = IndexService.STRATEGY_STRUCTURE;
    static final String INDEX_ENV = "REVIEW_INDEX_DIR";
    static final String USAGE = "Использование: ai-agent review [--base <ref>] [--head <ref>] "
            + "[--project <путь>] [--out <файл>] [--max-diff <N>]";

    private ReviewCommand() {
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        ReviewOptions options;
        try {
            options = ReviewOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
        Path project;
        try {
            project = DevAssistant.resolveProject(options.project()).path();
        } catch (RuntimeException e) {
            return failGit(options, ReviewRunner.describe(e), out, err);
        }
        List<GitDiff.FilePatch> patches;
        try {
            patches = GitDiff.patches(project, options.base(), options.head());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failGit(options, "чтение diff прервано", out, err);
        } catch (IOException | RuntimeException e) {
            return failGit(options, ReviewRunner.describe(e), out, err);
        }
        Integer window = windowTokens();
        ReviewRunner.Input input = new ReviewRunner.Input(options.base(), options.head(), patches,
                window, options.maxDiff());
        ReviewRunner.Result result = ReviewRunner.run(input, llm(), new LazyIndexSearch(project, indexDirectory(), err));
        try {
            write(options, ReviewReport.fitComment(result.markdown()), out);
        } catch (IOException e) {
            err.println("Ошибка записи " + options.out() + ": " + ReviewRunner.describe(e));
            return 1;
        }
        printStats(result, window, err);
        return 0;
    }

    private static int failGit(ReviewOptions options, String reason, PrintStream out, PrintStream err) {
        err.println("Авторевью недоступно: " + reason);
        try {
            write(options, ReviewReport.unavailable(options.base(), options.head(), List.of(), reason), out);
        } catch (IOException e) {
            err.println("Ошибка записи " + options.out() + ": " + ReviewRunner.describe(e));
        }
        return 1;
    }

    private static void write(ReviewOptions options, String markdown, PrintStream out) throws IOException {
        if (options.out() == null) {
            out.print(markdown);
            out.flush();
            return;
        }
        Path parent = options.out().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(options.out(), markdown, StandardCharsets.UTF_8);
    }

    /** Окно контекста из LLM_CONTEXT_WINDOW_TOKENS; null — не задано или настройки не читаются. */
    static Integer windowTokens() {
        try {
            return ModelSettings.fromEnv().contextWindowTokens();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** LLM-клиент; ошибка конфигурации превращается в причину «Авторевью недоступно», а не в падение. */
    static ReviewRunner.Llm llm() {
        try {
            Config config = Config.fromEnv();
            ModelSettings settings = ModelSettings.fromEnv();
            Path history = Files.createTempDirectory("ai-agent-review-").resolve("history.json");
            LlmAgent agent = new LlmAgent(config, settings, new JsonConversationStore(history, true));
            return (system, user, maxOutputTokens) ->
                    agent.statelessRagCall(system, user, maxOutputTokens).text();
        } catch (Exception e) {
            String reason = ReviewRunner.describe(e);
            return (system, user, maxOutputTokens) -> {
                throw new IllegalStateException(reason);
            };
        }
    }

    /** Каталог индекса ревью: отдельный от help (REVIEW_INDEX_DIR или ~/.ai-advent-agent/review-index). */
    static Path indexDirectory() {
        String configured = System.getenv(INDEX_ENV);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return Path.of(System.getProperty("user.home"), ".ai-advent-agent", "review-index");
    }

    private static void printStats(ReviewRunner.Result result, Integer window, PrintStream err) {
        List<ReviewRunner.Entry> entries = result.entries();
        err.println("Ревью: файлов " + entries.size()
                + " (проанализировано " + count(entries, ReviewRunner.Status.ANALYZED)
                + ", частично " + count(entries, ReviewRunner.Status.PARTIAL)
                + ", пропущено " + count(entries, ReviewRunner.Status.SKIPPED)
                + ", не анализировалось " + count(entries, ReviewRunner.Status.NOT_ANALYZED) + ")");
        err.println("Размер diff: " + result.diffChars() + " симв., бюджет " + result.budgetChars() + " симв.");
        if (window == null) {
            err.println("Окно модели не задано (LLM_CONTEXT_WINDOW_TOKENS): лимит diff по умолчанию "
                    + ReviewRunner.UNKNOWN_WINDOW_DIFF_CHARS + " симв.");
        }
        err.println("Чанков RAG: " + result.chunks());
        err.println("Повторов LLM: " + result.retries());
        err.println("Время: " + result.elapsedMs() + " мс");
    }

    private static long count(List<ReviewRunner.Entry> entries, ReviewRunner.Status status) {
        return entries.stream().filter(entry -> entry.status() == status).count();
    }

    /** Индекс ревью строится при первом запросе к RAG, из рабочей копии проекта. */
    static final class LazyIndexSearch implements ReviewRunner.Search {
        private final Path project;
        private final Path indexDir;
        private final PrintStream err;
        private RagRetriever retriever;

        LazyIndexSearch(Path project, Path indexDir, PrintStream err) {
            this.project = project;
            this.indexDir = indexDir;
            this.err = err;
        }

        @Override
        public List<RagRetriever.Chunk> search(String query, int topK) throws Exception {
            if (retriever == null) {
                retriever = build();
            }
            return retriever.retrieve(query, topK);
        }

        private RagRetriever build() throws IOException, InterruptedException {
            String baseUrl = System.getenv("EMBEDDING_BASE_URL");
            String model = System.getenv("EMBEDDING_MODEL");
            if (baseUrl == null || model == null || baseUrl.isBlank() || model.isBlank()) {
                throw new IllegalStateException("не заданы EMBEDDING_BASE_URL и EMBEDDING_MODEL");
            }
            IndexStore store = new IndexStore(indexDir);
            Embedder embedder = OpenAiEmbedder.forReview(baseUrl, model, System.getenv("EMBEDDING_API_KEY"));
            IndexService.BuildReport report = new IndexService(store).build(STRATEGY, project.toString(),
                    new DocumentLoader(), embedder, model, line -> { });
            err.println("Индекс ревью: файлов " + report.files() + ", чанков " + report.chunks()
                    + ", запросов к API " + report.apiRequests());
            return new RagRetriever(store, embedder, STRATEGY, ReviewRetrieval.PER_FILE_CHUNKS);
        }
    }
}
