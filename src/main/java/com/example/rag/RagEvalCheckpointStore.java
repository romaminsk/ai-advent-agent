package com.example.rag;

import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Atomic, resumable storage for question/mode answers and rewrite cache. */
public final class RagEvalCheckpointStore {
    public static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final Map<String, Map<String, RagService.Result>> results = new LinkedHashMap<>();
    private final Map<String, RagQueryRewriter.Result> rewrites = new LinkedHashMap<>();

    public RagEvalCheckpointStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    public void load() throws IOException {
        results.clear();
        rewrites.clear();
        if (!exists()) return;
        JsonNode root = JsonSupport.MAPPER.readTree(file.toFile());
        if (root.path("schemaVersion").asInt() != SCHEMA_VERSION) {
            throw new IOException("Неподдерживаемая версия RAG eval checkpoint");
        }
        JsonNode rewriteNode = root.path("rewrites");
        rewriteNode.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            rewrites.put(entry.getKey(), new RagQueryRewriter.Result(
                    node.path("query").asText(entry.getKey()),
                    node.path("fallback").asBoolean(false), node.path("rewriteMs").asLong(0)));
        });
        JsonNode resultsNode = root.path("results");
        var questions = resultsNode.fields();
        while (questions.hasNext()) {
            var question = questions.next();
            Map<String, RagService.Result> modes = new LinkedHashMap<>();
            var modeFields = question.getValue().fields();
            while (modeFields.hasNext()) {
                var mode = modeFields.next();
                modes.put(mode.getKey(), readResult(mode.getValue()));
            }
            results.put(question.getKey(), modes);
        }
    }

    public void clear() {
        results.clear();
        rewrites.clear();
    }

    public void initialize() throws IOException {
        save();
    }

    public boolean hasResult(String questionId, String mode) {
        return results.containsKey(questionId) && results.get(questionId).containsKey(mode);
    }

    public RagService.Result result(String questionId, String mode) {
        return results.getOrDefault(questionId, Map.of()).get(mode);
    }

    public void putResult(String questionId, String mode, RagService.Result result)
            throws IOException {
        results.computeIfAbsent(questionId, ignored -> new LinkedHashMap<>()).put(mode, result);
        save();
    }

    public RagQueryRewriter.Result rewrite(String questionId) {
        return rewrites.get(questionId);
    }

    public void putRewrite(String questionId, RagQueryRewriter.Result rewrite)
            throws IOException {
        rewrites.put(questionId, rewrite);
        save();
    }

    public int completedPairs(List<String> questionIds, List<String> modes) {
        int count = 0;
        for (String id : questionIds) {
            for (String mode : modes) if (hasResult(id, mode)) count++;
        }
        return count;
    }

    public Map<String, Map<String, RagService.Result>> results() {
        Map<String, Map<String, RagService.Result>> copy = new LinkedHashMap<>();
        results.forEach((id, modes) -> copy.put(id, Map.copyOf(modes)));
        return Map.copyOf(copy);
    }

    private void save() throws IOException {
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        ObjectNode root = JsonSupport.MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("updatedAt", java.time.Instant.now().toString());
        ObjectNode rewriteRoot = root.putObject("rewrites");
        rewrites.forEach((id, rewrite) -> {
            ObjectNode node = rewriteRoot.putObject(id);
            node.put("query", RagQueryRewriter.redactSecrets(rewrite.query()));
            node.put("fallback", rewrite.rewriteFallback());
            node.put("rewriteMs", rewrite.rewriteMs());
        });
        ObjectNode resultRoot = root.putObject("results");
        results.forEach((id, modes) -> {
            ObjectNode question = resultRoot.putObject(id);
            modes.forEach((mode, result) -> writeResult(question.putObject(mode), result));
        });
        Path temporary = Files.createTempFile(absolute.getParent(),
                absolute.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(temporary, JsonSupport.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeResult(ObjectNode node, RagService.Result result) {
        node.put("answer", RagQueryRewriter.redactSecrets(result.answer()));
        node.put("retrieveMs", result.retrieveMs());
        node.put("llmMs", result.llmMs());
        node.put("status", result.status().name());
        node.put("error", RagQueryRewriter.redactSecrets(result.error()));
        node.put("filteredCount", result.filteredCount());
        node.put("filteredAll", result.filteredAll());
        node.put("rewriteFallback", result.rewriteFallback());
        node.put("rewriteQuery", RagQueryRewriter.redactSecrets(result.rewriteQuery()));
        node.put("rewriteMs", result.rewriteMs());
        writeChunks(node.putArray("chunks"), result.chunks());
        writeChunks(node.putArray("retrievedChunks"), result.retrievedChunks());
    }

    private static void writeChunks(ArrayNode array, List<RagRetriever.Chunk> chunks) {
        for (RagRetriever.Chunk chunk : chunks) {
            ObjectNode node = array.addObject();
            node.put("source", RagQueryRewriter.redactSecrets(chunk.source()));
            node.put("section", RagQueryRewriter.redactSecrets(chunk.section()));
            node.put("chunkId", chunk.chunkId());
            node.put("score", chunk.score());
            // Answers and metrics need only citation metadata; do not duplicate source text.
            node.put("text", "");
        }
    }

    private static RagService.Result readResult(JsonNode node) throws IOException {
        RagService.Status status;
        try {
            status = RagService.Status.valueOf(node.path("status").asText("ERROR"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Некорректный статус в eval checkpoint", invalid);
        }
        return new RagService.Result(node.path("answer").asText(""),
                readChunks(node.path("chunks")), readChunks(node.path("retrievedChunks")),
                node.path("retrieveMs").asLong(0), node.path("llmMs").asLong(0), status,
                nullableText(node.path("error")), node.path("filteredCount").asInt(0),
                node.path("filteredAll").asBoolean(false),
                node.path("rewriteFallback").asBoolean(false),
                node.path("rewriteQuery").asText(""), node.path("rewriteMs").asLong(0));
    }

    private static List<RagRetriever.Chunk> readChunks(JsonNode array) {
        java.util.ArrayList<RagRetriever.Chunk> chunks = new java.util.ArrayList<>();
        if (!array.isArray()) return List.of();
        for (JsonNode node : array) {
            chunks.add(new RagRetriever.Chunk(node.path("source").asText(""),
                    node.path("section").asText(""), node.path("chunkId").asText(""),
                    node.path("score").asDouble(0), node.path("text").asText("")));
        }
        return List.copyOf(chunks);
    }

    private static String nullableText(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
