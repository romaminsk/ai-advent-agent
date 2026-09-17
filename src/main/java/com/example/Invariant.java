package com.example;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Инвариант — жёсткое ограничение, которое агент не имеет права нарушать:
 * выбранная архитектура, принятые технические решения, ограничения по стеку,
 * бизнес-правила. Инвариант — не предпочтение и не факт памяти: агент обязан
 * учитывать его в рассуждениях и отказываться от решений, которые его
 * нарушают, объясняя отказ.
 *
 * Неизменяемый record. Хранится в отдельном файле инвариантов
 * ({@link InvariantStore}); в рабочую память ({@link WorkingMemory})
 * и в историю диалога инварианты не попадают.
 *
 * Поля: id — уникальный короткий идентификатор (8 hex-символов, для удаления);
 * text — текст инварианта; category — необязательная категория для группировки
 * в выводе (architecture|stack|decision|business|other); createdAt — момент
 * создания. Категория нормализуется к нижнему регистру (Locale.ROOT),
 * пустая — null.
 */
public record Invariant(String id, String text, String category, Instant createdAt) {

    public Invariant {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("идентификатор инварианта обязателен");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("текст инварианта обязателен");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("метка времени инварианта обязательна");
        }
        id = id.trim();
        text = text.trim();
        category = category == null || category.isBlank()
                ? null : category.trim().toLowerCase(Locale.ROOT);
    }

    /** Новый инвариант с сгенерированным идентификатором и временем создания. */
    public static Invariant create(String text, String category, Instant now) {
        return new Invariant(newId(), text, category, now);
    }

    /** Короткий идентификатор: 8 hex-символов UUID (читабельно в /invariant remove). */
    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
