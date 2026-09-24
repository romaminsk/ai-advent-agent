package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeSet;

/**
 * Общая канонизация JSON для SHA-256 целостности по всей цепочке
 * search → summarize → saveToFile: фиксированный порядок полей
 * (рекурсивная сортировка ключей), без пробелов, UTF-8.
 * Одинаково используется сервером и клиентом-оркестратором.
 */
public final class PipelineCanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PipelineCanonicalJson() {
    }

    /** Канонический JSON объекта с отсортированными ключами на любом уровне. */
    public static String canonical(Object value) {
        try {
            return MAPPER.writeValueAsString(sort(MAPPER.valueToTree(value)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось канонизировать JSON.", e);
        }
    }

    /** SHA-256 строковых байтов UTF-8 в hex (нижний регистр). */
    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 байтов в hex (нижний регистр). */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(digest.getDigestLength() * 2);
            for (byte b : digest.digest(bytes)) {
                hex(hex, b);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен.", e);
        }
    }

    private static void hex(StringBuilder out, byte b) {
        char high = Character.forDigit((b >> 4) & 0xF, 16);
        char low = Character.forDigit(b & 0xF, 16);
        out.append(high).append(low);
    }

    /** Рекурсивная перестройка узла: поля объектов отсортированы, массивы не тронуты. */
    private static JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = new ObjectNode(MAPPER.getNodeFactory());
            TreeSet<String> fields = new TreeSet<>();
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                fields.add(entry.getKey());
            }
            for (String field : fields) {
                sorted.set(field, sort(object.get(field)));
            }
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = new ArrayNode(MAPPER.getNodeFactory());
            for (JsonNode item : array) copy.add(sort(item));
            return copy;
        }
        return node;
    }
}
