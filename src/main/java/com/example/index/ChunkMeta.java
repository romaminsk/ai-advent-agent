package com.example.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Метаданные одного чанка индекса. */
public record ChunkMeta(
        String chunkId,
        String source,
        String title,
        String section,
        String strategy,
        int startChar,
        int endChar,
        int chars) {

    /** Аккумулятор чанков одной стратегии одной сборки. */
    public static final class Builder {
        private final List<Chunk> chunks = new ArrayList<>();
        private final String strategy;

        public Builder(String strategy) {
            this.strategy = strategy;
        }

        /** Добавляет чанк: chunk_id = sha1(source + strategy + порядковый номер). */
        public void add(String source, String title, String section,
                        String text, int startChar, int endChar) {
            if (text == null || text.isBlank()) {
                return;
            }
            int index = chunks.size();
            String chunkId = sha1Hex(source + strategy + index);
            if (section == null || section.isBlank()) {
                section = title;
            }
            chunks.add(new Chunk(new ChunkMeta(chunkId, source, title,
                    section, strategy, startChar, endChar, text.length()), text));
        }

        /** Добавляет кусок документа как чанк. */
        public void add(String source, String title, String section, Piece piece) {
            add(source, title, section, piece.text(), piece.startChar(), piece.endChar());
        }

        public List<Chunk> build() {
            return List.copyOf(chunks);
        }
    }

    /** Чанк: метаданные + текст. Вектор добавляет эмбеддер на сборке индекса. */
    public record Chunk(ChunkMeta meta, String text) {
    }

    /** sha1 в hex (стабильный идентификатор чанка между сборками). */
    public static String sha1Hex(String value) {
        return digestHex("SHA-1", value.getBytes(StandardCharsets.UTF_8));
    }

    /** sha256 в hex (ключ кэша эмбеддингов). */
    public static String sha256Hex(byte[] bytes) {
        return digestHex("SHA-256", bytes);
    }

    private static String digestHex(String algorithm, byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " недоступен", e);
        }
    }
}
