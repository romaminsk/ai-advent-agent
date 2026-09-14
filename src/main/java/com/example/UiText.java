package com.example;

import java.util.Locale;

/**
 * Семантический слой вывода UI: маркеры состояний, ширина строки,
 * усечение по видимой ширине и шаблоны служебных сообщений.
 *
 * Используется обоими терминалами (InteractiveTerminalUi и PlainTerminalUi)
 * и диспетчером команд (Main), чтобы текстовые роли не зависели от
 * цветов и не разносились ANSI-фрагментами по бизнес-логике.
 *
 * Цвет никогда не является единственным носителем смысла:
 * каждый маркер различим и в чистом тексте (✓/!/×/?/…).
 */
public final class UiText {

    /** Маркеры состояний; обязательно ASCII-safe fallback обезвреживанием в LLM_ASCII. */
    public static final String OK = "✓";
    public static final String WARN = "!";
    public static final String FAIL = "×";
    public static final String ASK = "?";
    public static final String WAIT = "…";

    private UiText() {
    }

    /**
     * Предупреждение: единый маркер «!» перед текстом (без дублирования).
     */
    public static String warn(String text) {
        return text == null ? WARN : withMarker(WARN, text);
    }

    /** Успех: единственный маркер «✓» и короткий факт. */
    public static String success(String fact) {
        return withMarker(OK, fact);
    }

    /** Текст ошибки без префикса: «×» добавляет терминал (интерактивный — красным). */
    public static String failure(String fact) {
        return fact == null ? "" : fact.strip();
    }

    /** Запрос подтверждения: «? <вопрос> [y/N]». */
    public static String ask(String question) {
        return withMarker(ASK, question.strip() + " [y/N]");
    }

    /** Ожидание/длительная операция: «… <какой этап, если известен>» либо просто «…». */
    public static String waiting(String knownStep) {
        if (knownStep == null || knownStep.isBlank()) {
            return WAIT;
        }
        return withMarker(WAIT, knownStep);
    }

    /** Строит сообщение: «<маркер> <текст>» в одну строку. */
    private static String withMarker(String marker, String text) {
        String clean = text.strip();
        if (clean.startsWith(marker)) {
            return clean;
        }
        return marker + " " + clean;
    }

    /** Подсказка следующего шага под сообщением: «  Попробуйте /xxx». */
    public static String hint(String command) {
        return "  Попробуйте /" + command.replaceFirst("^/", "").trim();
    }

    /**
     * Видимая ширина строки в терминале: иероглифы CJK считаются как 2,
     * остальные — 1. Управляющие последовательности ANSI не входят:
     * во внешний контекст они попадают только после усечения.
     */
    public static int visibleWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            width += codePointWidth(codePoint);
        }
        return width;
    }

    private static int codePointWidth(int cp) {
        // Широкие блоки: CJK-иероглифы, полные формы и хангыль (упрощённо, основные диапазоны).
        if ((cp >= 0x1100 && cp <= 0x115F)          // хангул чамо
                || (cp >= 0x2E80 && cp <= 0x303E)   // CJK радикалы, символы
                || (cp >= 0x3041 && cp <= 0x33FF)   // хирагана, катакана, CJK совместимость
                || (cp >= 0x3400 && cp <= 0x4DBF)   // расширение A
                || (cp >= 0x4E00 && cp <= 0x9FFF)   // CJK единые иероглифы
                || (cp >= 0xF900 && cp <= 0xFAFF)   // CJK совместимость
                || (cp >= 0xFF00 && cp <= 0xFF60)   // полные формы
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1F64F)) { // эмодзи-блок (большинство двойные)
            return 2;
        }
        return 1;
    }

    /**
     * Усечение по видимой ширине с многоточием. Многоточие входит в лимит:
     * результат никогда шире maxWidth. ASCII-окружение заменит маркер
     * заранее через asciify, поэтому здесь только unicode-вариант.
     */
    public static String truncate(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) {
            return "";
        }
        int total = visibleWidth(text);
        if (total <= maxWidth) {
            return text;
        }
        int budget = maxWidth - 1; // место под «…»
        StringBuilder out = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int w = codePointWidth(codePoint);
            if (width + w > budget) {
                break;
            }
            out.appendCodePoint(codePoint);
            width += w;
            i += Character.charCount(codePoint);
        }
        out.append('…');
        return out.toString();
    }

    /** Обезвреживание служебных символов: произвольные глифы заменяются обычными. */

    public static String asciify(String text, boolean ascii) {
        if (text == null) {
            return "";
        }
        if (!ascii) {
            return text;
        }
        return text
                .replace("✓", "[ok]")
                .replace("×", "x")
                .replace("◆", "*")
                .replace("…", "...")
                .replace("›", ">")
                .replace("»", "\"");
    }

    /** Имя ключа для подсказок в нижнем регистре по Locale.ROOT. */
    public static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
