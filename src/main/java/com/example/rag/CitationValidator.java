package com.example.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates numbered RAG references and verbatim quotes against the retrieved chunks.
 * Структурная проверка «пункт → дословная цитата» не доказывает смыслового
 * соответствия: она лишь требует у каждого показанного пункта дословный фрагмент
 * из указанного чанка. Смысловое соответствие проверяет человек.
 */
public final class CitationValidator {
    public enum AnswerStatus { ANSWERED, IDK_LOW_RELEVANCE, IDK_THRESHOLD, IDK_MODEL, UNVERIFIED }

    private static final Pattern REFERENCE = Pattern.compile("\\[(\\d+)]");
    private static final Pattern QUOTE = Pattern.compile("\\[(\\d+)]\\s*[«“](.*?)[»”]",
            Pattern.DOTALL);
    private static final Pattern ITEM_START = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+");
    private static final String UNCOVERED_MARKER = "не покрыто контекстом";

    public record Quote(int number, String text) { }
    public record RejectedQuote(int number, String text, String reason) { }

    public record Validation(List<Integer> references, List<Quote> confirmedQuotes,
                             int totalQuotes, int rejectedQuotes,
                             int invalidReferences, List<RejectedQuote> rejectedQuoteDetails,
                             List<String> confirmedItems, List<String> uncoveredItems,
                             int droppedItemCount) {
        public Validation(List<Integer> references, List<Quote> confirmedQuotes,
                          int totalQuotes, int rejectedQuotes, int invalidReferences,
                          List<RejectedQuote> rejectedQuoteDetails) {
            this(references, confirmedQuotes, totalQuotes, rejectedQuotes, invalidReferences,
                    rejectedQuoteDetails, List.of(), List.of(), 0);
        }

        public Validation(List<Integer> references, List<Quote> confirmedQuotes,
                          int totalQuotes, int rejectedQuotes, int invalidReferences) {
            this(references, confirmedQuotes, totalQuotes, rejectedQuotes, invalidReferences,
                    List.of(), List.of(), List.of(), 0);
        }

        public Validation {
            references = List.copyOf(references);
            confirmedQuotes = List.copyOf(confirmedQuotes);
            rejectedQuoteDetails = List.copyOf(rejectedQuoteDetails);
            confirmedItems = List.copyOf(confirmedItems);
            uncoveredItems = List.copyOf(uncoveredItems);
        }

        public Set<Integer> validReferences() {
            return Set.copyOf(new LinkedHashSet<>(references));
        }

        /** Только номера с подтверждённой цитатой: ссылка [N] без цитаты не показывается. */
        public List<Integer> confirmedQuoteNumbers() {
            return confirmedQuotes.stream().map(Quote::number).distinct().sorted().toList();
        }
    }

    public Validation validate(String answer, List<RagRetriever.Chunk> chunks) {
        String text = stripModelSources(answer == null ? "" : answer);
        List<Integer> references = new ArrayList<>();
        int invalidReferences = 0;
        Matcher referenceMatcher = REFERENCE.matcher(text);
        while (referenceMatcher.find()) {
            int number;
            try {
                number = Integer.parseInt(referenceMatcher.group(1));
            } catch (NumberFormatException overflow) {
                invalidReferences++;
                continue;
            }
            if (number < 1 || number > chunks.size()) invalidReferences++;
            else references.add(number);
        }

        List<Quote> confirmed = new ArrayList<>();
        List<RejectedQuote> rejectedDetails = new ArrayList<>();
        int total = 0;
        int rejected = 0;
        Matcher quoteMatcher = QUOTE.matcher(text);
        while (quoteMatcher.find()) {
            total++;
            String quote = quoteMatcher.group(2);
            int number;
            try {
                number = Integer.parseInt(quoteMatcher.group(1));
            } catch (NumberFormatException overflow) {
                rejected++;
                rejectedDetails.add(rejected(-1, quote, "citation-number-out-of-range"));
                continue;
            }
            String reason = quoteRejectionReason(number, quote, chunks);
            if (reason != null) {
                rejected++;
                rejectedDetails.add(rejected(number, quote, reason));
                continue;
            }
            confirmed.add(new Quote(number, RagQueryRewriter.redactSecrets(quote.strip())));
        }

        List<String> confirmedItems = new ArrayList<>();
        List<String> uncoveredItems = new ArrayList<>();
        int droppedItems = 0;
        for (String item : splitItems(text)) {
            if (item.toLowerCase(Locale.ROOT).startsWith(UNCOVERED_MARKER)) {
                uncoveredItems.add(itemDisplayText(item));
                continue;
            }
            if (itemConfirmed(item, chunks)) confirmedItems.add(itemDisplayText(item));
            else droppedItems++;
        }
        return new Validation(references, confirmed, total, rejected, invalidReferences,
                rejectedDetails, confirmedItems, uncoveredItems, droppedItems);
    }

    /** Пункт подтверждён, только если в нём самом есть прошедшая проверку цитата:
     * одна валидная цитата в другом пункте не легализует этот пункт. */
    private static boolean itemConfirmed(String item, List<RagRetriever.Chunk> chunks) {
        Matcher quoteMatcher = QUOTE.matcher(item);
        while (quoteMatcher.find()) {
            String quote = quoteMatcher.group(2);
            int number;
            try {
                number = Integer.parseInt(quoteMatcher.group(1));
            } catch (NumberFormatException overflow) {
                continue;
            }
            if (quoteRejectionReason(number, quote, chunks) == null) return true;
        }
        return false;
    }

    private static String quoteRejectionReason(int number, String quote,
                                               List<RagRetriever.Chunk> chunks) {
        String normalizedQuote = normalize(quote);
        if (number < 1 || number > chunks.size()) return "citation-number-out-of-range";
        if (normalizedQuote.length() < 20) return "quote-shorter-than-20";
        if (normalizedQuote.length() > 300) return "quote-longer-than-300";
        if (RagQueryRewriter.looksSecret(quote)) return "secret-like-content";
        if (!normalize(chunks.get(number - 1).text()).contains(normalizedQuote)) {
            return "not-a-verbatim-substring";
        }
        return null;
    }

    /** Пункты: строка-маркер списка начинает новый пункт; пустая строка разделяет;
     * остальные строки присоединяются к текущему пункту (переносы внутри пункта). */
    static List<String> splitItems(String text) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : (text == null ? "" : text).split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                if (current.length() > 0) items.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (ITEM_START.matcher(stripped).find() && current.length() > 0) {
                items.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append('\n');
            current.append(stripped);
        }
        if (current.length() > 0) items.add(current.toString());
        return items;
    }

    private static String itemDisplayText(String item) {
        return QUOTE.matcher(item).replaceAll("[$1]")
                .replaceAll("(?imu)^\\s*(?:#{1,6}\\s*)?(?:источники|sources|цитаты|quotes)[ \\t]*[:\\t ]*$",
                        "")
                .replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static RejectedQuote rejected(int number, String quote, String reason) {
        String text = RagQueryRewriter.looksSecret(quote) ? "[скрыто: secret-like]"
                : RagQueryRewriter.redactSecrets(quote == null ? "" : quote.strip());
        if (text.length() > 300) text = text.substring(0, 300) + "…";
        return new RejectedQuote(number, text, reason);
    }

    /** Whitespace and common straight/typographic quote glyphs are canonicalized. */
    public static String normalize(String value) {
        if (value == null) return "";
        return value.replace('«', '"').replace('»', '"')
                .replace('“', '"').replace('”', '"')
                .replace('„', '"').replace('‟', '"')
                .replace('‘', '\'').replace('’', '\'')
                .replaceAll("\\s+", " ").strip();
    }

    /** Removes quote payloads and the model's own source/quote sections from the answer portion;
     * display-only: validate() must keep quote sections, they carry the citations to check. */
    public static String answerText(String answer) {
        if (answer == null) return "";
        String withoutSections = answer.replaceAll(
                "(?isu)(?:^|\\n)\\s*(?:#{1,6}\\s*)?(?:источники|sources|цитаты|quotes)"
                        + "[ \\t]*(?::|\\n|$).*", "");
        return QUOTE.matcher(withoutSections).replaceAll("[$1]")
                .replaceAll("(?m)^[ \\t]*$", "")
                .replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String stripModelSources(String answer) {
        return answer.replaceAll("(?isu)(?:^|\\n)\\s*(?:источники|sources)\\s*:\\s*.*$", "");
    }
}
