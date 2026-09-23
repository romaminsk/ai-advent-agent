package com.example;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persisted interval configuration for one monitored repository. */
public record MonitorSchedule(String id, String repositoryRoot, long intervalSeconds,
                              long summaryIntervalSeconds, boolean enabled,
                              long missedCount, String nextRunAt,
                              String createdAt, String updatedAt) {
    private static final Pattern INTERVAL = Pattern.compile("(\\d+)([smh])");

    public MonitorSchedule {
        if (id == null || id.isBlank() || repositoryRoot == null || repositoryRoot.isBlank()) {
            throw new IllegalArgumentException("id и repositoryRoot обязательны");
        }
        if (intervalSeconds < 30 || intervalSeconds > 86_400
                || summaryIntervalSeconds < 60 || summaryIntervalSeconds > 86_400) {
            throw new IllegalArgumentException("интервал вне допустимого диапазона");
        }
    }

    public static MonitorSchedule create(String repositoryRoot, Duration interval,
                                         Duration summaryInterval) {
        String now = Instant.now().toString();
        return new MonitorSchedule("mon-" + UUID.randomUUID(), repositoryRoot,
                interval.toSeconds(), summaryInterval.toSeconds(), true, 0, null, now, now);
    }

    public MonitorSchedule withEnabled(boolean value) {
        return new MonitorSchedule(id, repositoryRoot, intervalSeconds, summaryIntervalSeconds,
                value, missedCount, nextRunAt, createdAt, Instant.now().toString());
    }

    public static Duration parseInterval(String raw, String field, long minSeconds, long maxSeconds) {
        if (raw == null || raw.isBlank()) {
            throw new MonitorException(field + " обязателен в формате <число>s|m|h.");
        }
        Matcher matcher = INTERVAL.matcher(raw.trim().toLowerCase(java.util.Locale.ROOT));
        if (!matcher.matches()) {
            throw new MonitorException(field + " должен иметь формат <число>s|m|h.");
        }
        long number;
        try {
            number = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new MonitorException(field + " слишком велик.");
        }
        long multiplier = switch (matcher.group(2)) {
            case "s" -> 1;
            case "m" -> 60;
            case "h" -> 3600;
            default -> throw new MonitorException("Неверная единица интервала.");
        };
        if (number > maxSeconds / multiplier || number * multiplier < minSeconds) {
            throw new MonitorException(field + " должен быть от " + minSeconds + " до "
                    + maxSeconds + " секунд.");
        }
        return Duration.ofSeconds(number * multiplier);
    }
}
