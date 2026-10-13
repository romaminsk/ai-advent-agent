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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        List<String> warnings = new ArrayList<>();
        Map<String, String> env = System.getenv();
        Map<String, String> values = ReviewLlmConfig.merged(env,
                ReviewLlmConfig.readEnvFile(ReviewLlmConfig.envFile(env), warnings));
        ReviewLlmConfig.Selection selection = ReviewLlmConfig.select(values);
        warnings.addAll(selection.warnings());
        warnings.forEach(warning -> err.println("Внимание: " + warning));
        List<ReviewRunner.Model> models = models(selection.backends(),
                ReviewLlmConfig.localTimeoutSeconds(values));
        ReviewRunner.Input input = new ReviewRunner.Input(options.base(), options.head(), patches,
                selection.windowTokens(), options.maxDiff());
        ReviewRunner.Result result = ReviewRunner.run(input, models,
                new LazyIndexSearch(project, indexDirectory(), values, err));
        try {
            write(options, ReviewReport.fitComment(result.markdown()), out);
        } catch (IOException e) {
            err.println("Ошибка записи " + options.out() + ": " + ReviewRunner.describe(e));
            return 1;
        }
        printStats(result, selection, err);
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

    /** Модели в порядке попыток; ошибка конфигурации становится причиной сбоя, а не падением. */
    static List<ReviewRunner.Model> models(List<ReviewLlmConfig.Backend> backends, long localTimeoutSeconds) {
        List<ReviewRunner.Model> models = new ArrayList<>();
        for (ReviewLlmConfig.Backend backend : backends) {
            boolean local = ReviewLlmConfig.MODE_LOCAL.equals(backend.mode());
            long timeout = local ? localTimeoutSeconds : ReviewRunner.LLM_TIMEOUT_SECONDS;
            models.add(new ReviewRunner.Model(backend.mode(), backend.model(), llmFor(backend),
                    backend.apiKey(), timeout));
        }
        return models;
    }

    static ReviewRunner.Llm llmFor(ReviewLlmConfig.Backend backend) {
        try {
            Config config = new Config(backend.apiKey(), backend.url(), backend.model());
            Path history = Files.createTempDirectory("ai-agent-review-").resolve("history.json");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), new JsonConversationStore(history, true));
            return (system, user, maxOutputTokens) -> {
                try {
                    return agent.statelessRagCall(system, user, maxOutputTokens).text();
                } catch (Exception e) {
                    throw new IllegalStateException(ReviewLlmConfig.scrub(
                            ReviewRunner.describe(e), backend.apiKey()));
                }
            };
        } catch (Exception e) {
            String reason = ReviewLlmConfig.scrub(ReviewRunner.describe(e), backend.apiKey());
            return (system, user, maxOutputTokens) -> {
                throw new IllegalStateException(reason);
            };
        }
    }

    /** Каталог индекса ревью: постоянный REVIEW_INDEX_DIR или ~/.ai-advent-agent/review-index (help не затрагивается). */
    static Path indexDirectory() {
        String configured = System.getenv(INDEX_ENV);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return Path.of(System.getProperty("user.home"), ".ai-advent-agent", "review-index");
    }

    private static void printStats(ReviewRunner.Result result, ReviewLlmConfig.Selection selection, PrintStream err) {
        List<ReviewRunner.Entry> entries = result.entries();
        err.println("Ревью: файлов " + entries.size()
                + " (проанализировано " + count(entries, ReviewRunner.Status.ANALYZED)
                + ", частично " + count(entries, ReviewRunner.Status.PARTIAL)
                + ", пропущено " + count(entries, ReviewRunner.Status.SKIPPED)
                + ", не анализировалось " + count(entries, ReviewRunner.Status.NOT_ANALYZED) + ")");
        err.println("Размер diff: " + result.diffChars() + " симв., бюджет " + result.budgetChars() + " симв.");
        err.println("Режимы LLM по порядку попыток: " + selection.backends());
        err.println("Чанков RAG: " + result.chunks());
        err.println("Повторов LLM: " + result.retries());
        err.println("Время: " + result.elapsedMs() + " мс");
    }

    private static long count(List<ReviewRunner.Entry> entries, ReviewRunner.Status status) {
        return entries.stream().filter(entry -> entry.status() == status).count();
    }

    /** Индекс ревью строится при первом запросе к RAG из рабочей копии проекта; эмбеддинги только локально. */
    static final class LazyIndexSearch implements ReviewRunner.Search {
        private final Path project;
        private final Path indexDir;
        private final Map<String, String> values;
        private final PrintStream err;
        private RagRetriever retriever;

        LazyIndexSearch(Path project, Path indexDir, Map<String, String> values, PrintStream err) {
            this.project = project;
            this.indexDir = indexDir;
            this.values = values;
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
            String baseUrl = values.get("EMBEDDING_BASE_URL");
            String model = values.get("EMBEDDING_MODEL");
            if (baseUrl == null || model == null) {
                throw new IllegalStateException("не заданы EMBEDDING_BASE_URL и EMBEDDING_MODEL");
            }
            Config.requireLoopbackUrl(baseUrl, "EMBEDDING_BASE_URL");
            IndexStore store = new IndexStore(indexDir);
            Embedder embedder = OpenAiEmbedder.forReview(baseUrl, model, null);
            IndexService.BuildReport report = new IndexService(store).build(STRATEGY, project.toString(),
                    new DocumentLoader(), embedder, model, line -> { });
            err.println("Индекс ревью: файлов " + report.files() + ", чанков " + report.chunks()
                    + ", запросов к API " + report.apiRequests());
            return new RagRetriever(store, embedder, STRATEGY, ReviewRetrieval.PER_FILE_CHUNKS);
        }
    }
}
