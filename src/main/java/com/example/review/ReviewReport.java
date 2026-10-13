package com.example.review;

import com.example.rag.RagRetriever;

import java.util.List;

/** Markdown-отчёт ревью: маркер комментария, список файлов, разделы LLM, источники. */
public final class ReviewReport {

    public static final String MARKER = "<!-- ai-review -->";
    public static final String DISCLAIMER = "_Сгенерировано AI, требует проверки человеком._";
    static final List<String> SECTIONS = List.of(
            "### Потенциальные баги", "### Архитектурные проблемы", "### Рекомендации");

    private ReviewReport() {
    }

    /** Комментарий всегда начинается с маркера; повторный вызов не дублирует его. */
    public static String withMarker(String body) {
        return body.startsWith(MARKER) ? body : MARKER + "\n" + body;
    }

    /** Лимит комментария GitHub; при превышении отчёт обрезается с пометкой (маркер сохраняется). */
    public static final int COMMENT_LIMIT = 65_536;
    static final String TRUNCATED_NOTE = "\n\n_Отчёт обрезан по лимиту комментария GitHub (65536 симв.)._\n";

    public static String fitComment(String markdown) {
        if (markdown.codePointCount(0, markdown.length()) <= COMMENT_LIMIT) {
            return markdown;
        }
        int keep = COMMENT_LIMIT - TRUNCATED_NOTE.codePointCount(0, TRUNCATED_NOTE.length());
        return markdown.substring(0, markdown.offsetByCodePoints(0, keep)) + TRUNCATED_NOTE;
    }

    public static boolean hasSections(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return SECTIONS.stream().allMatch(text::contains);
    }

    static String noChanges(String base, String head) {
        return withMarker("## AI-ревью PR\n\nИзменений между `" + base + "` и `" + head + "` нет.\n");
    }

    static String nothingToAnalyze(String base, String head, List<ReviewRunner.Entry> entries) {
        return withMarker("## AI-ревью PR\n\n" + summary(base, head, entries) + "\n\n"
                + filesList(entries) + "\n\nНечего анализировать: все файлы пропущены или не влезли в лимит diff.\n");
    }

    static String unavailable(String base, String head, List<ReviewRunner.Entry> entries, String reason) {
        return withMarker("## AI-ревью PR\n\n" + summary(base, head, entries) + "\n\n"
                + filesList(entries) + "\n\nАвторевью недоступно: " + reason + "\n");
    }

    static String success(String base, String head, List<ReviewRunner.Entry> entries, String llmText,
                          List<RagRetriever.Chunk> chunks, String ragError,
                          String model, String mode, String fallbackNote) {
        StringBuilder text = new StringBuilder();
        text.append("## AI-ревью PR\n\n").append(summary(base, head, entries)).append("\n\n");
        text.append("Модель: ").append(model).append(", режим: ").append(mode).append(".\n");
        if (fallbackNote != null) {
            text.append(fallbackNote).append('\n');
        }
        text.append('\n');
        text.append(filesList(entries)).append("\n\n");
        text.append(llmText.strip()).append("\n\n");
        text.append("### Источники\n");
        if (chunks.isEmpty()) {
            text.append(ragError == null ? "Фрагменты не найдены." : ragError).append('\n');
        }
        for (int i = 0; i < chunks.size(); i++) {
            RagRetriever.Chunk chunk = chunks.get(i);
            text.append('[').append(i + 1).append("] `").append(chunk.source()).append("` › ")
                    .append(chunk.section()).append('\n');
        }
        text.append('\n').append(DISCLAIMER).append('\n');
        return withMarker(text.toString());
    }

    static String summary(String base, String head, List<ReviewRunner.Entry> entries) {
        long analyzed = entries.stream().filter(e -> e.status() == ReviewRunner.Status.ANALYZED).count();
        long partial = entries.stream().filter(e -> e.status() == ReviewRunner.Status.PARTIAL).count();
        long skipped = entries.stream().filter(e -> e.status() == ReviewRunner.Status.SKIPPED).count();
        long notAnalyzed = entries.stream().filter(e -> e.status() == ReviewRunner.Status.NOT_ANALYZED).count();
        return "База: `" + base + "` → голова: `" + head + "`. Файлов: " + entries.size()
                + ", проанализировано: " + analyzed + ", частично: " + partial
                + ", пропущено: " + skipped + ", не анализировалось: " + notAnalyzed + ".";
    }

    static String filesList(List<ReviewRunner.Entry> entries) {
        StringBuilder text = new StringBuilder("### Изменённые файлы\n");
        for (ReviewRunner.Entry entry : entries) {
            text.append("- `").append(entry.path()).append("` (+").append(entry.added())
                    .append(" −").append(entry.removed()).append(") — ").append(statusText(entry)).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static String statusText(ReviewRunner.Entry entry) {
        return switch (entry.status()) {
            case ANALYZED -> "проанализирован";
            case PARTIAL -> "частично проанализирован (обрезано по лимиту diff)";
            case NOT_ANALYZED -> "не анализировалось: " + entry.reason();
            case SKIPPED -> "пропущен: " + entry.reason();
        };
    }
}
