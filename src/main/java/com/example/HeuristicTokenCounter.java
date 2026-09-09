package com.example;

/**
 * Локальная эвристическая оценка числа токенов. Точный токенизатор выбранной
 * модели не подключён, поэтому результат всегда помечается как оценка (≈)
 * и никогда не называется точным подсчётом.
 *
 * Алгоритм (упрощённая модель, НЕ токенизатор модели):
 * - CJK-иероглифы (китайский, японская кана, корейский) — ≈1 токен на символ;
 * - emoji и символы дополнительных плоскостей — ≈2 токена на символ
 *   (вариационный селектор U+FE0F и ZWJ U+200D сами по себе не считаются);
 * - кириллица — ≈1 токен на 3 символа;
 * - латиница — ≈1 токен на 4 символа;
 * - цифры — ≈1 токен на 3 символа;
 * - серия пробельных символов — ≈1 токен на 8 символов;
 * - прочие знаки (пунктуация и т.п.) — ≈1 токен на 2 символа.
 *
 * Ограничения:
 * - реальные токенизаторы работают по BPE-словарю конкретной модели и учитывают
 *   границы слов; эвристика даёт грубую оценку с заметной погрешностью,
 *   для кода она обычно занижает результат (код токенизируется хуже текста);
 * - фиксированное число символов НЕ равно одному токену: это лишь средняя
 *   пропорция эвристики, а не утверждение о словаре модели;
 * - оценка не зависит от модели и не претендует на точность подсчёта.
 */
public final class HeuristicTokenCounter implements TokenCounter {

    public static final HeuristicTokenCounter INSTANCE = new HeuristicTokenCounter();

    private static final int KIND_ZERO = 0;
    private static final int KIND_WS = 1;
    private static final int KIND_CJK = 2;
    private static final int KIND_EMOJI = 3;
    private static final int KIND_CYRILLIC = 4;
    private static final int KIND_LATIN = 5;
    private static final int KIND_DIGIT = 6;
    private static final int KIND_OTHER = 7;

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        int runKind = KIND_OTHER;
        int runLength = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            int kind = kindOf(codePoint);
            if (kind == KIND_ZERO) {
                // Вариационный селектор и ZWJ: сами по себе токенов не добавляют.
                continue;
            }
            if (kind == runKind) {
                runLength++;
                continue;
            }
            total += tokensForRun(runKind, runLength);
            runKind = kind;
            runLength = 1;
        }
        total += tokensForRun(runKind, runLength);
        return total;
    }

    @Override
    public boolean exact() {
        return false;
    }

    @Override
    public String description() {
        return "локальная эвристика — оценка (≈), подтверждённый токенизатор модели не подключён";
    }

    /** Класс символа для эвристики; определяет пропорцию «символы → токены». */
    private static int kindOf(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return KIND_WS;
        }
        if (isCjk(codePoint)) {
            return KIND_CJK;
        }
        if (isEmojiOrSymbol(codePoint)) {
            return KIND_EMOJI;
        }
        if (codePoint >= 0x0400 && codePoint <= 0x04FF) {
            return KIND_CYRILLIC;
        }
        if (isLatin(codePoint)) {
            return KIND_LATIN;
        }
        if (codePoint >= 0x0030 && codePoint <= 0x0039) {
            return KIND_DIGIT;
        }
        return KIND_OTHER;
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)       // идеография CJK
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)   // расширение A
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)   // хирагана и катакана
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)   // хангыль
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF);  // совместимость CJK
    }

    private static boolean isEmojiOrSymbol(int codePoint) {
        return codePoint >= 0x1F000
                || (codePoint >= 0x2600 && codePoint <= 0x27BF);  // символы и дингбаты
    }

    private static boolean isLatin(int codePoint) {
        return (codePoint >= 0x0041 && codePoint <= 0x005A)
                || (codePoint >= 0x0061 && codePoint <= 0x007A)
                || (codePoint >= 0x00C0 && codePoint <= 0x024F);  // латиница-1 и расширения
    }

    /** Оценка токенов для серии символов одного класса. */
    private static int tokensForRun(int kind, int length) {
        if (length <= 0) {
            return 0;
        }
        return switch (kind) {
            case KIND_CJK -> length;
            case KIND_EMOJI -> 2 * length;
            case KIND_CYRILLIC, KIND_DIGIT -> ceilDiv(length, 3);
            case KIND_LATIN -> ceilDiv(length, 4);
            case KIND_WS -> ceilDiv(length, 8);
            default -> ceilDiv(length, 2);
        };
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
