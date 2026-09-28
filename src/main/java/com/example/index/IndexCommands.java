package com.example.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * CLI-команды /index: build [путь], stats, search <текст>, compare.
 * Возвращают числовые тексты для терминального вывода (без ANSI).
 */
public final class IndexCommands {

    /** Сравнение стратегий на контрольных вопросах. */
    public record QuestionResult(String question, String expected,
                                 boolean hitTop1, boolean hitTop3,
                                 double avgTop3) {
    }

    private final IndexStore store;
    private final Supplier<Embedder> embedderFactory;
    private final String model;

    public IndexCommands(IndexStore store, Supplier<Embedder> embedderFactory, String model) {
        this.store = store;
        this.embedderFactory = embedderFactory;
        this.model = model;
    }

    /** Выполняет подкоманду /index; возвращает текст для вывода. */
    public String handle(String argument) {
        String normalized = argument == null ? "" : argument.trim();
        String head = normalized.toLowerCase(Locale.ROOT);
        try {
            if (head.isEmpty() || head.startsWith("build")) {
                if (head.startsWith("build")) {
                    String path = normalized.substring("build".length()).trim();
                    return build(path);
                }
                return "Использование: /index build [путь], /index stats, "
                        + "/index search <текст>, /index compare";
            }
            if (head.equals("stats")) {
                return stats();
            }
            if (head.startsWith("search")) {
                String query = normalized.substring("search".length()).trim();
                if (query.isEmpty()) {
                    return "Использование: /index search <текст>";
                }
                return search(query);
            }
            if (head.equals("compare")) {
                return compare();
            }
            return "Неизвестная подкоманда /index: " + argument
                    + ". Доступно: build [путь], stats, search <текст>, compare";
        } catch (Exception e) {
            return "Ошибка /index: " + e.getMessage();
        }
    }

    /** Сборка обоих индексов с прогрессом. */
    public String build(String path) throws Exception {
        Path root = path.isBlank()
                ? Path.of("").toAbsolutePath().normalize()
                : Path.of(path).toAbsolutePath().normalize();
        Embedder embedder = embedderFactory.get();
        IndexService service = new IndexService(store);
        List<String> lines = new ArrayList<>();
        lines.add("Сборка индекса по корпусу: " + root);
        lines.add("модель: " + model);
        var fixed = service.build(IndexService.STRATEGY_FIXED, root.toString(),
                new DocumentLoader(), embedder, model, line -> lines.add("  " + line));
        var structure = service.build(IndexService.STRATEGY_STRUCTURE, root.toString(),
                new DocumentLoader(), embedder, model, line -> lines.add("  " + line));
        lines.add(String.format(Locale.ROOT,
                "fixed: %d чанков из %d файлов, %d запросов к API, %d мс",
                fixed.chunks(), fixed.files(), fixed.apiRequests(), fixed.buildMs()));
        lines.add(String.format(Locale.ROOT,
                "structure: %d чанков из %d файлов, %d запросов к API, %d мс",
                structure.chunks(), structure.files(), structure.apiRequests(), structure.buildMs()));
        lines.add("индексы: " + store.directory().resolve(IndexService.STRATEGY_FIXED + ".json")
                + ", " + store.directory().resolve(IndexService.STRATEGY_STRUCTURE + ".json"));
        return String.join("\n", lines);
    }

    /** Статистика по каждой стратегии. */
    public String stats() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String strategy : List.of(IndexService.STRATEGY_FIXED, IndexService.STRATEGY_STRUCTURE)) {
            IndexStore.Index index = store.load(strategy);
            if (index == null) {
                lines.add(strategy + ": индекс не найден (сначала /index build)");
                continue;
            }
            int min = Integer.MAX_VALUE;
            int max = 0;
            long total = 0;
            var sources = new java.util.HashSet<String>();
            for (IndexStore.IndexedChunk chunk : index.chunks()) {
                int size = chunk.meta().chars();
                min = Math.min(min, size);
                max = Math.max(max, size);
                total += size;
                sources.add(chunk.meta().source());
            }
            double avg = index.chunks().isEmpty() ? 0 : (double) total / index.chunks().size();
            lines.add(String.format(Locale.ROOT,
                    "%s: %d чанков, %d файлов, средний %.0f, мин %d, макс %d, сборка %d мс, API последней сборки %d, coldRequests %d, модель %s, dim %d",
                    strategy, index.chunks().size(), sources.size(), avg,
                    min == Integer.MAX_VALUE ? 0 : min, max, index.buildMs(),
                    index.apiRequests(), index.coldRequests(), index.model(), index.dim()));
        }
        return String.join("\n", lines);
    }

    /** Поиск top-5 по обоим индексам. */
    public String search(String query) throws Exception {
        float[] queryVector = embedderFactory.get().embed(List.of(query)).get(0);
        List<String> lines = new ArrayList<>();
        for (String strategy : List.of(IndexService.STRATEGY_FIXED, IndexService.STRATEGY_STRUCTURE)) {
            IndexStore.Index index = store.load(strategy);
            lines.add("--- " + strategy + " ---");
            if (index == null) {
                lines.add("(индекс не найден)");
                continue;
            }
            List<IndexSearch.Hit> hits = IndexSearch.topK(index, queryVector, 5);
            int placed = 1;
            for (IndexSearch.Hit hit : hits) {
                lines.add(String.format(Locale.ROOT, "%d. %.4f %s | %s | %s",
                        placed++, hit.score(), hit.chunk().meta().source(),
                        hit.chunk().meta().section(), preview(hit.chunk().text(), 120)));
            }
        }
        return String.join("\n", lines);
    }

    /** Сравнение стратегий: таблица + контрольные вопросы → md-файл. */
    public String compare() throws Exception {
        IndexStore.Index fixed = store.load(IndexService.STRATEGY_FIXED);
        IndexStore.Index structure = store.load(IndexService.STRATEGY_STRUCTURE);
        if (fixed == null || structure == null) {
            return "Сравнение требует обоих индексов — сначала /index build";
        }
        Embedder embedder = embedderFactory.get();
        List<QuestionResult> resultsFixed = new ArrayList<>();
        List<QuestionResult> resultsStructure = new ArrayList<>();
        Path questions = indexQuestionsFile(fixed.corpusRoot());
        List<String[]> parsed = readQuestions(questions);
        if (parsed.isEmpty()) {
            return "Нет контрольных вопросов: " + questions;
        }
        String sourceValidation = validateExpectedSources(parsed, fixed, structure);
        if (sourceValidation != null) {
            return "Ошибка контрольных вопросов: " + sourceValidation;
        }
        for (String[] item : parsed) {
            String question = item[0];
            String expected = item[1];
            float[] queryVector = embedder.embed(List.of(question)).get(0);
            resultsFixed.add(evaluate(fixed, question, expected, queryVector));
            resultsStructure.add(evaluate(structure, question, expected, queryVector));
        }
        String report = composeReport(fixed, structure, parsed, resultsFixed,
                resultsStructure, questions);
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path target = store.directory().resolve("compare-" + date + ".md");
        Files.createDirectories(store.directory());
        Files.writeString(target, report, StandardCharsets.UTF_8);
        return "Сравнение записано: " + target + "\n\n"
                + verdictLines(fixed, structure, parsed, resultsFixed, resultsStructure);
    }

    /** Сравнивает метрики отдельно; близкие top-k результаты помечаются незначимыми. */
    static String verdictLines(IndexStore.Index fixedIndex, IndexStore.Index structureIndex,
                               List<String[]> parsed,
                               List<QuestionResult> fixed, List<QuestionResult> structure) {
        int top1Fixed = hits(fixed, true);
        int top1Structure = hits(structure, true);
        int top3Fixed = hits(fixed, false);
        int top3Structure = hits(structure, false);
        String top1 = questionWinner("top-1", top1Fixed, top1Structure, parsed.size());
        String top3 = questionWinner("top-3", top3Fixed, top3Structure, parsed.size());
        String cuts = lowerIsBetter("обрезанности", midCutShare(fixedIndex),
                midCutShare(structureIndex), "%.1f%%");
        String requests = lowerIsBetter("cold API-запросов", fixedIndex.coldRequests(),
                structureIndex.coldRequests(), "%.0f");
        return "Вывод:\n" + top1 + "; " + top3 + "\n" + cuts + "\n" + requests;
    }

    private static String questionWinner(String metric, int fixed, int structure, int total) {
        if (Math.abs(fixed - structure) <= 1) {
            return metric + ": fixed " + fixed + "/" + total + ", structure "
                    + structure + "/" + total + " — разница ≤ 1 вопроса, незначимо";
        }
        return metric + ": лучше " + (fixed > structure ? "fixed" : "structure")
                + " (fixed " + fixed + "/" + total + ", structure " + structure
                + "/" + total + ")";
    }

    private static String lowerIsBetter(String metric, double fixed, double structure,
                                        String format) {
        if (Double.compare(fixed, structure) == 0) {
            return metric + ": равны (fixed " + String.format(Locale.ROOT, format, fixed)
                    + ", structure " + String.format(Locale.ROOT, format, structure) + ")";
        }
        return metric + ": лучше " + (fixed < structure ? "fixed" : "structure")
                + " (fixed " + String.format(Locale.ROOT, format, fixed)
                + ", structure " + String.format(Locale.ROOT, format, structure) + ")";
    }

    private static int hits(List<QuestionResult> results, boolean top1) {
        int count = 0;
        for (QuestionResult result : results) {
            if (top1 ? result.hitTop1() : result.hitTop3()) {
                count++;
            }
        }
        return count;
    }

    private QuestionResult evaluate(IndexStore.Index index, String question,
                                    String expected, float[] vector) {
        List<IndexSearch.Hit> hits = IndexSearch.topK(index, vector, 3);
        if (hits.isEmpty()) {
            return new QuestionResult(question, expected, false, false, 0.0);
        }
        boolean top1 = matches(hits.get(0), expected);
        boolean top3 = false;
        double sum = 0;
        for (IndexSearch.Hit hit : hits) {
            if (matches(hit, expected)) {
                top3 = true;
            }
            sum += hit.score();
        }
        return new QuestionResult(question, expected, top1, top3, sum / hits.size());
    }

    private static boolean matches(IndexSearch.Hit hit, String expected) {
        String source = hit.chunk().meta().source().toLowerCase(Locale.ROOT);
        for (String candidate : expected.split("\\|")) {
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && source.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String validateExpectedSources(List<String[]> questions,
                                                  IndexStore.Index fixed,
                                                  IndexStore.Index structure) {
        for (String[] question : questions) {
            for (String candidate : question[1].split("\\|")) {
                String expected = candidate.trim().toLowerCase(Locale.ROOT);
                if (expected.isEmpty()) {
                    return "для вопроса «" + question[0] + "» не задан expected source";
                }
                boolean inFixed = fixed.chunks().stream().anyMatch(chunk ->
                        chunk.meta().source().toLowerCase(Locale.ROOT).contains(expected));
                boolean inStructure = structure.chunks().stream().anyMatch(chunk ->
                        chunk.meta().source().toLowerCase(Locale.ROOT).contains(expected));
                if (!inFixed || !inStructure) {
                    return "файл «" + candidate.trim() + "» отсутствует в одном из индексов";
                }
            }
        }
        return null;
    }

    /** Сравнительная таблица + вопросы в markdown. */
    static String composeReport(IndexStore.Index fixed, IndexStore.Index structure,
                                List<String[]> parsed,
                                List<QuestionResult> resultsFixed,
                                List<QuestionResult> resultsStructure,
                                Path questionsFile) {
        StringBuilder out = new StringBuilder();
        out.append("# Сравнение стратегий индексации\n\n");
        out.append("Дата: ").append(LocalDate.now())
                .append(", модель: ").append(fixed.model())
                .append(", corpusRoot: ").append(fixed.corpusRoot()).append("\n\n");
        out.append("| Метрика | fixed | structure |\n|---|---|---|\n");
        out.append(String.format(Locale.ROOT, "| чанков | %d | %d |\n",
                fixed.chunks().size(), structure.chunks().size()));
        out.append(String.format(Locale.ROOT, "| средний размер | %.0f | %.0f |\n",
                avgChunkSize(fixed), avgChunkSize(structure)));
        out.append(String.format(Locale.ROOT, "| разброс размеров | %d–%d | %d–%d |\n",
                minChunkSize(fixed), maxChunkSize(fixed),
                minChunkSize(structure), maxChunkSize(structure)));
        out.append(String.format(Locale.ROOT, "| обрезанных посреди предложения/метода | %.1f%% | %.1f%% |\n",
                midCutShare(fixed), midCutShare(structure)));
        out.append(String.format(Locale.ROOT, "| время сборки | %d мс | %d мс |\n",
                fixed.buildMs(), structure.buildMs()));
        out.append(String.format(Locale.ROOT, "| запросов к API (coldRequests) | %d | %d |\n",
                fixed.coldRequests(), structure.coldRequests()));
        out.append("\n## Контрольные вопросы\n\n");
        out.append("| # | вопрос | ожидаемый файл | fixed top-1 | fixed top-3 | fixed avg | structure top-1 | structure top-3 | structure avg |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < parsed.size(); i++) {
            QuestionResult f = resultsFixed.get(i);
            QuestionResult s = resultsStructure.get(i);
            out.append(String.format(Locale.ROOT, "| %d | %s | %s | %s | %s | %.4f | %s | %s | %.4f |\n",
                    i + 1, escapeTable(parsed.get(i)[0]), escapeTable(parsed.get(i)[1]),
                    f.hitTop1() ? "да" : "нет", f.hitTop3() ? "да" : "нет", f.avgTop3(),
                    s.hitTop1() ? "да" : "нет", s.hitTop3() ? "да" : "нет", s.avgTop3()));
        }
        out.append("\n").append(verdictLines(fixed, structure, parsed,
                resultsFixed, resultsStructure)).append("\n");
        out.append("\nВопросы: ").append(questionsFile).append("\n");
        return out.toString();
    }

    private static String escapeTable(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static double avgChunkSize(IndexStore.Index index) {
        long total = 0;
        for (IndexStore.IndexedChunk chunk : index.chunks()) {
            total += chunk.meta().chars();
        }
        return index.chunks().isEmpty() ? 0 : (double) total / index.chunks().size();
    }

    private static int minChunkSize(IndexStore.Index index) {
        int min = Integer.MAX_VALUE;
        for (IndexStore.IndexedChunk chunk : index.chunks()) {
            min = Math.min(min, chunk.meta().chars());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int maxChunkSize(IndexStore.Index index) {
        int max = 0;
        for (IndexStore.IndexedChunk chunk : index.chunks()) {
            max = Math.max(max, chunk.meta().chars());
        }
        return max;
    }

    /**
     * Доля чанков, обрезанных посреди предложения/метода: последний
     * значимый символ не является терминатором (. ! ? : ; }) или заголовком.
     */
    static double midCutShare(IndexStore.Index index) {
        if (index.chunks().isEmpty()) {
            return 0.0;
        }
        int cut = 0;
        for (IndexStore.IndexedChunk chunk : index.chunks()) {
            if (midSentenceOrMethod(chunk.text())) {
                cut++;
            }
        }
        return (double) cut * 100 / index.chunks().size();
    }

    /** Эвристика обрыва посреди предложения или тела метода. */
    static boolean midSentenceOrMethod(String text) {
        if (text == null) {
            return false;
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return false;
        }
        char last = text.charAt(end - 1);
        return".!:;}»\"".indexOf(last) < 0 && last != '…';
    }

    /** Файл контрольных вопросов: возле corpusRoot или в cwd. */
    private static Path indexQuestionsFile(String corpusRoot) {
        Path root = corpusRoot == null || corpusRoot.isBlank()
                ? Path.of("")
                : Path.of(corpusRoot);
        Path candidate = root.resolve("index-questions.txt");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        Path local = Path.of("index-questions.txt");
        return Files.isRegularFile(local) ? local : candidate;
    }

    /** Формат файла: вопрос<TAB>ожидаемая часть source; строки с # — комментарии. */
    static List<String[]> readQuestions(Path file) throws IOException {
        List<String[]> result = new ArrayList<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String separator = line.contains("\t") ? "\t" : " | ";
                int split = line.indexOf(separator);
                if (split < 0) {
                    result.add(new String[]{line, ""});
                    continue;
                }
                result.add(new String[]{
                        line.substring(0, split).strip(),
                        line.substring(split + separator.length()).strip()});
            }
        }
        return result;
    }

    /** Первые max символов текста с переносами строк в пробел. */
    static String preview(String text, int max) {
        String flat = text.replace("\n", " ").replace("\r", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** Точка уровня тестов: хранилище уже связано с временным каталогом. */
    public IndexStore store() {
        return store;
    }
}
