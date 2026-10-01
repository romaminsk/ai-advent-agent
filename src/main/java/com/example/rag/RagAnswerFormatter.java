package com.example.rag;

import java.util.List;
import java.util.Set;

/** Renders only code-derived sources and validator-approved quotations. */
public final class RagAnswerFormatter {
    private RagAnswerFormatter() { }

    public static String format(RagService.Result result, boolean diagnostics) {
        StringBuilder out = new StringBuilder();
        if (result.answerStatus() == CitationValidator.AnswerStatus.IDK_LOW_RELEVANCE
                || result.answerStatus() == CitationValidator.AnswerStatus.IDK_THRESHOLD) {
            out.append(RagConstants.LOW_RELEVANCE_ANSWER);
            if (result.answerStatus() == CitationValidator.AnswerStatus.IDK_THRESHOLD) {
                out.append("\nПричина: top-1 score ")
                        .append(String.format(java.util.Locale.ROOT, "%.4f",
                                result.retrievedChunks().stream()
                                        .mapToDouble(RagRetriever.Chunk::score).max().orElse(0)))
                        .append(" ниже порога «не знаю»; фильтр minScore чанки пропустил.");
            } else if (result.retrievedChunks().isEmpty()) {
                out.append("\nПричина: поиск не дал чанков.");
            } else {
                out.append("\nПричина: после фильтра minScore не осталось чанков (filteredAll).");
            }
            out.append("\nБлижайшие разделы:");
            result.retrievedChunks().stream().limit(3).forEach(chunk -> out.append("\n")
                    .append(safe(chunk.source())).append(" — ").append(safe(chunk.section()))
                    .append(" (score ").append(String.format(java.util.Locale.ROOT, "%.4f", chunk.score()))
                    .append(')'));
            if (result.retrievedChunks().isEmpty()) out.append(" нет");
            out.append("\nУточните вопрос: укажите файл, класс или команду.");
            appendDiagnostics(out, result, diagnostics);
            return out.toString();
        }

        if (result.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED) {
            out.append("Не могу подтвердить ответ цитатами из базы.");
        } else {
            out.append("Ответ: ").append(result.answerStatus()
                    == CitationValidator.AnswerStatus.IDK_MODEL ? "НЕ ЗНАЮ"
                    : CitationValidator.answerText(safe(result.answer())));
        }

        Set<Integer> referenced = result.citations().validReferences();
        List<Integer> ordered = referenced.stream().sorted().toList();
        out.append("\nИсточники:");
        for (int number : ordered) {
            RagRetriever.Chunk chunk = result.chunks().get(number - 1);
            out.append("\n[").append(number).append("] ").append(safe(chunk.source()))
                    .append(" — ").append(safe(chunk.section())).append(" (chunk: ")
                    .append(safe(chunk.chunkId())).append(')');
        }
        if (ordered.isEmpty()) out.append(" нет");
        out.append("\nЦитаты:");
        for (CitationValidator.Quote quote : result.citations().confirmedQuotes()) {
            out.append("\n[").append(quote.number()).append("] «")
                    .append(safe(quote.text())).append("»");
        }
        if (result.citations().confirmedQuotes().isEmpty()) out.append(" нет");
        if (result.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED) {
            out.append("\nУточните вопрос, указав файл, класс или команду.");
        }
        appendDiagnostics(out, result, diagnostics);
        return out.toString();
    }

    private static void appendDiagnostics(StringBuilder out, RagService.Result result,
                                          boolean diagnostics) {
        if (!diagnostics) return;
        out.append("\nСтатус: ").append(result.answerStatus())
                .append("; отброшено цитат: ").append(result.citations().rejectedQuotes())
                .append("; ссылок вне диапазона: ").append(result.citations().invalidReferences());
    }

    private static String safe(String value) {
        if (RagQueryRewriter.looksSecret(value)) return "[скрыто]";
        return RagQueryRewriter.redactSecrets(value == null ? "" : value);
    }
}
