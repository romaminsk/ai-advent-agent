package com.example.index;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Хранилище JSON-индекса: {model, dim, createdAt, corpusRoot, buildMs,
 * apiRequests, coldRequests, chunks:[{chunkId, source, title, section, strategy,
 * startChar, endChar, chars, text, vector}]}. Запись атомарная
 * (tmp → move). Файл: <dir>/<strategy>.json.
 */
public final class IndexStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory FACTORY = MAPPER.getFactory();

    private final Path dir;

    public IndexStore(Path dir) {
        this.dir = dir;
    }

    /** Готовый индекс в памяти. */
    public record Index(String strategy, String model, int dim, Instant createdAt,
                        String corpusRoot, long buildMs, int apiRequests, int coldRequests,
                        List<IndexedChunk> chunks) {
    }

    /** Чанк индекса: метаданные + текст + вектор. */
    public record IndexedChunk(ChunkMeta meta, String text, float[] vector) {
    }

    /** Каталог индексов. */
    public Path directory() {
        return dir;
    }

    /** Файл индекса стратегии. */
    public Path file(String strategy) {
        return dir.resolve(strategy + ".json");
    }

    /** Есть ли индекс стратегии. */
    public boolean exists(String strategy) {
        return Files.isRegularFile(file(strategy));
    }

    /** Атомарная запись индекса (tmp → move). */
    public void save(Index index) throws IOException {
        Files.createDirectories(dir);
        Path target = file(index.strategy());
        Path temp = Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
        try (JsonGenerator generator = FACTORY.createGenerator(temp.toFile(), JsonEncoding.UTF8)) {
            generator.writeStartObject();
            generator.writeStringField("model", index.model());
            generator.writeNumberField("dim", index.dim());
            generator.writeStringField("createdAt", index.createdAt().toString());
            generator.writeStringField("corpusRoot", index.corpusRoot());
            generator.writeNumberField("buildMs", index.buildMs());
            generator.writeNumberField("apiRequests", index.apiRequests());
            generator.writeNumberField("coldRequests", index.coldRequests());
            generator.writeFieldName("chunks");
            generator.writeStartArray();
            for (IndexedChunk chunk : index.chunks()) {
                generator.writeStartObject();
                generator.writeStringField("chunkId", chunk.meta().chunkId());
                generator.writeStringField("source", chunk.meta().source());
                generator.writeStringField("title", chunk.meta().title());
                generator.writeStringField("section", chunk.meta().section());
                generator.writeStringField("strategy", chunk.meta().strategy());
                generator.writeNumberField("startChar", chunk.meta().startChar());
                generator.writeNumberField("endChar", chunk.meta().endChar());
                generator.writeNumberField("chars", chunk.meta().chars());
                generator.writeStringField("text", chunk.text());
                if (chunk.vector() != null) {
                    generator.writeArrayFieldStart("vector");
                    for (float value : chunk.vector()) {
                        generator.writeNumber(round(value));
                    }
                    generator.writeEndArray();
                }
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Читает индекс стратегии; null, если файла нет/он повреждён. */
    public Index load(String strategy) throws IOException {
        Path target = file(strategy);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try (JsonParser parser = FACTORY.createParser(target.toFile())) {
            String model = null;
            int dim = 0;
            Instant createdAt = Instant.EPOCH;
            String corpusRoot = "";
            long buildMs = 0;
            int apiRequests = 0;
            int coldRequests = 0;
            List<IndexedChunk> chunks = new ArrayList<>();
            if (!parser.hasCurrentToken()) {
                parser.nextToken();
            }
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "model" -> model = parser.getValueAsString();
                    case "dim" -> dim = parser.getIntValue();
                    case "createdAt" -> createdAt = Instant.parse(parser.getValueAsString());
                    case "corpusRoot" -> corpusRoot = parser.getValueAsString();
                    case "buildMs" -> buildMs = parser.getLongValue();
                    case "apiRequests" -> apiRequests = parser.getIntValue();
                    case "coldRequests" -> coldRequests = parser.getIntValue();
                    case "chunks" -> { if (value == JsonToken.START_ARRAY) readChunks(parser, chunks); }
                    default -> parser.skipChildren();
                }
            }
            if (model == null) {
                return null;
            }
            // Старые индексы без coldRequests читаются по числу API-запросов
            // последней сборки; для кэшевых legacy-индексов значение 0 означает unknown.
            if (coldRequests == 0) {
                coldRequests = apiRequests;
            }
            return new Index(strategy, model, dim, createdAt, corpusRoot, buildMs,
                    apiRequests, coldRequests, List.copyOf(chunks));
        }
    }

    private static void readChunks(JsonParser parser, List<IndexedChunk> chunks)
            throws IOException {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            String chunkId = null;
            String source = null;
            String title = "";
            String section = "";
            String strategy = null;
            int startChar = 0;
            int endChar = 0;
            int chars = 0;
            String text = "";
            float[] vector = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "chunkId" -> chunkId = parser.getValueAsString();
                    case "source" -> source = parser.getValueAsString();
                    case "title" -> title = parser.getValueAsString();
                    case "section" -> section = parser.getValueAsString();
                    case "strategy" -> strategy = parser.getValueAsString();
                    case "startChar" -> startChar = parser.getIntValue();
                    case "endChar" -> endChar = parser.getIntValue();
                    case "chars" -> chars = parser.getIntValue();
                    case "text" -> text = parser.getValueAsString();
                    case "vector" -> { if (parser.currentToken() == JsonToken.START_ARRAY) vector = readVector(parser); }
                    default -> parser.skipChildren();
                }
            }
            if (chunkId == null || source == null || strategy == null) {
                throw new IOException("Индекс: чанк без обязательных метаданных");
            }
            chunks.add(new IndexedChunk(new ChunkMeta(chunkId, source, title, section,
                    strategy, startChar, endChar, chars), text, vector));
        }
    }

    private static float[] readVector(JsonParser parser) throws IOException {
        List<Float> values = new ArrayList<>();
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return new float[0];
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            values.add(parser.getFloatValue());
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }

    /** Округление float до 7 знаков — читаемый и компактный JSON. */
    private static double round(float value) {
        if (!Float.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1e7) / 1e7;
    }
}
