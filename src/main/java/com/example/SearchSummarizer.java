package com.example;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Шаг 2 цепочки: пересчитывает SHA-256 входа (payloadSha256 из search)
 * и строит детерминированную сводку. Модель не вызывается.
 * Изменённый вход отбраковывается до любых вычислений.
 */
public final class SearchSummarizer {

    static final String TOOL_NAME = "summarize";
    static final int MAX_TOP_FILES = 10;
    static final int MAX_SAMPLE_LINES = 5;

    /** Файл в списке topFiles: путь и количество совпадений. */
    public record TopFile(String file, int matches) {
    }

    /**
     * Сводка. summarySha256 — SHA-256 канонического JSON сводки без самого поля.
     */
    public record Summary(String query, String root, int totalMatches, int shownMatches,
                          boolean truncated, int filesWithMatches, List<TopFile> topFiles,
                          List<FileSearcher.Match> sampleLines, String summaryText,
                          String sourceSha256, String summarySha256) {

        public Map<String, Object> toMap() {
            List<Map<String, Object>> top = new ArrayList<>();
            for (TopFile topFile : topFiles) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", topFile.file());
                item.put("matches", topFile.matches());
                top.add(item);
            }
            List<Map<String, Object>> samples = new ArrayList<>();
            for (FileSearcher.Match match : sampleLines) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", match.file());
                item.put("line", match.line());
                item.put("text", match.text());
                samples.add(item);
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("query", query);
            map.put("root", root);
            map.put("totalMatches", totalMatches);
            map.put("shownMatches", shownMatches);
            map.put("truncated", truncated);
            map.put("filesWithMatches", filesWithMatches);
            map.put("topFiles", top);
            map.put("sampleLines", samples);
            map.put("summaryText", summaryText);
            map.put("sourceSha256", sourceSha256);
            map.put("summarySha256", summarySha256);
            return map;
        }

        /** Хеш канонического JSON сводки без самого summarySha256. */
        public String integritySha256() {
            return PipelineCanonicalJson.sha256Hex(PipelineCanonicalJson.canonical(
                    summaryMap(query, root, totalMatches, shownMatches, truncated, filesWithMatches,
                            topFiles, sampleLines, summaryText, sourceSha256)));
        }
    }

    private SearchSummarizer() {
    }

    /**
     * Сводка по результату search. searchResult — карта ровно как вернул search;
     * несовпадение payloadSha256 → ошибка инструмента "input integrity check failed".
     */
    public static Summary summarize(Map<String, Object> searchResult) throws PipelineToolException {
        if (searchResult == null) {
            throw new PipelineToolException(INTEGRITY_ERROR);
        }
        Map<String, Object> input = new LinkedHashMap<>(searchResult);
        String claimed = string(input.remove("payloadSha256"));
        if (claimed == null
                || !claimed.equals(PipelineCanonicalJson.sha256Hex(PipelineCanonicalJson.canonical(input)))) {
            throw new PipelineToolException(INTEGRITY_ERROR);
        }
        return summarize(input, claimed);
    }

    /** Внутренняя сводка уже проверенного входа (без поля payloadSha256). */
    static Summary summarize(Map<String, Object> input, String sourceSha256)
            throws PipelineToolException {
        String query = string(input.get("query"));
        String root = string(input.get("root"));
        Integer totalMatches = integer(input.get("totalMatches"));
        Boolean truncated = bool(input.get("truncated"));
        if (query == null || root == null || totalMatches == null || truncated == null) {
            throw new PipelineToolException("searchResult не содержит ожидаемых полей search.");
        }
        Object rawMatches = input.get("matches");
        List<FileSearcher.Match> matches = new ArrayList<>();
        if (rawMatches instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) {
                    throw new PipelineToolException("matches содержит не объект совпадения.");
                }
                String file = string(entry.get("file"));
                Integer line = integer(entry.get("line"));
                String text = string(entry.get("text"));
                if (file == null || line == null || text == null) {
                    throw new PipelineToolException("match не содержит ожидаемых полей.");
                }
                matches.add(new FileSearcher.Match(file, line, text));
            }
        } else if (rawMatches != null) {
            throw new PipelineToolException("matches должен быть массивом.");
        }

        int shownMatches = matches.size();
        Map<String, Integer> perFile = new LinkedHashMap<>();
        for (FileSearcher.Match match : matches) {
            perFile.merge(match.file(), 1, Integer::sum);
        }
        int filesWithMatches = perFile.size();
        List<TopFile> topFiles = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : perFile.entrySet()) {
            topFiles.add(new TopFile(entry.getKey(), entry.getValue()));
        }
        // До 10: по убыванию количества, при равенстве — по имени файла.
        topFiles.sort(Comparator.comparingInt(TopFile::matches).reversed()
                .thenComparing(TopFile::file, Comparator.naturalOrder()));
        if (topFiles.size() > MAX_TOP_FILES) {
            topFiles = new ArrayList<>(topFiles.subList(0, MAX_TOP_FILES));
        }
        List<FileSearcher.Match> sampleLines = matches.subList(0, Math.min(matches.size(), MAX_SAMPLE_LINES));
        String summaryText = buildSummaryText(totalMatches, filesWithMatches, topFiles, truncated);
        return new Summary(query, root, totalMatches, shownMatches, truncated, filesWithMatches,
                List.copyOf(topFiles), List.copyOf(sampleLines), summaryText, sourceSha256,
                PipelineCanonicalJson.sha256Hex(PipelineCanonicalJson.canonical(
                        summaryMap(query, root, totalMatches, shownMatches, truncated,
                                filesWithMatches, topFiles, sampleLines, summaryText, sourceSha256))));
    }

    /** Хеш канонического JSON сводки без поля summarySha256. */
    static String integrity(String query, String root, int totalMatches, int shownMatches,
                            boolean truncated, int filesWithMatches, List<TopFile> topFiles,
                            List<FileSearcher.Match> sampleLines, String summaryText,
                            String sourceSha256) {
        return PipelineCanonicalJson.sha256Hex(PipelineCanonicalJson.canonical(
                summaryMap(query, root, totalMatches, shownMatches, truncated, filesWithMatches,
                        topFiles, sampleLines, summaryText, sourceSha256)));
    }

    static String buildSummaryText(int totalMatches, int filesWithMatches, List<TopFile> topFiles,
                                   boolean truncated) {
        if (totalMatches == 0) {
            return "Совпадений запроса не найдено.";
        }
        StringBuilder text = new StringBuilder("Найдено ").append(totalMatches)
                .append(ruPlural(totalMatches, " совпадение", " совпадения", " совпадений"))
                .append(" в ").append(filesWithMatches).append(ruPlural(filesWithMatches, " файле", " файлах", " файлах"));
        if (!topFiles.isEmpty()) {
            TopFile top = topFiles.get(0);
            text.append("; больше всего в ").append(top.file()).append(" (")
                    .append(top.matches()).append(")");
        }
        text.append('.');
        if (truncated) {
            text.append(" Список совпадений обрезан лимитом maxResults.");
        }
        return text.toString();
    }

    /** Фиксированный порядок полей сводки; summarySha256 добавляет сам вызывающий. */
    static Map<String, Object> summaryMap(String query, String root, int totalMatches,
                                          int shownMatches, boolean truncated,
                                          int filesWithMatches, List<TopFile> topFiles,
                                          List<FileSearcher.Match> sampleLines,
                                          String summaryText, String sourceSha256) {
        List<Map<String, Object>> top = new ArrayList<>();
        for (TopFile topFile : topFiles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", topFile.file());
            item.put("matches", topFile.matches());
            top.add(item);
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (FileSearcher.Match match : sampleLines) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", match.file());
            item.put("line", match.line());
            item.put("text", match.text());
            samples.add(item);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("query", query);
        map.put("root", root);
        map.put("totalMatches", totalMatches);
        map.put("shownMatches", shownMatches);
        map.put("truncated", truncated);
        map.put("filesWithMatches", filesWithMatches);
        map.put("topFiles", top);
        map.put("sampleLines", samples);
        map.put("summaryText", summaryText);
        map.put("sourceSha256", sourceSha256);
        return map;
    }

    private static final String INTEGRITY_ERROR = "input integrity check failed";

    static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    static Integer integer(Object value) {
        return value instanceof Integer number ? number : null;
    }

    static Boolean bool(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }

    static String ruPlural(int count, String one, String few, String many) {
        long abs = Math.abs(count);
        long mod10 = abs % 10;
        long mod100 = abs % 100;
        if (mod10 == 1 && mod100 != 11) return one;
        return mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? few : many;
    }
}
