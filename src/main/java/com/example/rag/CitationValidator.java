package com.example.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates numbered RAG references and verbatim quotes against the retrieved chunks. */
public final class CitationValidator {
    public enum AnswerStatus { ANSWERED, IDK_LOW_RELEVANCE, IDK_THRESHOLD, IDK_MODEL, UNVERIFIED }

    private static final Pattern REFERENCE = Pattern.compile("\\[(\\d+)]");
    private static final Pattern QUOTE = Pattern.compile("\\[(\\d+)]\\s*[«“](.*?)[»”]",
            Pattern.DOTALL);

    public record Quote(int number, String text) { }
    public record RejectedQuote(int number, String text, String reason) { }

    public record Validation(List<Integer> references, List<Quote> confirmedQuotes,
                             int totalQuotes, int rejectedQuotes,
                             int invalidReferences, List<RejectedQuote> rejectedQuoteDetails) {
        public Validation(List<Integer> references, List<Quote> confirmedQuotes,
                          int totalQuotes, int rejectedQuotes, int invalidReferences) {
            this(references, confirmedQuotes, totalQuotes, rejectedQuotes, invalidReferences, List.of());
        }

        public Validation {
            references = List.copyOf(references);
            confirmedQuotes = List.copyOf(confirmedQuotes);
            rejectedQuoteDetails = List.copyOf(rejectedQuoteDetails);
        }

        public Set<Integer> validReferences() {
            return Set.copyOf(new LinkedHashSet<>(references));
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
            int number;
            try {
                number = Integer.parseInt(quoteMatcher.group(1));
            } catch (NumberFormatException overflow) {
                rejected++;
                rejectedDetails.add(rejected(-1, quoteMatcher.group(2), "citation-number-out-of-range"));
                continue;
            }
            String quote = quoteMatcher.group(2);
            String normalizedQuote = normalize(quote);
            String reason = null;
            if (number < 1 || number > chunks.size()) reason = "citation-number-out-of-range";
            else if (normalizedQuote.length() < 20) reason = "quote-shorter-than-20";
            else if (normalizedQuote.length() > 300) reason = "quote-longer-than-300";
            else if (RagQueryRewriter.looksSecret(quote)) reason = "secret-like-content";
            else if (!normalize(chunks.get(number - 1).text()).contains(normalizedQuote)) {
                reason = "not-a-verbatim-substring";
            }
            if (reason != null) {
                rejected++;
                rejectedDetails.add(rejected(number, quote, reason));
                continue;
            }
            confirmed.add(new Quote(number, RagQueryRewriter.redactSecrets(quote.strip())));
        }
        return new Validation(references, confirmed, total, rejected, invalidReferences, rejectedDetails);
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

    /** Removes quote payloads from the answer portion; the formatter lists verified quotes below. */
    public static String answerText(String answer) {
        if (answer == null) return "";
        return QUOTE.matcher(stripModelSources(answer)).replaceAll("").replaceAll("(?m)^[ \\t]*$", "")
                .replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String stripModelSources(String answer) {
        return answer.replaceAll("(?isu)(?:^|\\n)\\s*(?:источники|sources)\\s*:\\s*.*$", "");
    }
}
