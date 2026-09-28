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
import java.util.stream.Collectors;

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
                    "%s: %d чанков, %d файлов, средний %.0f, мин %d, макс %d, сборка %d мс, запросов %d, модель %s, dim %d",
                    strategy, index.chunks().size(), sources.size(), avg,
                    min == Integer.MAX_VALUE ? 0 : min, max, index.buildMs(),
                    index.apiRequests(), index.model(), index.dim()));
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
        return "Сравнение записано: " + target + "\n\n" + verdictLine(parsed, resultsFixed, resultsStructure);
    }

    /** Ответ одной строкой: какая стратегия лучше и почему. */
    static String verdictLine(List<String[]> parsed,
                              List<QuestionResult> fixed, List<QuestionResult> structure) {
        int top1Fixed = hits(fixed, true);
        int top1Structure = hits(structure, true);
        int top3Fixed = hits(fixed, false);
        int top3Structure = hits(structure, false);
        if (top1Fixed == top1Structure && top3Fixed == top3Structure) {
            return "Вывод: обе стратегии одинаковы по контрольным вопросам ("
                    + top1Fixed + " top-1, " + top3Fixed + " top-3 из "
                    + parsed.size() + ") — различия только в размере чанков";
        }
        boolean structureWins = top1Structure * 3 + top3Structure
                > top1Fixed * 3 + top3Fixed;
        String winner = structureWins ? "structure" : "fixed";
        String loser = structureWins ? "fixed" : "structure";
        int wTop1 = structureWins ? top1Structure : top1Fixed;
        int wTop3 = structureWins ? top3Structure : top3Fixed;
        int lTop1 = structureWins ? top1Fixed : top1Structure;
        int lTop3 = structureWins ? top3Fixed : top3Structure;
        return "Вывод: лучше " + winner + " — top-1 " + wTop1 + " против " + lTop1
                + ", top-3 " + wTop3 + " против " + lTop3 + " у " + loser
                + " (из " + parsed.size() + " вопросов)";
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
        return hit.chunk().meta().source().toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT));
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
        out.append(String.format(Locale.ROOT, "| запросов к API | %d | %d |\n",
                fixed.apiRequests(), structure.apiRequests()));
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
        out.append("\n").append(verdictLine(parsed, resultsFixed, resultsStructure)).append("\n");
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
