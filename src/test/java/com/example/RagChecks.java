package com.example;

import com.example.index.ChunkMeta;
import com.example.index.IndexStore;
import com.example.rag.RagConstants;
import com.example.rag.RagEval;
import com.example.rag.RagEvalCheckpointStore;
import com.example.rag.RagPromptBuilder;
import com.example.rag.RagQueryRewriter;
import com.example.rag.RagRetriever;
import com.example.rag.RagReranker;
import com.example.rag.RagSettings;
import com.example.rag.RagSettingsStore;
import com.example.rag.RagService;
import com.example.rag.CitationValidator;
import com.example.rag.RagAnswerFormatter;
import com.example.rag.RagCitationEval;
import com.example.rag.RagCitationEvalCheckpointStore;

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
        checkAskProgressAndSingleRetrieval();
        checkQuestionsAndMetrics();
        checkFilterRerankAndDiversity();
        checkRewriteFallbackAndSettingsStore();
        checkRewriteDiagnosticClientResponse();
        checkRagConfigurationCommandsAndEvalModes();
        checkEvalCheckpointResumeAndLlmTimeout();
        checkDetailedThresholdScan();
        checkCitationsAndIdk();
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
                result.answerStatus() == CitationValidator.AnswerStatus.IDK_LOW_RELEVANCE
                        && result.chunks().isEmpty()
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

    private static void checkAskProgressAndSingleRetrieval() throws Exception {
        String question = "unique question";
        IndexStore store = populatedStore(
                Files.createTempDirectory(baseTempDir, "rag-progress-index-"), question, 8);
        FakeUi askUi = new FakeUi(TerminalUi.Input.command("/rag ask " + question),
                TerminalUi.Input.command("/exit"));
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicReference<Boolean> searchWasShownBeforeRag = new AtomicReference<>(false);
        RagService askService = new RagService(new RagRetriever(store, new CountingEmbedder(8)),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT, (system, user, tokens) -> {
                    llmCalls.incrementAndGet();
                    searchWasShownBeforeRag.set(askUi.systems.stream()
                            .anyMatch(line -> line.startsWith("Поиск чанков…")));
                    return "on answer [DocumentLoader.java]";
                });
        LlmAgent askAgent = newMemoryAgent(new Config("test-key",
                        "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                HttpClient.newHttpClient(), Map.of());
        Main.runLoop(askUi, askAgent, "glm-5.3-flash", askService);
        String askOutput = String.join("\n", askUi.systems);
        expect("/rag ask печатает поиск до одного RAG-вызова, без baseline-вызова",
                searchWasShownBeforeRag.get()
                        && askOutput.contains("Поиск чанков…")
                        && askOutput.contains("Ответ с RAG…") && llmCalls.get() == 1);

        AtomicInteger retrievalLlmCalls = new AtomicInteger();
        RagService retrievalService = new RagService(
                new RagRetriever(store, new CountingEmbedder(8)), new RagPromptBuilder(),
                ContextBuilder.BASE_SYSTEM_PROMPT, (system, user, tokens) -> {
                    retrievalLlmCalls.incrementAndGet();
                    return "unexpected LLM call";
                });
        FakeUi retrievalUi = new FakeUi(
                TerminalUi.Input.command("/rag retrieval " + question),
                TerminalUi.Input.command("/exit"));
        LlmAgent retrievalAgent = newMemoryAgent(new Config("test-key",
                        "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                HttpClient.newHttpClient(), Map.of());
        Main.runLoop(retrievalUi, retrievalAgent, "glm-5.3-flash", retrievalService);
        String retrievalOutput = String.join("\n", retrievalUi.systems);
        expect("/rag retrieval <вопрос> выводит source, section, score без LLM",
                retrievalOutput.contains("DocumentLoader.java › loading")
                        && retrievalOutput.contains("score 1.000000")
                        && retrievalLlmCalls.get() == 0 && retrievalUi.errors.isEmpty());
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

    private static void checkFilterRerankAndDiversity() throws Exception {
        RagReranker reranker = new RagReranker();
        RagSettings filterOnly = new RagSettings(10, 5, 0.35, false, false);
        List<RagRetriever.Chunk> candidates = List.of(
                new RagRetriever.Chunk("a.java", "A", "low", 0.34, "low"),
                new RagRetriever.Chunk("a.java", "A", "edge", 0.35, "edge"),
                new RagRetriever.Chunk("b.java", "B", "tie-1", 0.50, "tie"),
                new RagRetriever.Chunk("c.java", "C", "tie-2", 0.50, "tie"));
        RagReranker.Result filtered = reranker.process("question", candidates, filterOnly);
        expect("фильтр отсекает только score ниже порога, equality проходит, порядок ties стабилен",
                filtered.filteredCount() == 1 && !filtered.candidates().get(0).kept()
                        && filtered.selected().stream().map(item -> item.chunk().chunkId()).toList()
                        .equals(List.of("tie-1", "tie-2", "edge")));

        RagSettings relativeSettings = RagSettings.DEFAULT.withMinScore(0.30)
                .withRelativeDelta(0.10).withRerank(false).withRewrite(false);
        RagReranker.Result relative = reranker.process("question", List.of(
                new RagRetriever.Chunk("expected.java", "A", "relative-edge", 0.80, "answer"),
                new RagRetriever.Chunk("noise.java", "B", "relative-noise", 0.55, "noise"),
                new RagRetriever.Chunk("noise2.java", "C", "relative-floor", 0.20, "noise")),
                relativeSettings);
        expect("относительный порог сочетает floor с maxScore-delta и сохраняет нужный top chunk",
                relative.selected().stream().map(item -> item.chunk().chunkId()).toList()
                        .equals(List.of("relative-edge")) && relative.filteredCount() == 2
                        && relative.candidates().get(1).reason().startsWith("score ниже относительного"));

        RagSettings rerankSettings = new RagSettings(10, 5, 0, true, false)
                .withWeights(0.70, 0.30);
        List<RagRetriever.Chunk> identifiers = List.of(
                new RagRetriever.Chunk("plain.java", "plain", "plain", 0.80, "unrelated text"),
                new RagRetriever.Chunk("McpOrchestrator.java", "orchestration", "identifier",
                        0.70, "McpOrchestrator git.get-repository-status"));
        RagReranker.Result reranked = reranker.process(
                "McpOrchestrator git.get-repository-status", identifiers, rerankSettings);
        expect("лексические совпадения identifier поднимают чанк над более высоким vectorScore",
                reranked.selected().get(0).chunk().chunkId().equals("identifier")
                        && reranked.selected().get(0).lexicalScore() > 0);
        double identifierLexical = RagReranker.lexicalScore("McpOrchestrator plain",
                new RagRetriever.Chunk("unrelated-a.java", "other", "id", 0.5,
                        "McpOrchestrator"));
        double plainLexical = RagReranker.lexicalScore("McpOrchestrator plain",
                new RagRetriever.Chunk("unrelated-b.java", "other", "plain", 0.5,
                        "plain"));
        double filenameBonus = RagReranker.lexicalScore("JsonConversationStore",
                new RagRetriever.Chunk("JsonConversationStore.java", "other", "filename",
                        0.5, "unrelated body"));
        expect("lexicalScore удваивает identifier-термины, исключает стоп-слова и ограничивает bonus имени файла",
                identifierLexical > plainLexical
                        && RagReranker.lexicalScore("как это lock-file",
                        new RagRetriever.Chunk("other.java", "other", "stop", 0.5,
                                "lock file")) > 0
                        && Math.abs(filenameBonus - RagReranker.METADATA_BONUS) < 1e-9);

        String historyQuestion = "target alpha beta gamma delta";
        List<RagRetriever.Chunk> historyCandidates = List.of(
                new RagRetriever.Chunk("noise-1.java", "other", "n1", 0.80, historyQuestion),
                new RagRetriever.Chunk("noise-2.java", "other", "n2", 0.79, historyQuestion),
                new RagRetriever.Chunk("noise-3.java", "other", "n3", 0.78, historyQuestion),
                new RagRetriever.Chunk("noise-4.java", "other", "n4", 0.77, historyQuestion),
                new RagRetriever.Chunk("JsonConversationStore.java", "write", "expected",
                        0.60, "lock replacement"),
                new RagRetriever.Chunk("lexical-decoy.java", "other", "decoy", 0.48,
                        historyQuestion));
        RagSettings historySettings = new RagSettings(20, 5, 0, true, false);
        RagReranker.Result oldWeights = reranker.process(historyQuestion, historyCandidates,
                historySettings, new RagReranker.Weights(0.70, 0.30));
        RagReranker.Result tunedWeights = reranker.process(historyQuestion, historyCandidates,
                historySettings, new RagReranker.Weights(0.90, 0.10));
        expect("вес 0.90/0.10 сохраняет baseline sourceHit history-lock при лексическом decoy",
                historyCandidates.subList(0, 5).stream().anyMatch(item ->
                        item.source().equals("JsonConversationStore.java"))
                        && oldWeights.selected().stream().noneMatch(item ->
                        item.chunk().source().equals("JsonConversationStore.java"))
                        && tunedWeights.selected().stream().anyMatch(item ->
                        item.chunk().source().equals("JsonConversationStore.java")));

        List<RagRetriever.Chunk> diversity = List.of(
                new RagRetriever.Chunk("A.java", "1", "a1", 0.99, "alpha query"),
                new RagRetriever.Chunk("A.java", "2", "a2", 0.98, "alpha query"),
                new RagRetriever.Chunk("A.java", "3", "a3", 0.97, "alpha query"),
                new RagRetriever.Chunk("B.java", "1", "b1", 0.60, "alpha query"));
        RagReranker.Result diverse = reranker.process("alpha query", diversity,
                new RagSettings(10, 4, 0, true, false).withDiversity(true));
        expect("в итоговом топе не больше двух чанков одного файла при наличии альтернатив",
                diverse.selected().stream().filter(item -> item.chunk().source().equals("A.java"))
                        .count() == 2 && diverse.selected().stream().anyMatch(item ->
                        item.chunk().source().equals("B.java")));

        Path dir = Files.createTempDirectory(baseTempDir, "rag-filter-all-");
        IndexStore store = new IndexStore(dir);
        store.save(new IndexStore.Index("structure", "fake", 8, Instant.now(), "tmp", 0,
                0, 0, List.of(indexedChunk("zero", "noise.java", "structure",
                RagEval.loadQuestions().get(0), new float[8]))));
        AtomicInteger llmCalls = new AtomicInteger();
        RagService noContext = new RagService(new RagRetriever(store, new CountingEmbedder(8)),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, tokens) -> {
                    llmCalls.incrementAndGet();
                    return "should not be called";
                }, new RagSettings(5, 5, 0.35, true, false), null);
        RagService.Result absent = noContext.ask("a question not matching", RagService.Mode.ON);
        expect("после фильтрации в ноль возвращается отказ, filteredAll и 0 вызовов LLM",
                absent.answerStatus() == CitationValidator.AnswerStatus.IDK_LOW_RELEVANCE
                        && absent.filteredAll()
                        && llmCalls.get() == 0);
    }

    private static void checkRewriteFallbackAndSettingsStore() throws Exception {
        for (String label : List.of("ошибка", "пусто", "provider-empty")) {
            RagQueryRewriter.Client client = (system, question) -> switch (label) {
                case "ошибка" -> throw new IOException("rewrite failure");
                case "provider-empty" -> throw new EmptyLlmAnswerException("empty rewrite");
                case "пусто" -> "   ";
                default -> "x".repeat(301);
            };
            RagQueryRewriter.Result result = new RagQueryRewriter(client).rewrite("исходный вопрос");
            expect("rewrite fallback при " + label,
                    result.query().equals("исходный вопрос") && result.rewriteFallback()
                            && result.rewriteStatus().equals(switch (label) {
                                case "ошибка" -> "fallback:error";
                                case "пусто", "provider-empty" -> "fallback:empty";
                                default -> "fallback:unknown";
                            }));
        }
        String longQuery = "ContextBuilder ".repeat(40);
        RagQueryRewriter.Result truncated = new RagQueryRewriter((system, question) -> longQuery)
                .rewrite("Какие данные контекста?");
        expect("слишком длинный rewrite режется до 300 символов по границе слова",
                !truncated.rewriteFallback() && truncated.rewriteStatus().equals("ok-truncated")
                        && truncated.query().length() <= 300
                        && truncated.query().endsWith("ContextBuilder"));
        AtomicReference<String> rewritePrompt = new AtomicReference<>();
        RagQueryRewriter.Result good = new RagQueryRewriter((system, question) -> {
            rewritePrompt.set(system);
            return "McpOrchestrator\n git.get-repository-status";
        }).rewrite("mcp status");
        expect("rewrite возвращает одну нормализованную строку",
                !good.rewriteFallback() && good.rewriteStatus().equals("ok")
                        && good.query().equals("McpOrchestrator git.get-repository-status")
                        && rewritePrompt.get().contains("Не более 15 слов, не длиннее 200 символов, без пояснений"));
        RagQueryRewriter.Result fencedJson = new RagQueryRewriter((system, question) ->
                "Результат:\n```json\n{\"query\":\"ContextBuilder profile facts\"}\n```")
                .rewrite("Какие слои контекста?");
        expect("rewrite извлекает query из JSON fenced block с окружающим текстом",
                !fencedJson.rewriteFallback() && fencedJson.rewriteStatus().equals("ok")
                        && fencedJson.query().equals("ContextBuilder profile facts"));
        RagQueryRewriter.Result malformedJson = new RagQueryRewriter((system, question) ->
                "```json\n{\"query\":\n```").rewrite("исходный вопрос");
        expect("ошибка разбора JSON фиксируется отдельной причиной fallback",
                malformedJson.rewriteFallback()
                        && malformedJson.rewriteStatus().equals("fallback:invalid-json"));
        RagQueryRewriter.Result timedRewrite = new RagQueryRewriter((system, question) -> {
            Thread.sleep(5_000);
            return "late";
        }, 1).rewrite("исходный вопрос");
        expect("rewrite помечает таймаут и не ждёт завершения клиента",
                timedRewrite.rewriteFallback() && timedRewrite.rewriteStatus().equals("fallback:timeout")
                        && timedRewrite.rewriteMs() < 3_000);
        Path diagnosticLog = Files.createTempDirectory(baseTempDir, "rewrite-diagnostics-")
                .resolve("rewrite-diagnostics.log");
        RagQueryRewriter.appendDiagnostic(diagnosticLog,
                new RagQueryRewriter.Diagnostic("history-lock", "test-model", 128, 20,
                        19, "length", 0, 42, true, 42, "fallback:empty",
                        RagQueryRewriter.INSTRUCTION, "Bearer abcdefghijklmnop"));
        String diagnosticText = Files.readString(diagnosticLog);
        expect("rewrite diagnostic пишет причины/метаданные и не пишет секрет, файл owner-only",
                diagnosticText.contains("finish_reason=length")
                        && diagnosticText.contains("content_length=0")
                        && diagnosticText.contains("reasoning_length=42")
                        && diagnosticText.contains("fallback:empty")
                        && !diagnosticText.contains("abcdefghijklmnop")
                        && Files.getPosixFilePermissions(diagnosticLog).equals(
                        java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
                        && RagQueryRewriter.DEFAULT_MAX_OUTPUT_TOKENS == 2048
                        && RagQueryRewriter.DEFAULT_TIMEOUT_SECONDS == 45);

        boolean invalidTopK = false;
        boolean invalidThreshold = false;
        try {
            new RagSettings(4, 5, 0.35, true, true);
        } catch (IllegalArgumentException expected) {
            invalidTopK = true;
        }
        try {
            new RagSettings(5, 3, 1.1, true, true);
        } catch (IllegalArgumentException expected) {
            invalidThreshold = true;
        }
        expect("RagSettings валидирует границы и содержит откалиброванные defaults",
                invalidTopK && invalidThreshold
                        && new RagSettings(5, 5, 0.35, true, true).minScore() == 0.35
                        && RagSettings.DEFAULT.minScore() == 0.50
                        && RagSettings.DEFAULT.relativeDelta() == 0.15
                        && RagSettings.DEFAULT.rerankVectorWeight() == 1.0
                        && RagSettings.DEFAULT.rerankLexicalWeight() == 0.0
                        && !RagSettings.DEFAULT.diversityEnabled());

        Path file = Files.createTempDirectory(baseTempDir, "rag-settings-").resolve("rag.json");
        RagSettingsStore persistence = new RagSettingsStore(file);
        RagSettingsStore.State state = new RagSettingsStore.State(true,
                new RagSettings(25, 4, 0.42, false, true,
                        0.80, 0.20, 0.15, false).withIdkThreshold(0.58));
        persistence.save(state);
        expect("настройки и флаг режима сохраняются и загружаются",
                persistence.load().equals(state)
                        && persistence.load().settings().idkThreshold() == 0.58);
    }

    private static void checkRewriteDiagnosticClientResponse() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        List<String> requestBodies = new ArrayList<>();
        var server = startHttpsServer(keyStore, (body, session, auth) -> {
            requestBodies.add(body);
            return json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"\",\"reasoning_content\":\"hidden thought\"},"
                    + "\"finish_reason\":\"length\"}],\"usage\":{"
                    + "\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30,"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":19}}}");
        });
        JsonConversationStore store = tempStore();
        try {
            Config config = new Config("test-key", "https://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store, tempMemoryStore());
            LlmAgent.StatelessCallDiagnostic diagnostic = agent.diagnoseWithoutHistory(
                    "rewrite system", "rewrite query", 512);
            expect("stateless rewrite diagnostic preserves finish_reason, empty content and reasoning length",
                    diagnostic.model().equals("glm-5.3-flash")
                            && diagnostic.maxOutputTokens() == 512
                            && diagnostic.finishReason().equals("length")
                            && diagnostic.contentLength() == 0
                            && diagnostic.reasoningLength() == "hidden thought".length()
                            && diagnostic.reasoningFieldPresent()
                            && diagnostic.reasoningTokens() == 19
                            && diagnostic.content() != null && diagnostic.content().isEmpty()
                            && diagnostic.durationMs() >= 0
                            && requestBodies.size() == 1 && requestBodies.get(0).contains("\"max_tokens\":512")
                            && !requestBodies.get(0).contains("reasoning_effort")
                            && agent.getHistory().isEmpty());
        } finally {
            store.close();
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    private static void checkRagConfigurationCommandsAndEvalModes() throws Exception {
        String question = "unique question";
        IndexStore store = populatedStore(
                Files.createTempDirectory(baseTempDir, "rag-config-index-"), question, 8);
        RagService service = new RagService(new RagRetriever(store, new CountingEmbedder(8)),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, tokens) -> "answer [DocumentLoader.java]");
        LlmAgent agent = newMemoryAgent(new Config("test-key",
                        "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                HttpClient.newHttpClient(), Map.of());
        FakeUi ui = new FakeUi(TerminalUi.Input.command("/rag config"),
                TerminalUi.Input.command("/rag set topk 12 3"),
                TerminalUi.Input.command("/rag set threshold 0.4"),
                TerminalUi.Input.command("/rag set rerank off"),
                TerminalUi.Input.command("/rag set rewrite off"),
                TerminalUi.Input.command("/rag retrieval " + question),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "glm-5.3-flash", service);
        String output = String.join("\n", ui.systems);
        expect("/rag config, set и retrieval разбирают значения, сохраняют их и выводят две таблицы",
                output.contains("topKBefore=20") && output.contains("topKBefore=12")
                        && output.contains("minScore=0.40") && output.contains("rerank=off")
                        && output.contains("rewrite=off") && output.contains("До фильтра/реранкинга")
                        && output.contains("После (top-3)") && ui.errors.isEmpty());

        List<RagEval.Question> questions = RagEval.loadQuestions();
        CountingEmbedder embedder = new CountingEmbedder(8);
        List<IndexStore.IndexedChunk> chunks = new ArrayList<>();
        for (RagEval.Question q : questions) {
            chunks.add(indexedChunk(q.id(), q.noAnswerExpected() ? "Other.java"
                    : q.expectedSources().split("\\|")[0], "structure", q,
                    embedder.vector(q.question())));
        }
        IndexStore evalStore = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-modes-"));
        evalStore.save(new IndexStore.Index("structure", "fake", 8, Instant.now(), "tmp", 0,
                0, 0, chunks));
        RagService evalService = new RagService(new RagRetriever(evalStore, embedder),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, tokens) -> "В базе нет ответа", RagSettings.DEFAULT,
                new RagQueryRewriter((system, q) -> "code " + q));
        RagEval.ComparisonReport report = RagEval.runModes(evalService, RagSettings.DEFAULT,
                ignored -> { }, ignored -> { });
        expect("eval-отчёт сравнивает все режимы A/B/C/D",
                report.rows().size() == 10 && report.markdown().contains("A no-rag")
                        && report.markdown().contains("B baseline")
                        && report.markdown().contains("C filter")
                        && report.markdown().contains("D full")
                        && report.markdown().contains("D rewriteStatus")
                        && report.markdown().contains("Threshold-фильтр сам по себе не считается отказом")
                        && report.markdown().contains("rewriteStatus=ok")
                        && report.summaries().size() == 4);
    }

    private static void checkEvalCheckpointResumeAndLlmTimeout() throws Exception {
        List<RagEval.Question> questions = RagEval.loadQuestions();
        CountingEmbedder embedder = new CountingEmbedder(8);
        List<IndexStore.IndexedChunk> chunks = new ArrayList<>();
        for (RagEval.Question question : questions) {
            chunks.add(indexedChunk(question.id(), question.noAnswerExpected() ? "Trap.java"
                    : question.expectedSources().split("\\|")[0], "structure", question,
                    embedder.vector(question.question())));
        }
        IndexStore index = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-checkpoint-index-"));
        index.save(new IndexStore.Index("structure", "fake", 8, Instant.now(), "tmp", 0,
                0, 0, chunks));
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicInteger rewriteCalls = new AtomicInteger();
        RagService service = new RagService(new RagRetriever(index, embedder),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, tokens) -> {
                    llmCalls.incrementAndGet();
                    return "В базе нет ответа";
                }, RagSettings.DEFAULT, new RagQueryRewriter((system, question) -> {
                    rewriteCalls.incrementAndGet();
                    return question;
                }));
        Path checkpointPath = Files.createTempDirectory(baseTempDir, "rag-checkpoint-")
                .resolve("checkpoint.json");
        List<String> ids = questions.stream().map(RagEval.Question::id).toList();
        RagEval.ComparisonReport ab = RagEval.runResumable(service, RagSettings.DEFAULT,
                List.of("A", "B"), ids, false, checkpointPath, ignored -> { }, ignored -> { });
        RagEvalCheckpointStore loaded = new RagEvalCheckpointStore(checkpointPath);
        loaded.load();
        int afterAbCalls = llmCalls.get();
        expect("eval checkpoint создаётся и содержит каждую пару A/B после обработки",
                loaded.exists() && loaded.completedPairs(ids, List.of("A", "B")) == 20
                        && afterAbCalls == 20
                        && ab.markdown().contains("history-lock/C")
                        && ab.missingPairs().get("C") == 10 && ab.missingPairs().get("D") == 10);

        RagEval.runResumable(service, RagSettings.DEFAULT, List.of("A", "B"), ids,
                true, checkpointPath, ignored -> { }, ignored -> { });
        expect("--resume пропускает завершённые A/B и не повторяет вызовы модели",
                llmCalls.get() == afterAbCalls);

        RagEval.ComparisonReport cd = RagEval.runResumable(service, RagSettings.DEFAULT,
                List.of("C", "D"), ids, true, checkpointPath, ignored -> { }, ignored -> { });
        loaded = new RagEvalCheckpointStore(checkpointPath);
        loaded.load();
        int afterCdCalls = llmCalls.get();
        expect("второй порционный запуск дописывает C/D и закрывает все 40 пар",
                loaded.completedPairs(ids, List.of("A", "B", "C", "D")) == 40
                        && afterCdCalls == 40
                        && cd.missingPairs().values().stream().allMatch(value -> value == 0)
                        && cd.markdown().contains("Недостающие пары")
                        && cd.markdown().contains("D rewriteStatus")
                        && loaded.result("history-lock", "D").rewriteStatus().equals("ok"));
        RagEval.runResumable(service, RagSettings.DEFAULT, List.of("C", "D"), ids,
                true, checkpointPath, ignored -> { }, ignored -> { });
        expect("--resume сохраняет A-ответы и rewrite cache между процессными запусками",
                llmCalls.get() == afterCdCalls && rewriteCalls.get() == 10);

        AtomicInteger timeoutCalls = new AtomicInteger();
        RagService timed = new RagService(new RagRetriever(
                new IndexStore(Files.createTempDirectory(baseTempDir, "rag-timeout-index-")),
                new CountingEmbedder(8)), new RagPromptBuilder(),
                ContextBuilder.BASE_SYSTEM_PROMPT, (system, user, tokens) -> {
                    timeoutCalls.incrementAndGet();
                    Thread.sleep(5_000);
                    return "too late";
                }, RagSettings.DEFAULT, null, 1);
        long started = System.nanoTime();
        RagService.Result timeout = timed.ask("timeout question", RagService.Mode.OFF);
        long elapsed = RagService.elapsedMs(started);
        expect("таймаут LLM-вызова повторяется один раз и после второго возвращается TIMEOUT",
                timeout.status() == RagService.Status.TIMEOUT && timeoutCalls.get() == 2
                        && elapsed < 5_000 && timeout.error().contains("Таймаут LLM-вызова"));

        Main.RagEvalCommand parsed = Main.parseRagEvalCommand(
                "C,D --questions history-lock,context-layers --resume "
                        + "--checkpoint rag-eval-checkpoint-new.json");
        expect("eval parser принимает режимы, вопросы, --resume и безопасное имя checkpoint",
                parsed.modes().equals(List.of("C", "D"))
                        && parsed.questionIds().equals(List.of("history-lock", "context-layers"))
                        && parsed.resume()
                        && parsed.checkpointName().equals("rag-eval-checkpoint-new.json")
                        && Main.ragEvalCheckpointPath(Path.of("/home/test"),
                        java.time.LocalDate.of(2026, 9, 30), parsed.checkpointName())
                        .equals(Path.of("/home/test/.ai-advent-agent/rag-results/"
                                + "rag-eval-checkpoint-new.json")));
    }

    private static void checkDetailedThresholdScan() throws Exception {
        RagEval.Question answerable = new RagEval.Question("history-lock", "answer query",
                List.of("fact"), "Needed.java", "expected source");
        RagEval.Question trap = new RagEval.Question("trap", "trap query",
                List.of(RagConstants.NO_ANSWER), "", "no matching content");
        com.example.index.Embedder embedder = new com.example.index.Embedder() {
            @Override public int batchSize() { return 2; }
            @Override public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> text.equals("answer query")
                        ? new float[]{1, 0, 0} : new float[]{0, 1, 0}).toList();
            }
        };
        List<IndexStore.IndexedChunk> fixedChunks = List.of(indexedChunk("needed-fixed",
                "Needed.java", "fixed", answerable, new float[]{1, 0, 0}),
                indexedChunk("needed-fixed-2", "Needed.java", "fixed", answerable,
                        new float[]{0.65f, 0.65f, 0.3937f}));
        List<IndexStore.IndexedChunk> structureChunks = List.of(indexedChunk("needed-structure",
                "Needed.java", "structure", answerable, new float[]{1, 0, 0}),
                indexedChunk("needed-structure-2", "Needed.java", "structure", answerable,
                        new float[]{0.65f, 0.65f, 0.3937f}));
        IndexStore store = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-threshold-scan-"));
        store.save(new IndexStore.Index("fixed", "fake", 3, Instant.now(), "tmp", 0,
                0, 0, fixedChunks));
        store.save(new IndexStore.Index("structure", "fake", 3, Instant.now(), "tmp", 0,
                0, 0, structureChunks));
        RagEval.ThresholdScanReport scan = RagEval.thresholdScanDetails(List.of(answerable, trap),
                new RagRetriever(store, embedder, "fixed"),
                new RagRetriever(store, embedder, "structure"));
        expect("threshold-scan выводит expected-source rank, score distribution и сетку до 0.70",
                scan.markdown().contains("expected-source score")
                        && scan.markdown().contains("median")
                        && scan.markdown().contains("| structure | 0.70 |")
                        && scan.selectedThreshold() == 0.70 && scan.calibrated()
                        && scan.selectedStrategy().equals("structure"));
        expect("абсолютная калибровка сохраняет нужный чанк и отсеивает ловушку",
                scan.markdown().contains("нужных сохранено")
                        && scan.markdown().contains("ловушка отсечена")
                        && scan.markdown().contains("| structure | 0.70 | 1/2 | 2 | 1 | да |"));

        RagEval.Question lowAnswer = new RagEval.Question("low-answer", "low answer query",
                List.of("fact"), "Needed.java", "low-scoring expected chunks");
        RagEval.Question lowTrap = new RagEval.Question("low-trap", "low trap query",
                List.of(RagConstants.NO_ANSWER), "", "trap below expected floor");
        com.example.index.Embedder lowEmbedder = new com.example.index.Embedder() {
            @Override public int batchSize() { return 2; }
            @Override public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> text.equals("low answer query")
                        ? new float[]{1, 0, 0} : new float[]{0, 1, 0}).toList();
            }
        };
        List<IndexStore.IndexedChunk> lowChunks = List.of(
                indexedChunk("low-needed-1", "Needed.java", "structure", lowAnswer,
                        new float[]{0.3f, 0.3f, 0.9055f}),
                indexedChunk("low-needed-2", "Needed.java", "structure", lowAnswer,
                        new float[]{0.4f, 0.4f, 0.8246f}),
                indexedChunk("low-noise", "Noise.java", "structure", lowAnswer,
                        new float[]{0.5f, 0.6f, 0.6245f}));
        IndexStore lowStore = new IndexStore(Files.createTempDirectory(baseTempDir,
                "rag-relative-threshold-"));
        lowStore.save(new IndexStore.Index("fixed", "fake", 3, Instant.now(), "tmp", 0,
                0, 0, lowChunks));
        lowStore.save(new IndexStore.Index("structure", "fake", 3, Instant.now(), "tmp", 0,
                0, 0, lowChunks));
        RagEval.ThresholdScanReport compromise = RagEval.thresholdScanDetails(
                List.of(lowAnswer, lowTrap), new RagRetriever(lowStore, lowEmbedder, "fixed"),
                new RagRetriever(lowStore, lowEmbedder, "structure"));
        expect("если абсолютный порог теряет больше одного нужного чанка, relative-компромисс показан честно",
                !compromise.calibrated() && compromise.relativeDelta() != null
                        && compromise.markdown().contains("Ни один абсолютный и относительный порог")
                        && compromise.markdown().contains("потеря нужных — 2")
                        && compromise.markdown().contains("Лучший компромисс"));

        com.example.index.Embedder identical = new com.example.index.Embedder() {
            @Override public int batchSize() { return 2; }
            @Override public List<float[]> embed(List<String> texts) {
                return texts.stream().map(ignored -> new float[]{1, 0, 0}).toList();
            }
        };
        RagEval.RerankAnalysisReport analysis = RagEval.rerankAnalysis(List.of(answerable, trap),
                new RagRetriever(store, identical, "structure"), RagSettings.DEFAULT);
        expect("rerank-analysis sweeps generic weights and prints source ranks/lexical reason; "
                        + "constraints=" + analysis.constraintsMet() + ", baselinePrecision="
                        + analysis.baselinePrecision() + ", rerankPrecision="
                        + analysis.selectedPrecision() + ", baselineHits="
                        + analysis.baselineSourceHits() + ", rerankHits="
                        + analysis.selectedSourceHits() + ", header="
                        + analysis.markdown().contains("| Чанк | vector rank | rerank rank | lexicalScore | reason |"),
                analysis.constraintsMet() && analysis.baselinePrecision() == 1.0
                        && analysis.selectedPrecision() >= analysis.baselinePrecision()
                        && analysis.selectedPrecision() >= 0.43
                        && analysis.baselineSourceHits() == analysis.selectedSourceHits()
                        && analysis.markdown().contains("| Чанк | vector rank | rerank rank | lexicalScore | reason |")
                        && analysis.markdown().contains("expectedSources"));
    }

    private static void checkCitationsAndIdk() throws Exception {
        String question = "citation contract question";
        CountingEmbedder embedder = new CountingEmbedder(8);
        String exactQuote = "The indexed source preserves this exact sentence for citation validation.";
        List<IndexStore.IndexedChunk> indexed = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String source = "src/main/java/Source" + i + ".java";
            String text = i == 1 ? exactQuote + " Additional supporting text." : "other indexed text " + i;
            indexed.add(new IndexStore.IndexedChunk(new ChunkMeta("chunk-" + i, source,
                    "Source" + i, "section-" + i, "structure", 0, text.length(), text.length()),
                    text, embedder.vector(question)));
        }
        IndexStore store = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-citations-"));
        store.save(new IndexStore.Index("structure", "fake", 8, Instant.now(), "tmp", 0,
                0, 0, indexed));
        RagSettings settings = new RagSettings(5, 5, 0, false, false)
                .withIdkThreshold(0.5);

        String validAnswer = "Тезис подтверждён [1].\n[1] «" + exactQuote + "»\n"
                + "Источники: [5] invented.java";
        RagService.Result valid = citationService(store, embedder, settings, validAnswer,
                new AtomicInteger()).ask(question, RagService.Mode.ON);
        String formatted = RagAnswerFormatter.format(valid, false);
        expect("корректный ответ получает ANSWERED, подтверждённую цитату и источник из кода",
                valid.answerStatus() == CitationValidator.AnswerStatus.ANSWERED
                        && valid.citations().confirmedQuotes().size() == 1
                        && valid.citations().invalidReferences() == 0
                        && formatted.contains("[1] src/main/java/Source1.java — section-1 (chunk: chunk-1)")
                        && !formatted.contains("invented.java")
                        && !formatted.contains("Статус:")
                        && RagAnswerFormatter.format(valid, true).contains("Статус: ANSWERED; отброшено цитат: 0"));

        RagService.Result fabricated = citationService(store, embedder, settings,
                "Факт [1].\n[1] «This sentence was invented and is not present in the indexed source.»",
                new AtomicInteger()).ask(question, RagService.Mode.ON);
        expect("выдуманная цитата отбрасывается и ответ становится UNVERIFIED",
                fabricated.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED
                        && fabricated.citations().rejectedQuotes() == 1
                        && RagAnswerFormatter.format(fabricated, false)
                        .contains("Не могу подтвердить ответ цитатами из базы")
                        && !RagAnswerFormatter.format(fabricated, false)
                        .contains("This sentence was invented"));

        RagService.Result outOfRange = citationService(store, embedder, settings,
                "Факт [7].\n[1] «" + exactQuote + "»", new AtomicInteger())
                .ask(question, RagService.Mode.ON);
        expect("ссылка [7] при пяти чанках учитывается как ошибка диапазона",
                outOfRange.citations().invalidReferences() == 1
                        && outOfRange.answerStatus() == CitationValidator.AnswerStatus.ANSWERED);

        String ownSectionAnswer = "Тезис [1].\n## Цитаты\n\n[1] «" + exactQuote + "»\n"
                + "Источники: [5] invented.java";
        RagService.Result ownSection = citationService(store, embedder, settings, ownSectionAnswer,
                new AtomicInteger()).ask(question, RagService.Mode.ON);
        String ownSectionFormatted = RagAnswerFormatter.format(ownSection, false);
        expect("answerText вырезает секции Цитаты/Источники модели, а validate проверяет эти цитаты",
                ownSection.answerStatus() == CitationValidator.AnswerStatus.ANSWERED
                        && ownSection.citations().confirmedQuotes().size() == 1
                        && CitationValidator.answerText(ownSectionAnswer).equals("Тезис [1].")
                        && ownSectionFormatted.contains("Ответ: Тезис [1].")
                        && !ownSectionFormatted.contains("## Цитаты")
                        && !ownSectionFormatted.contains("invented.java"));

        RagService.Result noQuotes = citationService(store, embedder, settings,
                "Ответ со ссылкой [1].", new AtomicInteger()).ask(question, RagService.Mode.ON);
        expect("ответ без цитат имеет статус UNVERIFIED",
                noQuotes.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED);

        RagService.Result modelIdk = citationService(store, embedder, settings,
                "НЕ ЗНАЮ", new AtomicInteger()).ask(question, RagService.Mode.ON);
        expect("ответ НЕ ЗНАЮ получает IDK_MODEL",
                modelIdk.answerStatus() == CitationValidator.AnswerStatus.IDK_MODEL);

        String spacedText = "Пробелы сохраняются    при переносе строки\nи в точной цитате достаточно символов.";
        String spacedQuote = "Пробелы сохраняются при переносе\nстроки и в точной цитате достаточно символов.";
        String normalizedAnswer = "Текст [1].\n[1] «" + spacedQuote + "»";
        List<IndexStore.IndexedChunk> spacedChunks = List.of(new IndexStore.IndexedChunk(
                new ChunkMeta("spacing", "Spacing.java", "Spacing", "spacing", "structure",
                        0, spacedText.length(), spacedText.length()), spacedText, embedder.vector(question)));
        IndexStore spacedStore = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-cite-spaces-"));
        spacedStore.save(new IndexStore.Index("structure", "fake", 8, Instant.now(), "tmp", 0,
                0, 0, spacedChunks));
        RagService.Result normalized = citationService(spacedStore, embedder,
                new RagSettings(1, 1, 0, false, false).withIdkThreshold(0.5),
                normalizedAnswer, new AtomicInteger()).ask(question, RagService.Mode.ON);
        expect("цитата с другими пробелами и переносом подтверждается после нормализации",
                normalized.answerStatus() == CitationValidator.AnswerStatus.ANSWERED);

        RagService.Result shortQuote = citationService(store, embedder, settings,
                "Факт [1].\n[1] «Too short quote.»", new AtomicInteger())
                .ask(question, RagService.Mode.ON);
        expect("цитата короче 20 символов отбрасывается",
                shortQuote.citations().rejectedQuotes() == 1
                        && shortQuote.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED);

        AtomicInteger emptyCalls = new AtomicInteger();
        RagService.Result empty = citationService(store, embedder, settings, "", emptyCalls)
                .ask(question, RagService.Mode.ON);
        expect("пустой ответ модели завершается UNVERIFIED",
                empty.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED
                        && empty.status() == RagService.Status.EMPTY && emptyCalls.get() == 2);

        com.example.index.Embedder lowEmbedder = new com.example.index.Embedder() {
            @Override public int batchSize() { return 4; }
            @Override public List<float[]> embed(List<String> texts) {
                return texts.stream().map(ignored -> new float[]{1, 0}).toList();
            }
        };
        List<IndexStore.IndexedChunk> lowChunks = List.of(new IndexStore.IndexedChunk(
                new ChunkMeta("low", "Low.java", "Low", "low", "structure", 0, 20, 20),
                "not relevant", new float[]{0.2f, (float) Math.sqrt(0.96)}));
        IndexStore lowStore = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-idk-low-"));
        lowStore.save(new IndexStore.Index("structure", "fake", 2, Instant.now(), "tmp", 0,
                0, 0, lowChunks));
        AtomicInteger lowCalls = new AtomicInteger();
        RagService.Result low = new RagService(new RagRetriever(lowStore, lowEmbedder),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, tokens) -> { lowCalls.incrementAndGet(); return "should not run"; },
                new RagSettings(1, 1, 0, false, false).withIdkThreshold(0.5), null)
                .ask("weak query", RagService.Mode.ON);
        expect("top-1 ниже idkThreshold даёт IDK_THRESHOLD без вызова модели",
                low.answerStatus() == CitationValidator.AnswerStatus.IDK_THRESHOLD
                        && lowCalls.get() == 0 && low.answer().startsWith("Не знаю:"));

        RagSettings thresholdSettings = new RagSettings(1, 1, 0.50, false, false)
                .withIdkThreshold(0.578629);
        String thresholdText = "This retrieved text supports the mocked answer correctly.";
        IndexStore midScoreStore = scoreStore(0.56, thresholdText);
        AtomicInteger midScoreCalls = new AtomicInteger();
        RagService.Result midScore = new RagService(new RagRetriever(midScoreStore, lowEmbedder),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, tokens) -> { midScoreCalls.incrementAndGet(); return "unexpected"; },
                thresholdSettings, null).ask("between min and idk", RagService.Mode.ON);
        expect("score 0.56 проходит minScore 0.50, но IDK_THRESHOLD не вызывает модель",
                midScore.answerStatus() == CitationValidator.AnswerStatus.IDK_THRESHOLD
                        && midScoreCalls.get() == 0 && midScore.filteredCount() == 0);

        IndexStore aboveThresholdStore = scoreStore(0.60, thresholdText);
        AtomicInteger aboveThresholdCalls = new AtomicInteger();
        RagService.Result aboveThreshold = new RagService(new RagRetriever(aboveThresholdStore,
                lowEmbedder), new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, tokens) -> {
                    aboveThresholdCalls.incrementAndGet();
                    return "Факт [1].\n[1] «" + thresholdText + "»";
                }, thresholdSettings, null).ask("above idk", RagService.Mode.ON);
        expect("score выше idkThreshold вызывает модель",
                aboveThreshold.answerStatus() == CitationValidator.AnswerStatus.ANSWERED
                        && aboveThresholdCalls.get() == 1);

        Main.RagCitationEvalCommand citeCommand = Main.parseRagCitationEvalCommand(
                "--checkpoint ~/.ai-advent-agent/rag-results/cite.json"
                        + " --report ~/.ai-advent-agent/rag-results/cite-report.md");
        expect("cite eval принимает отдельный checkpoint/report и разворачивает ~",
                citeCommand.checkpoint().endsWith(".ai-advent-agent/rag-results/cite.json")
                        && citeCommand.report().endsWith(".ai-advent-agent/rag-results/cite-report.md"));
        boolean rejectNotJson = false;
        boolean rejectNotMarkdown = false;
        boolean rejectUnknownOption = false;
        try {
            Main.parseRagCitationEvalCommand("--checkpoint report.md");
        } catch (IllegalArgumentException expected) {
            rejectNotJson = true;
        }
        try {
            Main.parseRagCitationEvalCommand("--report cite.json");
        } catch (IllegalArgumentException expected) {
            rejectNotMarkdown = true;
        }
        try {
            Main.parseRagCitationEvalCommand("--attempts 2");
        } catch (IllegalArgumentException expected) {
            rejectUnknownOption = true;
        }
        expect("cite eval отклоняет неверные расширения и посторонние опции",
                rejectNotJson && rejectNotMarkdown && rejectUnknownOption
                        && Main.parseRagCitationEvalCommand("").checkpoint() == null
                        && Main.parseRagCitationEvalCommand("").report() == null);

        String evalQuote = "This indexed sentence is long enough to be used as a verified quotation.";
        com.example.index.Embedder commonEmbedder = new com.example.index.Embedder() {
            @Override public int batchSize() { return 16; }
            @Override public List<float[]> embed(List<String> texts) {
                return texts.stream().map(ignored -> new float[]{1, 0}).toList();
            }
        };
        IndexStore evalStore = new IndexStore(Files.createTempDirectory(baseTempDir,
                "rag-cite-eval-stub-"));
        evalStore.save(new IndexStore.Index("structure", "fake", 2, Instant.now(), "tmp", 0,
                0, 0, List.of(new IndexStore.IndexedChunk(new ChunkMeta("cite-eval-chunk",
                "src/main/java/Citation.java", "Citation", "citation", "structure", 0,
                evalQuote.length(), evalQuote.length()), evalQuote, new float[]{1, 0}))));
        AtomicInteger citeLlmCalls = new AtomicInteger();
        String fixedLimitsQuestion = RagEval.loadQuestions().stream()
                .filter(item -> item.id().equals("fixed-limits"))
                .findFirst().orElseThrow().question();
        String fabricatedCiteQuote = "This fabricated sentence is definitely absent from every indexed chunk.";
        RagService evalService = new RagService(new RagRetriever(evalStore, commonEmbedder),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT,
                (system, user, tokens) -> {
                    citeLlmCalls.incrementAndGet();
                    if (user.contains(RagEval.loadQuestions().stream()
                            .filter(RagEval.Question::noAnswerExpected).findFirst().orElseThrow().question())) {
                        return "НЕ ЗНАЮ";
                    }
                    if (user.contains(fixedLimitsQuestion)) {
                        return "Факт [1].\n[1] «" + fabricatedCiteQuote + "»";
                    }
                    return "Факт [1].\n[1] «" + evalQuote + "»";
                }, settings, null);
        Path citeCheckpoint = Files.createTempDirectory(baseTempDir, "rag-cite-eval-checkpoint-")
                .resolve("citations.json");
        List<String> citeLog = new ArrayList<>();
        String citeParameters = "model=test-model, max_tokens=4096, temperature=default"
                + ", topK=5/5, minScore=0.00, idkThreshold=0.500000, rewrite=off (cite eval)";
        RagCitationEval.Report citeReport = RagCitationEval.run(evalService, commonEmbedder,
                settings, citeCheckpoint, citeParameters, citeLog::add, ignored -> { });
        int citeCallsAfterFirstRun = citeLlmCalls.get();
        String citeLogText = String.join("\n", citeLog);
        RagCitationEval.Report resumedCiteReport = RagCitationEval.run(evalService, commonEmbedder,
                settings, citeCheckpoint, citeParameters, citeLog::add, ignored -> { });
        expect("cite eval stub проверяет все 10 вопросов, ловушку, цитаты, cosine и resume",
                citeCallsAfterFirstRun == 10 && citeLlmCalls.get() == citeCallsAfterFirstRun
                        && citeReport.rows().size() == 10
                        && citeReport.answersWithSources() == 9
                        && citeReport.answersWithQuotes() == 9
                        && citeReport.answeredCount() == 8
                        && citeReport.answerableTotal() == 9
                        && citeReport.trapHandled()
                        && resumedCiteReport.markdown().contains("Cosine — эвристика")
                        && resumedCiteReport.markdown().contains("смысл совпадает (ручная)"));
        expect("cite eval логирует статус, top-1, сырой ответ и причины отбраковки",
                citeLogText.contains("fixed-limits | UNVERIFIED | top-1 1.000000 | цитаты 0/1")
                        && citeLogText.contains("сырой ответ: Факт [1].")
                        && citeLogText.contains("отброшены: [1] not-a-verbatim-substring")
                        && citeLogText.contains("not-in-database | IDK_MODEL")
                        && citeLogText.contains("сырой ответ: НЕ ЗНАЮ")
                        && citeLogText.contains("цитат нет"));
        expect("cite eval отчёт содержит параметры, сводку статусов, отбракованные цитаты и итог",
                citeReport.markdown().contains("Параметры прогона: model=test-model, max_tokens=4096")
                        && citeReport.markdown().contains("## Сводка статусов (из 10)")
                        && citeReport.markdown().contains("ANSWERED: 8")
                        && citeReport.markdown().contains("UNVERIFIED: 1")
                        && citeReport.markdown().contains("IDK_MODEL: 1")
                        && citeReport.markdown().contains("| fixed-limits | 1 | not-a-verbatim-substring | "
                        + fabricatedCiteQuote + " |")
                        && citeReport.markdown().contains("Отвечено: 8/9 из имеющих ответ")
                        && citeReport.markdown().contains("- Ловушка: да (корректный отказ")
                        && citeReport.markdown().contains("Длительность: всего")
                        && citeReport.markdown().contains("факты найдено")
                        && citeReport.markdown().contains("| fixed-limits | UNVERIFIED |"));
    }

    private static RagService citationService(IndexStore store, CountingEmbedder embedder,
                                               RagSettings settings, String response,
                                               AtomicInteger calls) {
        return new RagService(new RagRetriever(store, embedder), new RagPromptBuilder(),
                ContextBuilder.BASE_SYSTEM_PROMPT, (system, user, tokens) -> {
                    calls.incrementAndGet();
                    return response;
                }, settings, null);
    }

     private static IndexStore scoreStore(double score, String text)
             throws Exception {
        IndexStore store = new IndexStore(Files.createTempDirectory(baseTempDir, "rag-score-boundary-"));
        store.save(new IndexStore.Index("structure", "fake", 2, Instant.now(), "tmp", 0,
                0, 0, List.of(new IndexStore.IndexedChunk(new ChunkMeta("score", "Score.java",
                "Score", "score", "structure", 0, text.length(), text.length()), text,
                new float[]{(float) score, (float) Math.sqrt(1 - score * score)}))));
        return store;
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
