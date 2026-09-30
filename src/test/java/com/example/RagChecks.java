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
        checkRagConfigurationCommandsAndEvalModes();
        checkEvalCheckpointResumeAndLlmTimeout();
        checkDetailedThresholdScan();
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

    private static void checkAskProgressAndSingleRetrieval() throws Exception {
        String question = "unique question";
        IndexStore store = populatedStore(
                Files.createTempDirectory(baseTempDir, "rag-progress-index-"), question, 8);
        FakeUi askUi = new FakeUi(TerminalUi.Input.command("/rag ask " + question),
                TerminalUi.Input.command("/exit"));
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicReference<Boolean> searchWasShownBeforeOff = new AtomicReference<>(false);
        AtomicReference<Boolean> offWasShownBeforeOn = new AtomicReference<>(false);
        RagService askService = new RagService(new RagRetriever(store, new CountingEmbedder(8)),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT, (system, user, tokens) -> {
                    llmCalls.incrementAndGet();
                    if (user.equals(question)) {
                        searchWasShownBeforeOff.set(askUi.systems.stream()
                                .anyMatch(line -> line.startsWith("Поиск чанков…")));
                        return "off answer";
                    }
                    offWasShownBeforeOn.set(askUi.systems.stream()
                            .anyMatch(line -> line.startsWith("Ответ без RAG…")));
                    return "on answer [DocumentLoader.java]";
                });
        LlmAgent askAgent = newMemoryAgent(new Config("test-key",
                        "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                HttpClient.newHttpClient(), Map.of());
        Main.runLoop(askUi, askAgent, "glm-5.3-flash", askService);
        String askOutput = String.join("\n", askUi.systems);
        expect("/rag ask печатает поиск до LLM и показывает off сразу перед запросом on",
                searchWasShownBeforeOff.get() && offWasShownBeforeOn.get()
                        && askOutput.contains("Поиск чанков…")
                        && askOutput.contains("Ответ без RAG…")
                        && askOutput.contains("Ответ с RAG…") && llmCalls.get() == 2);

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
                absent.answer().equals(RagConstants.NO_ANSWER) && absent.filteredAll()
                        && llmCalls.get() == 0);
    }

    private static void checkRewriteFallbackAndSettingsStore() throws Exception {
        for (String label : List.of("ошибка", "пусто", "provider-empty", "длинный")) {
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
                                default -> "fallback:too-long";
                            }));
        }
        RagQueryRewriter.Result good = new RagQueryRewriter((system, question) ->
                "McpOrchestrator\n git.get-repository-status").rewrite("mcp status");
        expect("rewrite возвращает одну нормализованную строку",
                !good.rewriteFallback() && good.rewriteStatus().equals("ok")
                        && good.query().equals("McpOrchestrator git.get-repository-status"));
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
                        0.80, 0.20, 0.15, false));
        persistence.save(state);
        expect("настройки и флаг режима сохраняются и загружаются",
                persistence.load().equals(state));
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
        expect("таймаут отдельного LLM-вызова возвращается как TIMEOUT",
                timeout.status() == RagService.Status.TIMEOUT && timeoutCalls.get() == 1
                        && elapsed < 3_000 && timeout.error().contains("Таймаут LLM-вызова"));

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
