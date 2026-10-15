package com.example;

import java.util.ArrayList;
import java.util.List;

/** Unified diff по строкам с тремя строками контекста: префикс/суффикс и LCS по середине. */
final class LineDiff {
    static final int CONTEXT = 3;
    private static final long MAX_LCS_CELLS = 4_000_000L;

    record Result(String diff, int added, int removed) {
        boolean changed() { return added > 0 || removed > 0; }
    }

    private enum Kind { EQUAL, DELETE, INSERT }

    private record Op(Kind kind, String text) {
        char marker() { return kind == Kind.EQUAL ? ' ' : kind == Kind.DELETE ? '-' : '+'; }
    }

    private LineDiff() {
    }

    static Result diff(String path, String oldText, String newText, boolean newFile) {
        List<String> oldLines = lines(oldText);
        List<String> newLines = lines(newText);
        List<Op> ops = operations(oldLines, newLines);
        int added = 0;
        int removed = 0;
        for (Op op : ops) {
            if (op.kind() == Kind.INSERT) added++;
            if (op.kind() == Kind.DELETE) removed++;
        }
        if (added == 0 && removed == 0) return new Result("", 0, 0);
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(newFile ? "/dev/null" : "a/" + path).append('\n');
        out.append("+++ b/").append(path).append('\n');
        int[] oldBefore = new int[ops.size() + 1];
        int[] newBefore = new int[ops.size() + 1];
        for (int i = 0; i < ops.size(); i++) {
            Kind kind = ops.get(i).kind();
            oldBefore[i + 1] = oldBefore[i] + (kind == Kind.INSERT ? 0 : 1);
            newBefore[i + 1] = newBefore[i] + (kind == Kind.DELETE ? 0 : 1);
        }
        for (int[] hunk : hunks(ops)) {
            int start = hunk[0];
            int end = hunk[1];
            int oldCount = 0;
            int newCount = 0;
            for (int i = start; i <= end; i++) {
                if (ops.get(i).kind() != Kind.INSERT) oldCount++;
                if (ops.get(i).kind() != Kind.DELETE) newCount++;
            }
            int oldStart = oldCount == 0 ? oldBefore[start] : oldBefore[start] + 1;
            int newStart = newCount == 0 ? newBefore[start] : newBefore[start] + 1;
            out.append("@@ -").append(oldStart).append(oldCount == 1 ? "" : "," + oldCount)
                    .append(" +").append(newStart).append(newCount == 1 ? "" : "," + newCount)
                    .append(" @@\n");
            for (int i = start; i <= end; i++) {
                Op op = ops.get(i);
                out.append(op.marker()).append(op.text()).append('\n');
            }
        }
        return new Result(out.toString(), added, removed);
    }

    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;
        String[] parts = text.split("\n", -1);
        int count = text.endsWith("\n") ? parts.length - 1 : parts.length;
        for (int i = 0; i < count; i++) result.add(parts[i]);
        return result;
    }

    private static List<Op> operations(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) ops.add(new Op(Kind.EQUAL, a.get(i)));
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);
        if ((long) midA.size() * midB.size() > MAX_LCS_CELLS) {
            for (String line : midA) ops.add(new Op(Kind.DELETE, line));
            for (String line : midB) ops.add(new Op(Kind.INSERT, line));
        } else {
            lcs(midA, midB, ops);
        }
        for (int i = a.size() - suffix; i < a.size(); i++) ops.add(new Op(Kind.EQUAL, a.get(i)));
        return ops;
    }

    private static void lcs(List<String> a, List<String> b, List<Op> ops) {
        int n = a.size();
        int m = b.size();
        int[][] table = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                table[i][j] = a.get(i).equals(b.get(j)) ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(new Op(Kind.EQUAL, a.get(i)));
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                ops.add(new Op(Kind.DELETE, a.get(i++)));
            } else {
                ops.add(new Op(Kind.INSERT, b.get(j++)));
            }
        }
        while (i < n) ops.add(new Op(Kind.DELETE, a.get(i++)));
        while (j < m) ops.add(new Op(Kind.INSERT, b.get(j++)));
    }

    private static List<int[]> hunks(List<Op> ops) {
        List<int[]> hunks = new ArrayList<>();
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind() == Kind.EQUAL) {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int last = i;
            int k = i;
            while (k < ops.size()) {
                if (ops.get(k).kind() != Kind.EQUAL) {
                    last = k;
                    k++;
                    continue;
                }
                int e = k;
                while (e < ops.size() && ops.get(e).kind() == Kind.EQUAL) e++;
                if (e < ops.size() && e - k <= 2 * CONTEXT) {
                    k = e;
                    continue;
                }
                break;
            }
            int end = Math.min(ops.size() - 1, last + CONTEXT);
            hunks.add(new int[]{start, end});
            i = end + 1;
        }
        return hunks;
    }
}
