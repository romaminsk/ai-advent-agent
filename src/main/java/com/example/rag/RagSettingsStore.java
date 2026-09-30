package com.example.rag;

import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Atomic persistence for both the RAG mode and its settings. */
public final class RagSettingsStore {
    public record State(boolean enabled, RagSettings settings, String indexStrategy) {
        public State {
            if (settings == null || (!"fixed".equals(indexStrategy)
                    && !"structure".equals(indexStrategy))) {
                throw new IllegalArgumentException("Некорректные настройки RAG");
            }
        }

        public State(boolean enabled, RagSettings settings) {
            this(enabled, settings, RagConstants.INDEX_STRATEGY);
        }
    }

    private final Path file;

    public RagSettingsStore(Path file) {
        this.file = file;
    }

    public static RagSettingsStore defaultStore() {
        return new RagSettingsStore(Path.of(System.getProperty("user.home"),
                ".ai-advent-agent", "rag-settings.json"));
    }

    public Path file() {
        return file;
    }

    public State load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new State(false, RagSettings.DEFAULT);
        }
        JsonNode root = JsonSupport.MAPPER.readTree(file.toFile());
        JsonNode node = root.path("settings");
        RagSettings settings = new RagSettings(node.path("topKBefore").asInt(20),
                node.path("topKAfter").asInt(5), node.path("minScore").asDouble(
                RagSettings.DEFAULT_MIN_SCORE), node.path("rerankEnabled").asBoolean(true),
                node.path("rewriteEnabled").asBoolean(true),
                node.path("rerankVectorWeight").asDouble(RagSettings.DEFAULT_VECTOR_WEIGHT),
                node.path("rerankLexicalWeight").asDouble(RagSettings.DEFAULT_LEXICAL_WEIGHT),
                node.hasNonNull("relativeDelta") ? node.path("relativeDelta").asDouble() : null,
                node.path("diversityEnabled").asBoolean(RagSettings.DEFAULT_DIVERSITY_ENABLED));
        String strategy = root.path("indexStrategy").asText(RagConstants.INDEX_STRATEGY);
        if (!strategy.equals("fixed") && !strategy.equals("structure")) {
            throw new IOException("Неизвестная стратегия RAG-индекса: " + strategy);
        }
        return new State(root.path("enabled").asBoolean(false), settings, strategy);
    }

    public void save(State state) throws IOException {
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        ObjectNode root = JsonSupport.MAPPER.createObjectNode();
        root.put("enabled", state.enabled());
        root.put("indexStrategy", state.indexStrategy());
        ObjectNode settings = root.putObject("settings");
        settings.put("topKBefore", state.settings().topKBefore());
        settings.put("topKAfter", state.settings().topKAfter());
        settings.put("minScore", state.settings().minScore());
        settings.put("rerankEnabled", state.settings().rerankEnabled());
        settings.put("rewriteEnabled", state.settings().rewriteEnabled());
        settings.put("rerankVectorWeight", state.settings().rerankVectorWeight());
        settings.put("rerankLexicalWeight", state.settings().rerankLexicalWeight());
        if (state.settings().relativeDelta() == null) settings.putNull("relativeDelta");
        else settings.put("relativeDelta", state.settings().relativeDelta());
        settings.put("diversityEnabled", state.settings().diversityEnabled());
        Path temporary = Files.createTempFile(absolute.getParent(), "rag-settings.", ".tmp");
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
