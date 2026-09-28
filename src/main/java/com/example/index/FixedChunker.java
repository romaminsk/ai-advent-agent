package com.example.index;

import java.util.ArrayList;
import java.util.List;

/**
 * Стратегия fixed: окно 800 символов, перекрытие 100, разрез
 * по ближайшему пробелу/переводу строки внутри окна.
 */
public final class FixedChunker {

    public static final int CHUNK_SIZE = 800;
    public static final int OVERLAP = 100;

    private FixedChunker() {
    }

    /** Разбивает текст на чанки фиксированного размера с перекрытием. */
    public static List<Piece> chunk(String text) {
        List<Piece> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        int length = text.length();
        int start = 0;
        while (start < length) {
            int plannedEnd = Math.min(start + CHUNK_SIZE, length);
            int end = plannedEnd;
            if (plannedEnd < length) {
                int boundary = findBoundary(text, start, plannedEnd);
                if (boundary > start) {
                    end = boundary;
                }
            }
            result.add(new Piece(text.substring(start, end), start, end));
            if (end >= length) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP);
        }
        return result;
    }

    /**
     * Ближайшая к концу окна граница (пробел, перевод строки, табуляция).
     * Границей считается позиция сразу ПОСЛЕ разделителя. Поиск идёт
     * от конца окна назад, но не глубже величины перекрытия.
     */
    static int findBoundary(String text, int start, int end) {
        int earliest = Math.max(start + 1, end - OVERLAP);
        for (int candidate = end - 1; candidate >= earliest; candidate--) {
            char c = text.charAt(candidate);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                int after = candidate + 1;
                if (after > start && after <= end) {
                    return after;
                }
            }
        }
        return -1;
    }
}
