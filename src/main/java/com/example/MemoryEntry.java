package com.example;

import java.time.Instant;

/**
 * Одна запись долговременной памяти: «ключ — значение» с метаданными
 * (когда добавлено, когда изменено). Сериализуется в JSON файла памяти;
 * пустые ключ и значение недопустимы.
 */
public record MemoryEntry(String key, String value, String createdAt, String updatedAt) {

    public MemoryEntry {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ключ записи памяти обязателен");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("значение записи памяти обязательно");
        }
        if (createdAt == null || createdAt.isBlank() || updatedAt == null || updatedAt.isBlank()) {
            throw new IllegalArgumentException("метки времени записи памяти обязательны");
        }
    }

    /** Новая запись с заданным временем создания; updatedAt = createdAt. */
    public static MemoryEntry create(String key, String value, Instant now) {
        String timestamp = now.toString();
        return new MemoryEntry(key.trim(), value.trim(), timestamp, timestamp);
    }

    /** Значение с обновлением метки изменения; createdAt сохраняется. */
    public MemoryEntry withValue(String newValue, Instant now) {
        return new MemoryEntry(key, newValue.trim(), createdAt, now.toString());
    }

    /**
     * Запись без явного ключа (старый формат): ключом стал весь текст
     * и значение совпадает с ним. Такие записи читаются и показываются
     * как есть; «ключ: значение» для них не дублируется.
     */
    public boolean legacyWithoutKey() {
        return key.equals(value);
    }

    /** Строка отображения «ключ: значение»; у записи без явного ключа — текст как есть. */
    public String renderLine() {
        return legacyWithoutKey() ? value : key + ": " + value;
    }
}
