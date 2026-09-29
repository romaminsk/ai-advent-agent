package com.example;

import com.example.index.ChunkMeta;
import com.example.index.IndexStore;
import com.example.rag.RagConstants;
import com.example.rag.RagEval;
import com.example.rag.RagPromptBuilder;
import com.example.rag.RagRetriever;
import com.example.rag.RagService;

import java.net.http.HttpClient;
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
        RagService service = new RagService(new RagRetriever(store, embedder),
                new RagPromptBuilder(), ContextBuilder.BASE_SYSTEM_PROMPT, (system, user) -> {
                    systemPrompt.set(system);
                    userPrompt.set(user);
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
                builder, ContextBuilder.BASE_SYSTEM_PROMPT, (system, user) -> {
                    llmCalls.incrementAndGet();
                    return "неожиданный ответ";
                });
        RagService.Result result = noHits.ask("unindexed", RagService.Mode.ON);
        expect("пустой поиск возвращает честный отказ без вызова LLM",
                result.answer().equals(RagConstants.NO_ANSWER) && result.chunks().isEmpty()
                        && llmCalls.get() == 0);
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
                    requestBodies.size() == 1 && requestBodies.get(0).contains(privateChunk));
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
                (system, user) -> "answer");
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
}
