package com.example;

import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/** Performs one explicit synchronous monitor run through the existing Git MCP. */
public final class MonitorRunner {
    private final MonitorStore store;

    public MonitorRunner(MonitorStore store) {
        this.store = store;
    }

    public Result run(String scheduleId) {
        MonitorSchedule schedule = store.schedule(scheduleId);
        if (schedule == null) throw new MonitorException("Расписание не найдено: " + scheduleId);
        if (!schedule.enabled()) throw new MonitorException("Расписание отключено: " + scheduleId);
        MonitorStore.RuntimeLease lease = store.tryRuntimeLock(scheduleId);
        if (lease == null) {
            MonitorRun busy = runError(schedule, 0, "BUSY", "Расписание уже выполняется.");
            store.record(busy, null, null);
            MonitorSummary summary = MonitorAggregator.aggregate(schedule, store.runs(scheduleId), Instant.now());
            store.recordSummary(summary);
            return new Result(busy, summary);
        }
        try (lease) {
            MonitorRun result = null;
            GitRepositoryStatus snapshot = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                String started = Instant.now().toString();
                try {
                    McpClientComponent.ToolCallResult call = new McpClientComponent()
                            .callTool(GitMcpServer.command(Path.of(schedule.repositoryRoot())),
                                    GitMcpServer.TOOL_NAME,
                                    Map.of("repoPath", schedule.repositoryRoot()));
                    if (call.error()) throw new MonitorException(call.text());
                    snapshot = GitRepositoryStatus.fromStructured(call.structuredContent());
                    if (snapshot == null) throw new MonitorException("Git MCP не вернул snapshot.");
                    result = successRun(schedule, started, attempt, snapshot);
                    break;
                } catch (Exception e) {
                    String code = errorCode(e);
                    result = runError(schedule, attempt, code, safeMessage(e));
                    if (attempt >= 3 || !retryable(code)) break;
                    try { Thread.sleep(100L << (attempt - 1)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        result = runError(schedule, attempt, "INTERRUPTED", "Запуск прерван.");
                        break;
                    }
                }
            }
            store.record(result, snapshot, null);
            MonitorSummary summary = MonitorAggregator.aggregate(schedule, store.runs(scheduleId), Instant.now());
            store.recordSummary(summary);
            return new Result(result, summary);
        }
    }

    private static MonitorRun successRun(MonitorSchedule s, String started, int attempt,
                                         GitRepositoryStatus status) {
        return new MonitorRun(s.id(), started, Instant.now().toString(), true, attempt,
                status.branch(), status.detachedHead(), status.headCommit(), status.clean(),
                status.staged().size(), status.unstaged().size(), status.untracked().size(),
                status.conflicts().size(), null, null);
    }

    private static MonitorRun runError(MonitorSchedule s, int attempt, String code, String message) {
        String now = Instant.now().toString();
        return new MonitorRun(s.id(), now, now, false, attempt, null, false, null, false,
                0, 0, 0, 0, code, message);
    }

    private static String errorCode(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("время ожидания") || message.contains("timeout")) return "TIMEOUT";
        if (message.contains("запустить") || message.contains("подключиться")) return "MCP_START";
        if (message.contains("прерван")) return "INTERRUPTED";
        return "MCP_CALL";
    }

    private static boolean retryable(String code) {
        return "TIMEOUT".equals(code) || "MCP_START".equals(code);
    }

    private static String safeMessage(Exception e) {
        String value = e.getMessage();
        if (value == null || value.isBlank()) return "Ошибка выполнения Git MCP.";
        return value.length() > 240 ? value.substring(0, 240) + "..." : value;
    }

    public record Result(MonitorRun run, MonitorSummary summary) {
    }
}
