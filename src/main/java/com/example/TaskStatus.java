package com.example;

import java.util.Locale;

/**
 * Статус задачи — ВТОРОЕ (ортогональное) измерение конечного автомата
 * задачи: состояние ожидания, возможное на любом этапе.
 *
 * PAUSED/BLOCKED — это не фазы работы, а флаги поверх этапа: смешивать их
 * в одну цепочку с этапами нельзя. ACTIVE ↔ PAUSED и ACTIVE ↔ BLOCKED
 * переходят свободно, PAUSED ↔ BLOCKED напрямую нельзя (только через ACTIVE).
 */
public enum TaskStatus {
    ACTIVE,
    PAUSED,
    BLOCKED;

    /** Допустимый переход статуса: из ACTIVE в PAUSED/BLOCKED и обратно. */
    public boolean canTransitionTo(TaskStatus target) {
        return switch (this) {
            case ACTIVE -> target == PAUSED || target == BLOCKED;
            case PAUSED -> target == ACTIVE;
            case BLOCKED -> target == ACTIVE;
        };
    }

    /** Имя статуса в нижнем регистре (для сообщений интерфейса). */
    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Разбор имени статуса; нестыковка — понятная ошибка. */
    public static TaskStatus parse(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (TaskStatus status : values()) {
                if (status.lowerName().equals(normalized)) {
                    return status;
                }
            }
        }
        throw new AgentException("Неизвестный статус задачи: «" + value + "». "
                + "Допустимые статусы: active, paused, blocked.");
    }
}
