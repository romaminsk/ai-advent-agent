package com.example;

import com.example.index.ChunkMeta;
import com.example.index.IndexStore;
import com.example.rag.RagConstants;
import com.example.rag.RagEval;
import com.example.rag.RagPromptBuilder;
import com.example.rag.RagRetriever;
import com.example.rag.RagService;

import java.net.http.HttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class RagChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkPromptAndRetriever();
        checkContextLimitAndNoAnswer();
        checkRetryAndPartialEval();
        checkDocumentLoaderExcludesTests();
        checkRetrievalRecallReport();
        checkHistoryExcludesRagContext();
        checkModeCommands();
        checkQuestionsAndMetrics();
    }

    private static void checkPromptAndRetriever() throws Exception {
        Path dir = Files.createTempDirectory(baseTempDir, "rag-index-");
        IndexStore store = populatedStore(dir, "unique question", 8);
        CountingEmbedder embedder = new CountingEmbedder(8);
        AtomicReference<String> userPrompt = new AtomicReference<>();
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        AtomicInteger maxOutputTokens = new AtomicInteger();
        RagService service = new RagService(new RagRetriever(store, embedder),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT, (system, user, maxTokens) -> {
                    systemPrompt.set(system);
                    userPrompt.set(user);
                    maxOutputTokens.set(maxTokens);
                    return "ответ [src/main/java/com/example/DocumentLoader.java]";
                });

        RagService.Result off = service.ask("unique question", RagService.Mode.OFF);
        expect("режим off передаёт модели только исходный вопрос",
                userPrompt.get().equals("unique question") && off.chunks().isEmpty()
                        && !userPrompt.get().contains("Контекст:"));
        RagService.Result on = service.ask("unique question", RagService.Mode.ON);
        expect("режим on добавляет найденный чанк, source и метаданные",
                userPrompt.get().contains("src/main/java/com/example/DocumentLoader.java")
                        && userPrompt.get().contains("› loading")
                        && on.chunks().stream().anyMatch(chunk ->
                        chunk.chunkId().equals("chunk-a")));
        expect("RAG system prompt требует опору на контекст и ссылки",
                systemPrompt.get().equals(RagConstants.SYSTEM_PROMPT)
                        && systemPrompt.get().contains("Не выдумывай"));
        expect("RAG измеряет retrieve и LLM раздельно",
                on.retrieveMs() >= 0 && on.llmMs() >= 0);
        expect("RAG off/on используют отдельный лимит max_tokens=4096",
                maxOutputTokens.get() == RagConstants.RAG_MAX_OUTPUT_TOKENS);
    }

    private static void checkContextLimitAndNoAnswer() throws Exception {
        RagPromptBuilder builder = new RagPromptBuilder();
        List<RagRetriever.Chunk> longChunks = List.of(
                chunk("a.java", "first", "A".repeat(3400)),
                chunk("b.java", "second", "B".repeat(3000)));
        RagPromptBuilder.Prompt bounded = builder.build("question", longChunks);
        expect("лимит контекста 6000 символов соблюдён без обрезки чанка посередине",
                bounded.context().length() <= RagConstants.CONTEXT_MAX_CHARS
                        && bounded.chunks().size() == 1
                        && bounded.context().endsWith("A".repeat(3400)));

        IndexStore empty = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-empty-"));
        AtomicInteger llmCalls = new AtomicInteger();
        RagService noHits = new RagService(new RagRetriever(empty, new CountingEmbedder(8)),
                builder, ContextBuilder.BASE_SYSTEM_PROMPT, (system, user, maxTokens) -> {
                    llmCalls.incrementAndGet();
                    return "неожиданный ответ";
                });
        RagService.Result result = noHits.ask("unindexed", RagService.Mode.ON);
        expect("пустой поиск возвращает честный отказ без вызова LLM",
                result.answer().equals(RagConstants.NO_ANSWER) && result.chunks().isEmpty()
                && llmCalls.get() == 0);
    }

    private static void checkRetryAndPartialEval() throws Exception {
        List<RagEval.Question> questions = RagEval.loadQuestions();
        String first = questions.get(0).question();
        IndexStore store = populatedStore(Files.createTempDirectory(baseTempDir, "rag-eval-index-"),
                first, 8);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger maxTokens = new AtomicInteger();
        RagService retryOnce = new RagService(new RagRetriever(store, new CountingEmbedder(8)),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, maxOutputTokens) -> {
                    maxTokens.set(maxOutputTokens);
                    return calls.incrementAndGet() == 1 ? " " : "ответ";
                });
        RagService.Result recovered = retryOnce.ask("retry question", RagService.Mode.OFF);
        expect("пустой ответ повторяется один раз и успешный retry возвращает текст",
                recovered.status() == RagService.Status.OK && calls.get() == 2
                        && recovered.answer().equals("ответ")
                        && maxTokens.get() == RagConstants.RAG_MAX_OUTPUT_TOKENS);

        AtomicInteger alwaysEmptyCalls = new AtomicInteger();
        RagService alwaysEmpty = new RagService(new RagRetriever(store, new CountingEmbedder(8)),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, maxOutputTokens) -> {
                    alwaysEmptyCalls.incrementAndGet();
                    return "";
                });
        RagService.Result empty = alwaysEmpty.ask("empty question", RagService.Mode.OFF);
        expect("после двух пустых ответов возвращается status=EMPTY",
                empty.status() == RagService.Status.EMPTY && alwaysEmptyCalls.get() == 2);

        RagService partialService = new RagService(new RagRetriever(store, new CountingEmbedder(8)),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, maxOutputTokens) -> {
                    if (user.equals(questions.get(0).question())) {
                        throw new IllegalStateException("stub failure");
                    }
                    if (user.equals(questions.get(1).question())) {
                        return "";
                    }
                    return "FileLock atomic replacement [JsonConversationStore.java]";
                });
        Path partialFile = Files.createTempDirectory(baseTempDir, "rag-partial-")
                .resolve("rag-eval.md");
        List<Integer> snapshots = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        RagEval.Report report = RagEval.run(partialService, progress::add, snapshot -> {
            snapshots.add(snapshot.rows().size());
            try {
                RagEval.writeReport(partialFile, snapshot.markdown());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        expect("eval продолжает следующие вопросы после ошибки и empty",
                report.rows().size() == 10 && report.errorResults() == 1
                        && report.emptyResults() == 1 && report.successfulQuestions() == 8
                        && report.rows().get(0).status().contains("off=error")
                        && report.rows().get(1).status().contains("off=empty"));
        expect("частичный отчёт сохранялся после каждого завершённого вопроса",
                snapshots.equals(java.util.stream.IntStream.rangeClosed(1, 10).boxed().toList())
                        && Files.readString(partialFile).contains("Прогресс: 10/10"));
        expect("прогресс печатает номер, длительности off/on и sourceHit",
                progress.size() == 10 && progress.get(2).startsWith("[3/10] off ")
                        && progress.get(2).contains(" | on ")
                        && progress.get(2).contains("sourceHit "));
        expect("ошибки и пустые ответы исключаются из сумм успешных вопросов",
                report.factsOff() == report.rows().subList(2, 10).stream()
                        .mapToInt(RagEval.Row::factsOff).sum());
    }

    private static void checkDocumentLoaderExcludesTests() throws Exception {
        Path root = Files.createTempDirectory(baseTempDir, "rag-corpus-");
        Path mainSource = root.resolve("src/main/java/com/example/Keep.java");
        Path testSource = root.resolve("src/test/java/com/example/SelfTest.java");
        Path questions = root.resolve("src/test/resources/rag/questions.json");
        Files.createDirectories(mainSource.getParent());
        Files.createDirectories(testSource.getParent());
        Files.createDirectories(questions.getParent());
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(root.resolve("artifacts"));
        Files.createDirectories(root.resolve(".idea"));
        Files.writeString(mainSource, "class Keep {}", StandardCharsets.UTF_8);
        Files.writeString(testSource, "class SelfTest {}", StandardCharsets.UTF_8);
        Files.writeString(questions, "questions must not enter index", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("target/Generated.md"), "target", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("artifacts/Report.md"), "artifacts", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".idea/Project.md"), "idea", StandardCharsets.UTF_8);
        List<String> sources = new com.example.index.DocumentLoader().load(root).stream()
                .map(com.example.index.DocumentLoader.Document::relativePath).toList();
        expect("DocumentLoader исключает src/test, generated target, artifacts и .idea",
                sources.equals(List.of("src/main/java/com/example/Keep.java"))
                        && sources.stream().noneMatch(source -> source.contains("SelfTest")
                        || source.contains("questions.json")));
    }

    private static void checkRetrievalRecallReport() throws Exception {
        List<RagEval.Question> questions = RagEval.loadQuestions();
        CountingEmbedder embedder = new CountingEmbedder(8);
        List<IndexStore.IndexedChunk> fixedChunks = new ArrayList<>();
        List<IndexStore.IndexedChunk> structureChunks = new ArrayList<>();
        int index = 0;
        for (RagEval.Question question : questions) {
            if (question.noAnswerExpected()) {
                continue;
            }
            String source = question.expectedSources().split("\\|")[0];
            fixedChunks.add(indexedChunk("fixed-" + index, source, "fixed", question,
                    embedder.vector(question.question())));
            structureChunks.add(indexedChunk("structure-" + index, source, "structure", question,
                    embedder.vector(question.question())));
            index++;
        }
        IndexStore store = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-recall-"));
        store.save(new IndexStore.Index("fixed", "fake", 8, Instant.now(), "tmp", 0,
                0, 0, fixedChunks));
        store.save(new IndexStore.Index("structure", "fake", 8, Instant.now(), "tmp", 0,
                0, 0, structureChunks));
        RagEval.RetrievalReport report = RagEval.retrieval(questions,
                new RagRetriever(store, embedder, "fixed"),
                new RagRetriever(store, embedder, "structure"));
        expect("retrieval comparison excludes no-answer question and ranks expected sources",
                report.expectedQuestions() == 9 && report.fixedHits() == 9
                        && report.structureHits() == 9
                        && report.rows().stream().allMatch(row -> row.fixedRank() == 1
                        && row.structureRank() == 1));
        expect("retrieval report contains both top-5 lists and recall summaries",
                report.format().contains("fixed top-5") && report.format().contains("structure top-5")
                        && report.format().contains("structure 9/9")
                        && report.format().contains("fixed 9/9"));
    }

    private static void checkHistoryExcludesRagContext() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        List<String> requestBodies = new ArrayList<>();
        var server = startHttpsServer(keyStore, (body, session, auth) -> {
            requestBodies.add(body);
            return json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ по контексту\"}}],\"usage\":{"
                    + "\"prompt_tokens\":9,\"completion_tokens\":4,\"total_tokens\":13}}");
        });
        JsonConversationStore store = tempStore();
        try {
            Config config = new Config("test-key", "https://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store, tempMemoryStore());
            String privateChunk = "RAG_CHUNK_MUST_NOT_BE_ARCHIVED";
            agent.askWithRagContext("original question", RagConstants.SYSTEM_PROMPT,
                    "Контекст:\n" + privateChunk + "\n\nВопрос: original question");
            expect("текущий запрос отправляет RAG-чанк модели",
                    requestBodies.size() == 1 && requestBodies.get(0).contains(privateChunk)
                            && requestBodies.get(0).contains("\"max_tokens\":4096"));
            expect("в историю и JSON-файл записываются только исходный вопрос и ответ",
                    agent.getHistory().equals(List.of(new ChatMessage("user", "original question"),
                            new ChatMessage("assistant", "Ответ по контексту")))
                            && !Files.readString(store.file(), StandardCharsets.UTF_8)
                            .contains(privateChunk));
        } finally {
            store.close();
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    private static void checkModeCommands() throws Exception {
        IndexStore empty = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-command-"));
        RagService service = new RagService(new RagRetriever(empty, new CountingEmbedder(8)),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, maxTokens) -> "answer");
        LlmAgent agent = newMemoryAgent(new Config("test-key",
                        "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                HttpClient.newHttpClient(), Map.of());
        FakeUi ui = new FakeUi(TerminalUi.Input.command("/rag status"),
                TerminalUi.Input.command("/rag on"), TerminalUi.Input.command("/rag status"),
                TerminalUi.Input.command("/rag off"), TerminalUi.Input.command("/rag status"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "glm-5.3-flash", service);
        String output = String.join("\n", ui.systems);
        expect("/rag on, /rag off и /rag status переключают режим без API",
                output.contains("Режим RAG: выключен") && output.contains("RAG включён")
                        && output.contains("Режим RAG: включён")
                        && output.contains("Режим RAG: выключен") && ui.errors.isEmpty());
    }

    private static void checkQuestionsAndMetrics() throws Exception {
        List<RagEval.Question> questions = RagEval.loadQuestions();
        expect("questions.json содержит ровно 10 вопросов с question и expected",
                questions.size() == 10 && questions.stream().allMatch(question ->
                        !question.question().isBlank() && !question.expected().isEmpty()));
        expect("в questions.json есть контрольный вопрос без ожидаемого источника",
                questions.stream().filter(RagEval.Question::noAnswerExpected).count() == 1);
        expect("factsHit считает совпавшие ключевые факты без учёта регистра",
                RagEval.factsHit("FILELOCK and atomic replacement", List.of(
                        "FileLock", "atomic", "missing")) == 2);
        expect("sourceHit ищет ожидаемый файл среди retrieved chunks",
                RagEval.sourceMatch(List.of(chunk("src/main/java/A.java", "s", "text")),
                        "A.java|B.java")
                        && !RagEval.sourceMatch(List.of(chunk("src/main/java/C.java", "s", "text")),
                        "A.java|B.java"));
        expect("cited проверяет упоминание ожидаемого файла, а no-answer распознаётся",
                RagEval.citedExpectedSource("Факт [A.java]", "src/main/java/A.java")
                        && RagEval.saysNoAnswer("В базе нет ответа."));
        List<RagRetriever.Chunk> citedChunks = List.of(
                chunk("src/main/java/A.java", "A", "first"),
                chunk("src/main/java/B.java", "B", "second"));
        expect("cited отображает номер [2] на source второго используемого чанка",
                RagEval.citedExpectedSource("Факт [2]", "src/main/java/B.java", citedChunks));
        expect("cited принимает [source 2], имя или полный путь без учёта регистра",
                RagEval.citedExpectedSource("Факт [source 2]", "src/main/java/B.java", citedChunks)
                        && RagEval.citedExpectedSource("Факт [b.java]", "src/main/java/B.java",
                        citedChunks)
                        && RagEval.citedExpectedSource("Факт [SRC/MAIN/JAVA/B.JAVA]",
                        "src/main/java/B.java", citedChunks));
        expect("cited=false для ссылки на другой source или ответа без ссылки",
                !RagEval.citedExpectedSource("Факт [2]", "src/main/java/A.java", citedChunks)
                        && !RagEval.citedExpectedSource("Факт из B.java", "src/main/java/B.java",
                        citedChunks));
    }

    private static IndexStore populatedStore(Path dir, String question, int dimension)
            throws Exception {
        CountingEmbedder vectors = new CountingEmbedder(dimension);
        IndexStore store = new IndexStore(dir);
        List<IndexStore.IndexedChunk> chunks = List.of(
                new IndexStore.IndexedChunk(new ChunkMeta("chunk-a",
                        "src/main/java/com/example/DocumentLoader.java", "DocumentLoader",
                        "loading", "structure", 0, 20, 20),
                        "Loader accepts markdown and Java documents.", vectors.vector(question)),
                new IndexStore.IndexedChunk(new ChunkMeta("chunk-b", "Other.java", "Other",
                        "irrelevant", "structure", 0, 10, 10), "unrelated chunk",
                        vectors.vector("something else")));
        store.save(new IndexStore.Index("structure", "fake", dimension, Instant.now(),
                "temp", 0, 0, 0, chunks));
        return store;
    }

    private static RagRetriever.Chunk chunk(String source, String section, String text) {
        return new RagRetriever.Chunk(source, section, source + "-id", 1.0, text);
    }

    private static IndexStore.IndexedChunk indexedChunk(String id, String source,
                                                        String strategy,
                                                        RagEval.Question question,
                                                        float[] vector) {
        return new IndexStore.IndexedChunk(new ChunkMeta(id, source, source,
                question.id(), strategy, 0, 1, 1), question.note(), vector);
    }
}
