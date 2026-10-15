package com.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ссылки вида Foo.java:42, Foo.java#L42 и «Foo.java строка 42» в тексте модели.
 * Номер строки подтверждён, если он есть в совпадениях search или в прочитанном диапазоне read того же файла.
 */
final class FileRefs {
    private static final Pattern REF = Pattern.compile(
            "([\\w./-]+\\.[A-Za-z][A-Za-z0-9]{0,7})(?::(\\d+)(?:-\\d+)?|#L(\\d+)|\\s+строк[аеиуы]?\\s+(\\d+))");

    private final Map<String, Set<Integer>> searchLines = new HashMap<>();
    private final Map<String, List<int[]>> readRanges = new HashMap<>();

    void observeSearch(Map<String, Object> data) {
        if (!(data.get("matches") instanceof List<?> matches)) return;
        for (Object item : matches) {
            Map<?, ?> match = (Map<?, ?>) item;
            searchLines.computeIfAbsent(String.valueOf(match.get("path")), key -> new java.util.HashSet<>())
                    .add(((Number) match.get("line")).intValue());
        }
    }

    void observeRead(String path, int fromLine, int toLine) {
        readRanges.computeIfAbsent(path, key -> new ArrayList<>()).add(new int[]{fromLine, toLine});
    }

    /** Ссылки из текста, которые не подтверждены результатами этого запуска, в порядке появления. */
    List<String> unconfirmed(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        Matcher matcher = REF.matcher(text);
        while (matcher.find()) {
            String line = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            if (!confirmed(matcher.group(1), Integer.parseInt(line))) out.add(matcher.group(0));
        }
        return List.copyOf(out);
    }

    /** Ссылки с номером строки из текста (для тестов и журнала). */
    static List<String> references(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = REF.matcher(text == null ? "" : text);
        while (matcher.find()) out.add(matcher.group(0));
        return out;
    }

    private boolean confirmed(String file, int line) {
        Set<String> paths = new LinkedHashSet<>(searchLines.keySet());
        paths.addAll(readRanges.keySet());
        for (String path : paths) {
            if (!path.equals(file) && !path.endsWith("/" + file)) continue;
            if (searchLines.getOrDefault(path, Set.of()).contains(line)) return true;
            for (int[] range : readRanges.getOrDefault(path, List.of())) {
                if (line >= range[0] && line <= range[1]) return true;
            }
        }
        return false;
    }
}
