package com.example;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Лёгкий рендер Markdown для терминала с поддержкой ANSI: заголовки,
 * списки, цитаты, жирный/курсив, inline-код, ссылки и ограждённые блоки
 * кода с именем языка и минимальной подсветкой (строки, комментарии,
 * числа, ключевые слова).
 *
 * Применяется только в интерактивном режиме с включённым цветом;
 * без цвета текст остаётся исходным. Управляющих последовательностей
 * вне оформленных элементов не добавляется, содержимое ответа
 * не изменяется — меняется только его отображение.
 */
final class MarkdownTerminal {

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String ITALIC = "\u001b[3m";
    private static final String DIM = "\u001b[2m";
    private static final String CYAN = "\u001b[36m";
    private static final String BLUE = "\u001b[34m";
    private static final String YELLOW = "\u001b[33m";

    /** Заголовок: от одного до шести «#» в начале строки. */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** Маркированный список: «- », «* », «+ » в начале строки. */
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");

    /** Цитата: «> » в начале строки. */
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");

    /** Ограждённый блок кода: ``` или ~~~, возможно с именем языка. */
    private static final Pattern FENCE = Pattern.compile("^\\s*(```+|~~~+)\\s*([\\w+#-]*)\\s*$");

    /**
     * Inline-элементы одним проходом: код, ссылка, жирный, курсив.
     * Единый шаблон исключает перекрытие: последовательные замены могли
     * бы находить разметку внутри уже вставленного ANSI.
     */
    private static final Pattern INLINE_ALL = Pattern.compile(
            "`([^`]+)`"
                    + "|\\[([^\\]]+)]\\(([^)]+)\\)"
                    + "|\\*\\*([^*]+)\\*\\*|__([^_]+)__"
                    + "|(?<!\\*)\\*([^*\\s][^*]*)\\*(?!\\*)"
                    + "|(?<!_)_([^_\\s][^_]*)_(?!_)");

    /**
     * Токены кода для подсветки: комментарии, строки, числа, ключевые слова.
     * Порядок альтернатив задаёт приоритет: комментарий или строка целиком
     * перекрывает ключевые слова внутри себя.
     */
    private static final Pattern CODE_TOKENS = Pattern.compile(
            "(//.*$|#[^!].*$|/\\*.*?\\*/)"
                    + "|(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*')"
                    + "|\\b(\\d+(?:\\.\\d+)?)\\b"
                    + "|\\b(package|import|public|private|protected|class|interface|record|enum|static|final|void|return|if|else|for|while|switch|case|break|continue|new|try|catch|finally|throw|throws|extends|implements|def|function|var|val|let|const|async|await|true|false|null|nil|None|True|False)\\b");

    private MarkdownTerminal() {
    }

    /** Рендерит Markdown-текст в текст с ANSI-оформлением. */
    static String render(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inCode = false;
        String codeLang = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                if (!inCode) {
                    inCode = true;
                    codeLang = fence.group(2);
                    if (!codeLang.isEmpty()) {
                        out.append(DIM).append("· ").append(codeLang).append(RESET);
                    }
                } else {
                    inCode = false;
                    codeLang = "";
                }
                if (i < lines.length - 1) {
                    out.append('\n');
                }
                continue;
            }
            if (inCode) {
                out.append(highlightCode(line));
            } else {
                out.append(renderLine(line));
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** Оформление одной строки вне блока кода. */
    private static String renderLine(String line) {
        Matcher heading = HEADING.matcher(line);
        if (heading.matches()) {
            return BOLD + CYAN + inline(heading.group(2)) + RESET;
        }
        Matcher quote = QUOTE.matcher(line);
        if (quote.matches()) {
            return DIM + "▌ " + RESET + inline(quote.group(1));
        }
        Matcher bullet = BULLET.matcher(line);
        if (bullet.matches()) {
            return bullet.group(1) + BLUE + "•" + RESET + " " + inline(bullet.group(2));
        }
        return inline(line);
    }

    /** Inline-разметка одним проходом: код, ссылки, жирный, курсив. */
    private static String inline(String line) {
        return INLINE_ALL.matcher(line).replaceAll(m -> {
            if (m.group(1) != null) {
                return CYAN + m.group(1) + RESET;
            }
            if (m.group(2) != null) {
                return m.group(2) + DIM + " (" + m.group(3) + ")" + RESET;
            }
            if (m.group(4) != null || m.group(5) != null) {
                return BOLD + (m.group(4) != null ? m.group(4) : m.group(5)) + RESET;
            }
            return ITALIC + (m.group(6) != null ? m.group(6) : m.group(7)) + RESET;
        });
    }

    /** Минимальная подсветка строки кода: комментарии, строки, числа, ключевые слова. */
    private static String highlightCode(String line) {
        Matcher matcher = CODE_TOKENS.matcher(line);
        StringBuilder result = new StringBuilder();
        int pos = 0;
        boolean any = false;
        while (matcher.find()) {
            any = true;
            String color;
            if (matcher.group(1) != null) {
                color = DIM;
            } else if (matcher.group(2) != null) {
                color = YELLOW;
            } else if (matcher.group(3) != null) {
                color = CYAN;
            } else {
                color = BLUE;
            }
            result.append(line, pos, matcher.start());
            result.append(color).append(matcher.group()).append(RESET);
            pos = matcher.end();
        }
        if (!any) {
            return line;
        }
        result.append(line.substring(pos));
        return result.toString();
    }
}
