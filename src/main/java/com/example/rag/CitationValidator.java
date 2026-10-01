package com.example.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates numbered RAG references and verbatim quotes against the retrieved chunks. */
public final class CitationValidator {
    public enum AnswerStatus { ANSWERED, IDK_LOW_RELEVANCE, IDK_MODEL, UNVERIFIED }

    private static final Pattern REFERENCE = Pattern.compile("\\[(\\d+)]");
    private static final Pattern QUOTE = Pattern.compile("\\[(\\d+)]\\s*[«“](.*?)[»”]",
            Pattern.DOTALL);

    public record Quote(int number, String text) { }

    public record Validation(List<Integer> references, List<Quote> confirmedQuotes,
                             int totalQuotes, int rejectedQuotes,
                             int invalidReferences) {
        public Validation {
            references = List.copyOf(references);
            confirmedQuotes = List.copyOf(confirmedQuotes);
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
                continue;
            }
            String quote = quoteMatcher.group(2);
            String normalizedQuote = normalize(quote);
            if (number < 1 || number > chunks.size()
                    || normalizedQuote.length() < 20 || normalizedQuote.length() > 300
                    || RagQueryRewriter.looksSecret(quote)
                    || !normalize(chunks.get(number - 1).text()).contains(normalizedQuote)) {
                rejected++;
                continue;
            }
            confirmed.add(new Quote(number, RagQueryRewriter.redactSecrets(quote.strip())));
        }
        return new Validation(references, confirmed, total, rejected, invalidReferences);
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
