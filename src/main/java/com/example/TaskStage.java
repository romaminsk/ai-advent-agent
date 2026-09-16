package com.example;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Этап задачи — ОДНО из двух ортогональных измерений конечного автомата
 * задачи (второе — {@link TaskStatus}): фаза работы над задачей.
 *
 * Переходы — только вперёд по цепочке PLANNING → EXECUTION → VALIDATION → DONE,
 * плюс одно осознанное исключение: возврат VALIDATION → EXECUTION при ошибке
 * проверки (только явной командой с причиной). Произвольные прыжки запрещены:
 * DONE → PLANNING — это не продолжение старой задачи, а новая задача.
 */
public enum TaskStage {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE;

    /** Допустимый переход из текущего этапа в целевой; иначе — ошибка. */
    public boolean canTransitionTo(TaskStage target) {
        return switch (this) {
            case PLANNING -> target == EXECUTION;
            case EXECUTION -> target == VALIDATION;
            case VALIDATION -> target == DONE || target == EXECUTION;
            case DONE -> false; // завершённая задача не продолжается — это новая задача
        };
    }

    /** Возвратный переход (VALIDATION → EXECUTION): требует явной причины. */
    public boolean isBackwardTransitionTo(TaskStage target) {
        return this == VALIDATION && target == EXECUTION;
    }

    /** Имя этапа в нижнем регистре (для сообщений интерфейса). */
    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Разбор имени этапа; нестыковка — понятная ошибка. */
    public static TaskStage parse(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (TaskStage stage : values()) {
                if (stage.lowerName().equals(normalized)) {
                    return stage;
                }
            }
        }
        throw new AgentException("Неизвестный этап задачи: «" + value + "». "
                + "Допустимые этапы: planning, execution, validation, done.");
    }
}
