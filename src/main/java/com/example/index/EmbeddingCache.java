package com.example.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Кэш эмбеддингов по sha256 текста чанка. Повторная сборка неизменённых
 * чанков не отправляет их на сервер (I5). Файл: <dir>/embed-cache.json,
 * запись атомарная (tmp → move).
 */
public final class EmbeddingCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, float[]> entries = new HashMap<>();
    private boolean dirty;

    public EmbeddingCache(Path file) {
        this.file = file;
        load();
    }

    /** Вектор по ключу-хэшу, если есть. */
    public float[] get(String sha256) {
        return entries.get(sha256);
    }

    /** Сохраняет вектор; запись не атомарна до save(). */
    public void put(String sha256, float[] vector) {
        entries.put(sha256, vector.clone());
        dirty = true;
    }

    /** Размер кэша (для диагностики). */
    public int size() {
        return entries.size();
    }

    /** Читает кэш, если файл есть; битый файл игнорируется. */
    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (var parser = MAPPER.getFactory().createParser(file.toFile())) {
            JsonNode root = MAPPER.readTree(parser);
            if (root != null && root.isObject()) {
                var fields = root.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    JsonNode vector = entry.getValue();
                    if (vector.isArray()) {
                        float[] value = new float[vector.size()];
                        for (int i = 0; i < vector.size(); i++) {
                            value[i] = (float) vector.get(i).asDouble();
                        }
                        entries.put(entry.getKey(), value);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Повреждённый кэш — начинаем с пустого.
        }
    }

    /** Атомарная запись кэша, если он менялся. */
    public void save() throws IOException {
        if (!dirty || file == null) {
            return;
        }
        Files.createDirectories(file.getParent());
        ObjectNode root = MAPPER.createObjectNode();
        for (Map.Entry<String, float[]> entry : entries.entrySet()) {
            try {
                root.putRawValue(entry.getKey(),
                        new com.fasterxml.jackson.databind.util.RawValue(
                                floatArrayJson(entry.getValue())));
            } catch (RuntimeException e) {
                throw new IOException("Не удалось сериализовать вектор кэша", e);
            }
        }
        writeAtomic(root);
        dirty = false;
    }

    private static final com.fasterxml.jackson.databind.util.RawValue RAW =
            new com.fasterxml.jackson.databind.util.RawValue("");

    /** Формат вектора в файл: массив чисел с 7 значимыми знаками после запятой. */
    private static String floatArrayJson(float[] vector) {
        StringBuilder json = new StringBuilder(vector.length * 12 + 2);
        json.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(java.math.BigDecimal.valueOf(vector[i]).setScale(7,
                    java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        }
        json.append(']');
        return json.toString();
    }

    private void writeAtomic(ObjectNode root) throws IOException {
        String json = MAPPER.writeValueAsString(root);
        Path temp = Files.createTempFile(file.getParent(),
                file.getFileName().toString() + ".", ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
