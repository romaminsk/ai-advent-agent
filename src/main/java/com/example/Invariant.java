package com.example;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * в выводе (architecture|stack|decision|business|other); forbiddenMarkers —
 * необязательные запрещённые слова/фразы для детерминированной проверки
 * на вводе (пустые — только блок «ИНВАРИАНТЫ» и встроенный словарь
 * {@link InvariantGuard}); createdAt — момент создания. Категория и маркеры
 * нормализуются к нижнему регистру (Locale.ROOT).
 */
public record Invariant(String id, String text, String category,
                        List<String> forbiddenMarkers, Instant createdAt) {

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
        // Маркеры нормализуются и дедуплицируются; пустой список — как было.
        List<String> normalizedMarkers = new ArrayList<>();
        if (forbiddenMarkers != null) {
            for (String marker : forbiddenMarkers) {
                String normalized = marker == null
                        ? "" : marker.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()
                        && !normalizedMarkers.contains(normalized)) {
                    normalizedMarkers.add(normalized);
                }
            }
        }
        forbiddenMarkers = List.copyOf(normalizedMarkers);
    }

    /** Совместимый конструктор без маркеров (старый формат и простые вызовы). */
    public Invariant(String id, String text, String category, Instant createdAt) {
        this(id, text, category, List.of(), createdAt);
    }

    /** Новый инвариант с сгенерированным идентификатором и временем создания. */
    public static Invariant create(String text, String category, Instant now) {
        return create(text, category, List.of(), now);
    }

    /** Новый инвариант с маркерами, идентификатором и временем создания. */
    public static Invariant create(String text, String category,
                                   List<String> forbiddenMarkers, Instant now) {
        return new Invariant(newId(), text, category, forbiddenMarkers, now);
    }

    /** true, если у инварианта заданы явные запрещённые маркеры. */
    public boolean hasForbiddenMarkers() {
        return !forbiddenMarkers.isEmpty();
    }

    /** Короткий идентификатор: 8 hex-символов UUID (читабельно в /invariant remove). */
    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
