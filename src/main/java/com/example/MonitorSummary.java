package com.example;

import java.util.List;

/** Deterministic aggregate over a schedule's recent runs. */
public record MonitorSummary(String scheduleId, String windowStart, String windowEnd,
                             int successCount, int failureCount, long missedCount,
                             String lastSuccessAt, String lastErrorAt, Boolean clean,
                             int stagedCount, int unstagedCount, int untrackedCount,
                             int conflictsCount, Integer stagedDelta, Integer unstagedDelta,
                             Integer untrackedDelta, Integer conflictsDelta,
                             List<String> branchChanges, List<String> headChanges,
                             boolean detachedHead) {
    public MonitorSummary {
        branchChanges = List.copyOf(branchChanges);
        headChanges = List.copyOf(headChanges);
    }
}
