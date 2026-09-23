package com.example;

/** One bounded, secret-free execution record. */
public record MonitorRun(String scheduleId, String startedAt, String finishedAt,
                         boolean success, int attempt, String branch, boolean detachedHead,
                         String headCommit, boolean clean, int stagedCount, int unstagedCount,
                         int untrackedCount, int conflictsCount, String errorCode,
                         String errorMessage) {
}
