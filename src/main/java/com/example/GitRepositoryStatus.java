package com.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Структурированный снимок состояния рабочего Git-репозитория. */
public record GitRepositoryStatus(
        String repositoryRoot,
        String branch,
        boolean detachedHead,
        String headCommit,
        boolean clean,
        List<String> staged,
        List<String> unstaged,
        List<String> untracked,
        List<String> conflicts) {

    public GitRepositoryStatus {
        staged = List.copyOf(staged);
        unstaged = List.copyOf(unstaged);
        untracked = List.copyOf(untracked);
        conflicts = List.copyOf(conflicts);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repositoryRoot", repositoryRoot);
        result.put("branch", branch);
        result.put("detachedHead", detachedHead);
        result.put("headCommit", headCommit);
        result.put("clean", clean);
        result.put("staged", staged);
        result.put("unstaged", unstaged);
        result.put("untracked", untracked);
        result.put("conflicts", conflicts);
        return result;
    }

    static GitRepositoryStatus fromStructured(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new GitRepositoryStatus(
                string(map.get("repositoryRoot")),
                nullableString(map.get("branch")),
                Boolean.TRUE.equals(map.get("detachedHead")),
                nullableString(map.get("headCommit")),
                Boolean.TRUE.equals(map.get("clean")),
                strings(map.get("staged")), strings(map.get("unstaged")),
                strings(map.get("untracked")), strings(map.get("conflicts")));
    }

    static String formatForTerminal(GitRepositoryStatus status) {
        StringBuilder output = new StringBuilder(status.clean()
                ? "✓ Git MCP: состояние получено."
                : "! Git MCP: состояние получено, есть изменения.")
                .append("\n  репозиторий: ").append(safePath(status.repositoryRoot()));
        if (status.detachedHead()) {
            output.append("\n  HEAD: detached");
        } else {
            output.append("\n  ветка: ").append(status.branch() == null ? "неизвестна" : safePath(status.branch()));
        }
        output.append("\n  коммит: ").append(status.headCommit() == null
                ? "коммитов пока нет" : status.headCommit());
        if (status.clean()) {
            output.append("\n  состояние: чистое");
        } else {
            output.append("\n  состояние: есть изменения");
            appendPaths(output, "staged", status.staged());
            appendPaths(output, "unstaged", status.unstaged());
            appendPaths(output, "untracked", status.untracked());
            appendPaths(output, "conflicts", status.conflicts());
        }
        output.append("\n  Снимок сохранён в памяти сеанса. Объяснить: /mcp explain");
        return output.toString();
    }

    private static void appendPaths(StringBuilder output, String title, List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        output.append("\n  ").append(title).append(" (").append(paths.size()).append("):");
        for (String path : paths) {
            output.append("\n    - ").append(safePath(path));
        }
    }

    private static String safePath(String path) {
        return AnsiSanitizer.sanitize(path == null ? "" : path)
                .replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String string(Object value) {
        String result = nullableString(value);
        return result == null ? "" : result;
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
