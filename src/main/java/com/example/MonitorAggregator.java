package com.example;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds a deterministic, LLM-free summary from persisted run records. */
public final class MonitorAggregator {
    private MonitorAggregator() {
    }

    public static MonitorSummary aggregate(MonitorSchedule schedule, List<MonitorRun> runs,
                                            Instant now) {
        Instant windowStart = now.minusSeconds(schedule.summaryIntervalSeconds());
        List<MonitorRun> window = runs.stream().filter(run -> at(run.startedAt()) != null
                && !at(run.startedAt()).isBefore(windowStart)).toList();
        List<MonitorRun> successes = runs.stream().filter(MonitorRun::success).toList();
        MonitorRun last = successes.isEmpty() ? null : successes.get(successes.size() - 1);
        MonitorRun previous = successes.size() < 2 ? null : successes.get(successes.size() - 2);
        Set<String> branchChanges = new LinkedHashSet<>();
        Set<String> headChanges = new LinkedHashSet<>();
        MonitorRun previousWindow = null;
        for (MonitorRun run : window) {
            if (!run.success()) continue;
            if (previousWindow != null && !java.util.Objects.equals(previousWindow.branch(), run.branch())) {
                branchChanges.add(value(previousWindow.branch()) + " -> " + value(run.branch()));
            }
            if (previousWindow != null && !java.util.Objects.equals(previousWindow.headCommit(), run.headCommit())) {
                headChanges.add(value(previousWindow.headCommit()) + " -> " + value(run.headCommit()));
            }
            previousWindow = run;
        }
        int successCount = (int) window.stream().filter(MonitorRun::success).count();
        int failureCount = window.size() - successCount;
        return new MonitorSummary(schedule.id(), windowStart.toString(), now.toString(),
                successCount, failureCount, schedule.missedCount(),
                last == null ? null : last.finishedAt(), lastError(window),
                last == null ? null : last.clean(),
                last == null ? 0 : last.stagedCount(), last == null ? 0 : last.unstagedCount(),
                last == null ? 0 : last.untrackedCount(), last == null ? 0 : last.conflictsCount(),
                delta(last, previous, 0), delta(last, previous, 1),
                delta(last, previous, 2), delta(last, previous, 3),
                List.copyOf(branchChanges), List.copyOf(headChanges),
                last != null && last.detachedHead());
    }

    private static Integer delta(MonitorRun current, MonitorRun previous, int field) {
        if (current == null || previous == null) return null;
        int now = count(current, field);
        return now - count(previous, field);
    }

    private static int count(MonitorRun run, int field) {
        return switch (field) {
            case 0 -> run.stagedCount();
            case 1 -> run.unstagedCount();
            case 2 -> run.untrackedCount();
            default -> run.conflictsCount();
        };
    }

    private static String lastError(List<MonitorRun> runs) {
        return runs.stream().filter(run -> !run.success())
                .reduce((first, second) -> second)
                .map(MonitorRun::finishedAt).orElse(null);
    }

    private static Instant at(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (RuntimeException e) { return null; }
    }

    private static String value(String value) {
        return value == null ? "нет" : value;
    }
}
