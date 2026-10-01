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

/** Separate resumable checkpoint for citation evaluation; answers stay in the log,
 * rejected quote texts are redacted and capped at 300 chars for the report table. */
public final class RagCitationEvalCheckpointStore {
    public record Row(String id, CitationValidator.AnswerStatus status, List<String> sources,
                      int citationCount, int confirmedCount, Double cosine,
                      int expectedFactsFound, int expectedFactsTotal, boolean idkCorrect,
                      double top1Score, long durationMs,
                      List<CitationValidator.RejectedQuote> rejectedCitations) {
        public Row {
            sources = List.copyOf(sources);
            rejectedCitations = List.copyOf(rejectedCitations);
        }
    }

    private final Path file;
    private final Map<String, Row> rows = new LinkedHashMap<>();

    public RagCitationEvalCheckpointStore(Path file) { this.file = file; }
    public Path file() { return file; }
    public boolean exists() { return Files.isRegularFile(file); }
    public Row row(String id) { return rows.get(id); }
    public List<Row> rows() { return List.copyOf(rows.values()); }

    public void load() throws IOException {
        rows.clear();
        if (!exists()) return;
        JsonNode root = JsonSupport.MAPPER.readTree(file.toFile());
        if (!"rag-citations".equals(root.path("kind").asText())
                || root.path("schemaVersion").asInt() != 1) {
            throw new IOException("Чекпойнт не является RAG citation eval checkpoint; файл оставлен без изменений");
        }
        JsonNode node = root.path("results");
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            CitationValidator.AnswerStatus status;
            try { status = CitationValidator.AnswerStatus.valueOf(value.path("status").asText()); }
            catch (IllegalArgumentException invalid) {
                throw new IOException("Некорректный статус в citation checkpoint", invalid);
            }
            List<String> sources = new java.util.ArrayList<>();
            if (value.path("sources").isArray()) value.path("sources").forEach(item -> sources.add(item.asText()));
            List<CitationValidator.RejectedQuote> rejected = new java.util.ArrayList<>();
            if (value.path("rejectedCitations").isArray()) {
                value.path("rejectedCitations").forEach(item -> rejected.add(
                        new CitationValidator.RejectedQuote(item.path("number").asInt(-1),
                                item.path("text").asText(""), item.path("reason").asText("unknown"))));
            }
            rows.put(entry.getKey(), new Row(entry.getKey(), status, sources,
                    value.path("citationCount").asInt(), value.path("confirmedCount").asInt(),
                    value.path("cosine").isNumber() ? value.path("cosine").asDouble() : null,
                    value.path("expectedFactsFound").asInt(), value.path("expectedFactsTotal").asInt(),
                    value.path("idkCorrect").asBoolean(), value.path("top1Score").asDouble(),
                    value.path("durationMs").asLong(), rejected));
        }
    }

    public void put(Row row) throws IOException {
        rows.put(row.id(), row);
        save();
    }

    private void save() throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        ObjectNode root = JsonSupport.MAPPER.createObjectNode();
        root.put("kind", "rag-citations");
        root.put("schemaVersion", 1);
        root.put("updatedAt", java.time.Instant.now().toString());
        ObjectNode results = root.putObject("results");
        rows.forEach((id, row) -> {
            ObjectNode node = results.putObject(id);
            node.put("status", row.status().name());
            ArrayNode sources = node.putArray("sources");
            row.sources().forEach(source -> sources.add(RagQueryRewriter.redactSecrets(source)));
            node.put("citationCount", row.citationCount());
            node.put("confirmedCount", row.confirmedCount());
            if (row.cosine() == null || !Double.isFinite(row.cosine())) node.putNull("cosine");
            else node.put("cosine", row.cosine());
            node.put("expectedFactsFound", row.expectedFactsFound());
            node.put("expectedFactsTotal", row.expectedFactsTotal());
            node.put("idkCorrect", row.idkCorrect());
            node.put("top1Score", row.top1Score());
            node.put("durationMs", row.durationMs());
            ArrayNode rejected = node.putArray("rejectedCitations");
            row.rejectedCitations().forEach(citation -> {
                ObjectNode item = rejected.addObject();
                item.put("number", citation.number());
                item.put("text", RagQueryRewriter.redactSecrets(citation.text()));
                item.put("reason", citation.reason());
            });
        });
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".", ".tmp");
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
}
