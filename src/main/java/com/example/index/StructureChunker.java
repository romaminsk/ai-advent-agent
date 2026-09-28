package com.example.index;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Стратегия structure: md — по заголовкам #/##/###, java — по классам
 * и методам. Раздел больше 1500 дорезается фиксированным окном,
 * куски меньше 200 склеиваются с соседом.
 */
public final class StructureChunker {

    public static final int MAX_SECTION = 1500;
    public static final int MIN_MERGE = 200;

    private static final Pattern CLASS_PATTERN =
            Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern METHOD_HEAD_PATTERN =
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\([^()]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*\\{");

    /** Кусок с именем секции. */
    public record SectionPiece(String text, int startChar, int endChar, String section) {
        Piece piece() {
            return new Piece(text, startChar, endChar);
        }
    }

    private StructureChunker() {
    }

    /** Разбивает документ по структуре, возвращает финальные куски с секциями. */
    public static List<SectionPiece> chunk(String text, DocumentLoader.DocType type) {
        String safeText = text == null ? "" : text;
        List<SectionPiece> sections = switch (type) {
            case MD -> mdSections(safeText);
            case JAVA -> javaSections(safeText);
            case TXT -> paragraphSections(safeText);
        };
        return finalize(sections, safeText);
    }

    /** Разрезает слишком большие куски и склеивает слишком мелкие. */
    static List<SectionPiece> finalize(List<SectionPiece> sections, String text) {
        // Разрезка до склейки даёт куски ≤ 800; после склейки мелких
        // цепочкой снова подрезаем превышающие 1500.
        return splitOversized(mergeSmall(splitOversized(sections, text), text), text);
    }

    /** Оборачивает куски fixed стратегии в куски с секцией (из заголовка). */
    static List<SectionPiece> unwrap(List<Piece> pieces, String section) {
        List<SectionPiece> result = new ArrayList<>();
        for (Piece piece : pieces) {
            result.add(new SectionPiece(piece.text(), piece.startChar(),
                    piece.endChar(), section));
        }
        return result;
    }

    private static List<SectionPiece> splitOversized(List<SectionPiece> sections, String text) {
        List<SectionPiece> result = new ArrayList<>();
        for (SectionPiece section : sections) {
            if (section.text().length() <= MAX_SECTION) {
                result.add(section);
                continue;
            }
            int start = section.startChar();
            int sectionEnd = section.endChar();
            while (start < sectionEnd) {
                int plannedEnd = Math.min(start + FixedChunker.CHUNK_SIZE, sectionEnd);
                int end = plannedEnd;
                if (plannedEnd < sectionEnd) {
                    int boundary = FixedChunker.findBoundary(text, start, plannedEnd);
                    if (boundary > start) {
                        end = boundary;
                    }
                }
                int finalEnd = end;
                result.add(new SectionPiece(text.substring(start, finalEnd),
                        start, finalEnd, section.section()));
                if (end >= sectionEnd) {
                    break;
                }
                start = Math.max(start + 1, end - FixedChunker.OVERLAP);
            }
        }
        return result;
    }

    /** Склейка мелких кусков (< 200) с левым соседом; одиночный мелкий остаётся. */
    static List<SectionPiece> mergeSmall(List<SectionPiece> pieces, String text) {
        List<SectionPiece> work = new ArrayList<>(pieces);
        // Сначала переклеиваем мелкие с левым соседом, кроме первого.
        List<SectionPiece> merged = new ArrayList<>();
        for (SectionPiece piece : work) {
            boolean mergeable = !merged.isEmpty()
                    && (merged.get(merged.size() - 1).text().length() < MIN_MERGE
                    || piece.text().length() < MIN_MERGE);
            if (mergeable) {
                SectionPiece left = merged.remove(merged.size() - 1);
                int start = left.startChar();
                int end = piece.endChar();
                // Объединяем исходный текст целиком, включая промежуток между кусками.
                String section = left.section() == null ? piece.section() : left.section();
                merged.add(new SectionPiece(text.substring(start, end), start, end, section));
            } else {
                merged.add(piece);
            }
        }
        return merged;
    }

    /** md: каждый заголовок #/##/### открывает новый раздел. */
    static List<SectionPiece> mdSections(String text) {
        List<SectionPiece> result = new ArrayList<>();
        String currentSection = "(начало)";
        int pieceStart = 0;
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean end = i == text.length() || text.charAt(i) == '\n';
            if (!end) {
                continue;
            }
            int level = headingLevelAt(text, lineStart, i);
            if (level > 0) {
                String heading = headingText(text, lineStart, i);
                Piece piece = trimPiece(text, pieceStart, lineStart);
                if (!piece.text().isBlank()) {
                    result.add(new SectionPiece(piece.text(), piece.startChar(),
                            piece.endChar(), currentSection));
                }
                currentSection = heading;
                pieceStart = lineStart;
            }
            lineStart = i + 1;
        }
        Piece tail = trimPiece(text, pieceStart, text.length());
        if (!tail.text().isBlank()) {
            result.add(new SectionPiece(tail.text(), tail.startChar(), tail.endChar(), currentSection));
        }
        return result;
    }

    private static Piece trimPiece(String text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        String value = start < end ? text.substring(start, end) : "";
        return new Piece(value, start, end);
    }

    private static String headingText(String text, int lineStart, int lineEnd) {
        int i = lineStart;
        while (i < lineEnd && text.charAt(i) == '#') {
            i++;
        }
        while (i < lineEnd && text.charAt(i) == ' ') {
            i++;
        }
        return text.substring(i, lineEnd).trim();
    }

    /** Уровень заголовка markdown (#, ##, ### — не #### или глубже). */
    private static int headingLevelAt(String text, int lineStart, int lineEnd) {
        int i = lineStart;
        int level = 0;
        while (i < lineEnd && text.charAt(i) == '#') {
            level++;
            i++;
        }
        if (level < 1 || level > 3) {
            return 0;
        }
        return i < lineEnd && text.charAt(i) == ' ' ? level : 0;
    }

    /** java: преамбула плюс куски по классам и методам вида «Класс#метод». */
    static List<SectionPiece> javaSections(String text) {
        List<SectionPiece> result = new ArrayList<>();
        Matcher matcher = CLASS_PATTERN.matcher(text);
        if (!matcher.find()) {
            result.add(new SectionPiece(text.trim(), 0, text.length(), "(файл)"));
            return result;
        }
        int classStart = lineStart(text, matcher.start());
        String className = matcher.group(2);
        if (classStart > 0) {
            Piece preamble = trimPiece(text, 0, classStart);
            if (!preamble.text().isBlank()) {
                result.add(new SectionPiece(preamble.text(), preamble.startChar(),
                        preamble.endChar(), "(преамбула)"));
            }
        }
        int depth = 0;
        int pieceStart = classStart;
        int sectionStart = classStart;
        int parenSeen = -1;
        int methodStart = -1;
        for (int i = classStart; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '{') {
                if (i == text.length()) {
                    break;
                }
                depth++;
            } else if (text.charAt(i) == '}') {
                depth--;
                if (depth == 1 && methodStart >= 0) {
                    int endExclusive = i + 1;
                    String signature = text.substring(sectionStart, Math.min(endExclusive, text.length()));
                    String method = methodName(signature);
                    if (method == null) {
                        method = "(метод)";
                    }
                    String section = className + "#" + method;
                    Piece piece = trimPiece(text, pieceStart, endExclusive);
                    if (!piece.text().isBlank()) {
                        result.add(new SectionPiece(piece.text(), piece.startChar(),
                                piece.endChar(), section));
                    }
                    pieceStart = endExclusive;
                    methodStart = -1;
                    parenSeen = -1;
                }
            } else if (text.charAt(i) == '(' && depth == 1 && methodStart < 0) {
                boolean plausible = looksLikeMethodLine(text, i);
                if (plausible) {
                    methodStart = i;
                    parenSeen = i;
                }
            }
        }
        Piece tail = trimPiece(text, pieceStart, text.length());
        if (!tail.text().isBlank()) {
            String section = className;
            if (tail.text().isBlank()) {
                section = className;
            }
            result.add(new SectionPiece(tail.text(), tail.startChar(), tail.endChar(), section));
        }
        return result;
    }

    /** Выглядит ли "(" как начало сигнатуры метода (в/class-теле). */
    private static boolean looksLikeMethodLine(String text, int parenAt) {
        int lineStart = lineStart(text, parenAt);
        String line = text.substring(lineStart, Math.min(parenAt + 1, text.length()));
        return line.matches("\\s*(?:(?:public|private|protected|static|final|abstract|"
                + "synchronized|native|default|sealed|non-sealed|@\\w+)\\s+)*.*\\(");
    }

    private static String methodName(String signature) {
        Matcher matcher = METHOD_HEAD_PATTERN.matcher(signature);
        String found = null;
        while (matcher.find()) {
            found = matcher.group(1);
        }
        if (found != null) {
            return found;
        }
        Matcher constructor = Pattern.compile("\\b([A-Z][\\w]*)\\s*\\(").matcher(signature);
        return constructor.find() ? constructor.group(1) : null;
    }

    /** txt: разбиение по пустым строкам (абзацам). */
    static List<SectionPiece> paragraphSections(String text) {
        List<SectionPiece> result = new ArrayList<>();
        int start = 0;
        int paragraph = 0;
        while (start < text.length()) {
            int blank = text.indexOf("\n\n", start);
            int end = blank < 0 ? text.length() : blank + 1;
            String value = text.substring(start, end);
            if (!value.isBlank()) {
                paragraph++;
                result.add(new SectionPiece(value, start, end, "абзац-" + paragraph));
            }
            if (blank < 0) {
                break;
            }
            start = end;
        }
        return result;
    }

    private static int lineStart(String text, int index) {
        int start = index;
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }
}
