package com.example;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Формализованное состояние задачи (Task State Machine) — отдельная сущность
 * поверх рабочей памяти: не просто текст задачи, а структура
 * «этап + текущий шаг + ожидаемое действие».
 *
 * Два ортогональных измерения конечного автомата:
 * - этап ({@link TaskStage}): PLANNING → EXECUTION → VALIDATION → DONE,
 *   только вперёд, плюс явный возврат VALIDATION → EXECUTION с причиной;
 *   PAUSED/BLOCKED в эту цепочку не входят — это не фазы работы;
 * - статус ({@link TaskStatus}): ACTIVE ↔ PAUSED, ACTIVE ↔ BLOCKED —
 *   состояние ожидания, возможное на любом этапе.
 *
 * Неизменяемый record: изменяющие методы возвращают копию с новой меткой
 * updatedAt. Хранится в рабочей памяти ({@link WorkingMemory}): состояние
 * задачи — это текущая задача, живёт в рамках сессии, очищается /clear
 * и /task clear, но переживает пауза и продолжение внутри сессии.
 *
 * Локальные инварианты (localInvariants) — жёсткие рамки, привязанные к
 * этой задаче: тот же тип {@link Invariant}, что и глобальные, но живут
 * ровно столько, сколько задача — они часть сессионного состояния
 * TaskState, а НЕ InvariantStore (глобальное, персистентное). Локальные
 * уточняют, но не отменяют глобальные ({@link ContextBuilder}).
 */
public record TaskState(TaskStage stage,
                        TaskStatus status,
                        String currentStep,
                        String expectedAction,
                        List<String> completedSteps,
                        List<Invariant> localInvariants,
                        String description,
                        String updatedAt) {

    /** Совместимый конструктор без локальных инвариантов. */
    public TaskState(TaskStage stage, TaskStatus status, String currentStep,
                     String expectedAction, List<String> completedSteps,
                     String description, String updatedAt) {
        this(stage, status, currentStep, expectedAction, completedSteps,
                List.of(), description, updatedAt);
    }

    public TaskState {
        if (stage == null || status == null) {
            throw new IllegalArgumentException("Этап и статус задачи обязательны.");
        }
        currentStep = blankToNull(currentStep);
        expectedAction = blankToNull(expectedAction);
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        localInvariants = localInvariants == null
                ? List.of() : List.copyOf(localInvariants);
        description = blankToNull(description);
        if (updatedAt == null || updatedAt.isBlank()) {
            throw new IllegalArgumentException("Метка времени задачи обязательна.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Начало задачи: этап PLANNING, статус ACTIVE, текущий шаг —
     * «сформулировать план», ожидаемое действие — «агент предлагает план».
     */
    public static TaskState start(String description, Instant now) {
        if (description == null || description.isBlank()) {
            throw new AgentException("Описание задачи обязательно: /task start <описание>.");
        }
        return new TaskState(TaskStage.PLANNING, TaskStatus.ACTIVE,
                "сформулировать план", "агент предлагает план",
                List.of(), description, now.toString());
    }

    /**
     * Перевод задачи на другой этап. Разрешены только переходы вперёд
     * по цепочке и возврат VALIDATION → EXECUTION — но обратный переход
     * требует явной причины (ошибка проверки), иначе он теряет смысл.
     * Сделанный шаг считается выполненным и переносится в completedSteps.
     */
    public TaskState withStage(TaskStage target, String reason, Instant now) {
        if (target == null) {
            throw new AgentException("Не указан этап задачи: /task stage <planning|execution|validation|done>.");
        }
        if (target == stage) {
            throw new AgentException("Задача уже на этапе " + stage.lowerName() + ".");
        }
        if (!stage.canTransitionTo(target)) {
            if (stage == TaskStage.DONE) {
                throw new AgentException("Переход done → " + target.lowerName()
                        + " запрещён: завершённая задача не продолжается — начните новую: "
                        + "/task start <описание>.");
            }
            throw new AgentException("Переход этапа " + stage.lowerName() + " → "
                    + target.lowerName() + " не разрешён: этапы идут только вперёд "
                    + "planning → execution → validation → done (допустим возврат "
                    + "validation → execution с причиной).");
        }
        if (stage.isBackwardTransitionTo(target)) {
            if (reason == null || reason.isBlank()) {
                throw new AgentException("Возврат на этап execution требует причины: "
                        + "/task stage execution <причина> (что именно не прошла проверка).");
            }
        }
        List<String> completed = new ArrayList<>(completedSteps);
        if (currentStep != null) {
            completed.add(currentStep);
        }
        return new TaskState(target, status, null, expectedAction, completed,
                localInvariants, description, now.toString());
    }

    /** Смена статуса (ACTIVE ↔ PAUSED, ACTIVE ↔ BLOCKED; произвольные — ошибка). */
    public TaskState withStatus(TaskStatus target, Instant now) {
        if (target == status) {
            throw new AgentException(sameStatusMessage(target));
        }
        if (!status.canTransitionTo(target)) {
            throw new AgentException("Переход статуса " + status.lowerName() + " → "
                    + target.lowerName() + " не разрешён: пауза и блокировка снимаются "
                    + "в активное состояние (/task resume, /task unblock).");
        }
        return new TaskState(stage, target, currentStep, expectedAction,
                completedSteps, localInvariants, description, now.toString());
    }

    private static String sameStatusMessage(TaskStatus target) {
        return switch (target) {
            case ACTIVE -> "Задача уже активна.";
            case PAUSED -> "Задача уже на паузе. Продолжить: /task resume.";
            case BLOCKED -> "Задача уже помечена как blocked (/task unblock — снять).";
        };
    }

    /**
     * Задание нового текущего шага: прежний текущий шаг (если был) считается
     * выполненным и переносится в completedSteps — чтобы после resume
     * не повторять пройденное.
     */
    public TaskState withStep(String step, Instant now) {
        if (step == null || step.isBlank()) {
            throw new AgentException("Текст шага обязателен: /task step <текст>.");
        }
        List<String> completed = new ArrayList<>(completedSteps);
        String newStep = step.trim();
        if (currentStep != null && !currentStep.equals(newStep)
                && !completed.contains(currentStep)) {
            completed.add(currentStep);
        }
        return new TaskState(stage, status, newStep, expectedAction, completed,
                localInvariants, description, now.toString());
    }

    /** Задание ожидаемого действия (что должен сделать агент или пользователь дальше). */
    public TaskState withExpectedAction(String action, Instant now) {
        if (action == null || action.isBlank()) {
            throw new AgentException("Текст ожидаемого действия обязателен: /task expect <текст>.");
        }
        return new TaskState(stage, status, currentStep, action, completedSteps,
                localInvariants, description, now.toString());
    }

    /** Обновление описания задачи (этап, шаги и статус не трогаются). */
    public TaskState withDescription(String newDescription, Instant now) {
        if (newDescription == null || newDescription.isBlank()) {
            throw new AgentException("Описание задачи не может быть пустым.");
        }
        return new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, localInvariants, newDescription, now.toString());
    }

    // ================= Локальные инварианты задачи =================

    /** Неизменяемое представление локальных инвариантов текущей задачи. */
    public List<Invariant> localInvariantsView() {
        return localInvariants;
    }

    /** Копия с добавленным локальным инвариантом (/task invariant add). */
    public TaskState withLocalInvariant(Invariant invariant, Instant now) {
        if (invariant == null) {
            throw new AgentException("Пустой локальный инвариант.");
        }
        List<Invariant> copy = new ArrayList<>(localInvariants);
        copy.add(invariant);
        return new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, copy, description, now.toString());
    }

    /** Результат /task invariant remove: новое состояние и удалённая запись. */
    record TaskInvariantRemove(TaskState state, Invariant removed) {
    }

    /**
     * Копия без локального инварианта по его id; removed — удалённый
     * инвариант, null — не найден (этап, шаги и статус не трогаются).
     */
    TaskInvariantRemove withoutLocalInvariant(String id, Instant now) {
        Invariant removedInvariant = null;
        List<Invariant> remaining = new ArrayList<>();
        for (Invariant invariant : localInvariants) {
            if (removedInvariant == null && invariant.id().equals(id)) {
                removedInvariant = invariant;
                continue;
            }
            remaining.add(invariant);
        }
        if (removedInvariant == null) {
            return new TaskInvariantRemove(this, null);
        }
        TaskState updated = new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, remaining, description, now.toString());
        return new TaskInvariantRemove(updated, removedInvariant);
    }

    /** Очистка всех локальных инвариантов (остальное состояние задачи не трогается). */
    public TaskState withoutLocalInvariants(Instant now) {
        return new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, List.of(), description, now.toString());
    }
}
