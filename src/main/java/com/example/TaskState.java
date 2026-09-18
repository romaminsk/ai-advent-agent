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
 * Контролируемые переходы: вход в EXECUTION требует явно утверждённого
 * плана (plan + planApproved), вход в DONE — явно зафиксированного
 * успешного результата проверки (validationResult + validationPassed).
 * Утверждение и результат фиксируются отдельными командами пользователя;
 * смена этапа и подтверждения возможны только в статусе ACTIVE. Замена
 * плана сбрасывает его утверждение; возврат VALIDATION → EXECUTION
 * сбрасывает результат проверки.
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
                        String updatedAt,
                        String plan,
                        boolean planApproved,
                        String validationResult,
                        boolean validationPassed) {

    /** Совместимый конструктор без плана, подтверждений и результата проверки. */
    public TaskState(TaskStage stage, TaskStatus status, String currentStep,
                     String expectedAction, List<String> completedSteps,
                     String description, String updatedAt) {
        this(stage, status, currentStep, expectedAction, completedSteps,
                List.of(), description, updatedAt, null, false, null, false);
    }

    /** Совместимый конструктор без плана, подтверждений и результата проверки. */
    public TaskState(TaskStage stage, TaskStatus status, String currentStep,
                     String expectedAction, List<String> completedSteps,
                     List<Invariant> localInvariants, String description,
                     String updatedAt) {
        this(stage, status, currentStep, expectedAction, completedSteps,
                localInvariants, description, updatedAt, null, false, null, false);
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
     *
     * Дополнительные правила контролируемых переходов:
     * - смена этапа возможна только в статусе ACTIVE (PAUSED/BLOCKED
     *   нельзя обойти сменой этапа, в том числе завершением задачи);
     * - вход в EXECUTION из PLANNING требует утверждённого плана
     *   (утверждение — отдельная команда пользователя);
     * - вход в DONE требует зафиксированного успешного результата
     *   проверки; возврат VALIDATION → EXECUTION сбрасывает результат.
     */
    public TaskState withStage(TaskStage target, String reason, Instant now) {
        if (target == null) {
            throw new AgentException("Не указан этап задачи: /task stage <planning|execution|validation|done>.");
        }
        if (status != TaskStatus.ACTIVE) {
            throw new AgentException("Смена этапа возможна только для активной задачи (сейчас "
                    + status.lowerName() + "). Верните задаче статус active: "
                    + (status == TaskStatus.PAUSED ? "/task resume." : "/task unblock."));
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
            if (stage == TaskStage.EXECUTION && target == TaskStage.DONE) {
                throw new AgentException("Переход execution → done запрещён: проверку (validation) "
                        + "пропускать нельзя. Ближайший допустимый переход: /task stage validation.");
            }
            throw new AgentException("Переход этапа " + stage.lowerName() + " → "
                    + target.lowerName() + " не разрешён: этапы идут только вперёд "
                    + "planning → execution → validation → done (допустим возврат "
                    + "validation → execution с причиной).");
        }
        if (stage == TaskStage.PLANNING && target == TaskStage.EXECUTION
                && !(plan != null && planApproved)) {
            throw new AgentException("Переход на этап execution требует утверждённого плана. "
                    + "Зафиксируйте и утвердите его: /task plan <текст>, затем /task approve. "
                    + "Простая смена этапа план не утверждает.");
        }
        if (stage == TaskStage.VALIDATION && target == TaskStage.DONE && !validationPassed) {
            throw new AgentException("Переход на этап done требует зафиксированного успешного "
                    + "результата проверки. Сначала фактически проверьте результат, затем "
                    + "зафиксируйте успех: /task validate pass <результат проверки>. "
                    + "Сообщение модели «всё проверено» результат не фиксирует.");
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
        // Возврат в execution сбрасывает прежнее подтверждение валидации:
        // повторный вход в validation потребует нового результата.
        boolean keepValidation = !(stage == TaskStage.VALIDATION && target == TaskStage.EXECUTION);
        return new TaskState(target, status, null, expectedAction, completed,
                localInvariants, description, now.toString(),
                plan, planApproved,
                keepValidation ? validationResult : null,
                keepValidation && validationPassed);
    }

    /** Смена статуса (ACTIVE ↔ PAUSED, ACTIVE ↔ BLOCKED; произвольные — ошибка). */
    public TaskState withStatus(TaskStatus target, Instant now) {
        if (target == TaskStatus.ACTIVE && stage == TaskStage.DONE) {
            throw new AgentException("Задача завершена: возобновить её нельзя. "
                    + "Для новой работы: /task start <описание>.");
        }
        if (target == status) {
            throw new AgentException(sameStatusMessage(target));
        }
        if (!status.canTransitionTo(target)) {
            throw new AgentException("Переход статуса " + status.lowerName() + " → "
                    + target.lowerName() + " не разрешён: пауза и блокировка снимаются "
                    + "в активное состояние (/task resume, /task unblock).");
        }
        return new TaskState(stage, target, currentStep, expectedAction,
                completedSteps, localInvariants, description, now.toString(),
                plan, planApproved, validationResult, validationPassed);
    }

    /**
     * Ближайшее допустимое действие для текущего состояния задачи — единый
     * источник подсказок при отказах (используется в сообщениях об отказах;
     * для интерфейса — {@link CommandHints#nextTaskAction}).
     */
    private String nextStepHint() {
        if (status != TaskStatus.ACTIVE) {
            return status == TaskStatus.PAUSED
                    ? "Сначала /task resume." : "Сначала снимите блокировку: /task unblock.";
        }
        return switch (stage) {
            case PLANNING -> plan == null
                    ? "Сначала зафиксируйте план: /task plan <текст>."
                    : (!planApproved
                    ? "Сначала утвердите план: /task approve."
                    : "План утверждён: переход к выполнению — /task stage execution.");
            case EXECUTION -> "Когда работа готова к проверке, переведите задачу "
                    + "на этап validation: /task stage validation.";
            case VALIDATION -> "Опишите результат проверки: "
                    + "/task validate pass|fail <результат проверки>.";
            case DONE -> "Задача завершена: для новой работы — /task start <описание>.";
        };
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
                localInvariants, description, now.toString(),
                plan, planApproved, validationResult, validationPassed);
    }

    /** Задание ожидаемого действия (что должен сделать агент или пользователь дальше). */
    public TaskState withExpectedAction(String action, Instant now) {
        if (action == null || action.isBlank()) {
            throw new AgentException("Текст ожидаемого действия обязателен: /task expect <текст>.");
        }
        return new TaskState(stage, status, currentStep, action, completedSteps,
                localInvariants, description, now.toString(),
                plan, planApproved, validationResult, validationPassed);
    }

    /** Обновление описания задачи (этап, шаги, план и статус не трогаются). */
    public TaskState withDescription(String newDescription, Instant now) {
        if (newDescription == null || newDescription.isBlank()) {
            throw new AgentException("Описание задачи не может быть пустым.");
        }
        return new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, localInvariants, newDescription, now.toString(),
                plan, planApproved, validationResult, validationPassed);
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
                completedSteps, copy, description, now.toString(),
                plan, planApproved, validationResult, validationPassed);
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
                completedSteps, remaining, description, now.toString(),
                plan, planApproved, validationResult, validationPassed);
        return new TaskInvariantRemove(updated, removedInvariant);
    }

    /** Очистка всех локальных инвариантов (остальное состояние задачи не трогается). */
    public TaskState withoutLocalInvariants(Instant now) {
        return new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, List.of(), description, now.toString(),
                plan, planApproved, validationResult, validationPassed);
    }

    // ================= План и его утверждение =================

    /**
     * Фиксирует или заменяет план (/task plan <текст>) — только в статусе
     * ACTIVE и только на этапе PLANNING: после выхода из planning план
     * не меняется, чтобы утверждённый план нельзя было незаметно подменить,
     * сохранив старое подтверждение. Замена текста сбрасывает утверждение;
     * повторная установка того же текста ничего не меняет (утверждение
     * сохраняется).
     */
    public TaskState withPlan(String newPlan, Instant now) {
        if (status != TaskStatus.ACTIVE) {
            throw new AgentException("Фиксация плана возможна только для активной задачи "
                    + "(сейчас " + status.lowerName() + "). Верните задаче статус active: "
                    + (status == TaskStatus.PAUSED ? "/task resume." : "/task unblock."));
        }
        if (stage != TaskStage.PLANNING) {
            throw new AgentException("План фиксируется на этапе planning (сейчас "
                    + stage.lowerName() + "). После выхода из planning план не меняется — "
                    + "иначе утверждённый план можно было бы подменить, сохранив утверждение.");
        }
        if (newPlan == null || newPlan.isBlank()) {
            throw new AgentException("Текст плана обязателен: /task plan <текст>.");
        }
        String trimmed = newPlan.trim();
        if (trimmed.equals(plan)) {
            throw new AgentException("План уже зафиксирован с этим текстом"
                    + (planApproved ? " и утверждён." : " (утверждение: /task approve)."));
        }
        return new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, localInvariants, description, now.toString(),
                trimmed, false, validationResult, validationPassed);
    }

    /**
     * Явное утверждение зафиксированного плана (/task approve) — только
     * в статусе ACTIVE и только на этапе PLANNING. Утверждение относится
     * к конкретному зафиксированному плану: без плана отклоняется;
     * смена этапа план не утверждает.
     */
    public TaskState approvePlan(Instant now) {
        if (status != TaskStatus.ACTIVE) {
            throw new AgentException("Утверждение плана возможно только для активной задачи "
                    + "(сейчас " + status.lowerName() + "). Верните задаче статус active: "
                    + (status == TaskStatus.PAUSED ? "/task resume." : "/task unblock."));
        }
        if (stage != TaskStage.PLANNING) {
            throw new AgentException("Утверждение плана — на этапе planning (сейчас "
                    + stage.lowerName() + ").");
        }
        if (plan == null) {
            throw new AgentException("План не зафиксирован: /task plan <текст>. "
                    + "Утверждать можно только конкретный план.");
        }
        if (planApproved) {
            throw new AgentException("План уже утверждён. Переход к выполнению: "
                    + "/task stage execution.");
        }
        return new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, localInvariants, description, now.toString(),
                plan, true, validationResult, validationPassed);
    }

    // ================= Результат проверки (валидация) =================

    /**
     * Фиксация результата проверки (/task validate pass|fail <результат>) —
     * только в статусе ACTIVE и только на этапе VALIDATION. Вход на этап
     * validation сам по себе ничего не фиксирует. Пустой результат не
     * принимается; новый результат заменяет прежний (pass → fail снова
     * запрещает DONE).
     */
    public TaskState withValidationResult(boolean passed, String result, Instant now) {
        if (status != TaskStatus.ACTIVE) {
            throw new AgentException("Фиксация результата проверки возможна только для "
                    + "активной задачи (сейчас " + status.lowerName() + "). Верните задаче "
                    + "статус active: "
                    + (status == TaskStatus.PAUSED ? "/task resume." : "/task unblock."));
        }
        if (stage != TaskStage.VALIDATION) {
            throw new AgentException("Результат проверки фиксируется только на этапе "
                    + "validation (сейчас " + stage.lowerName() + "). " + nextStepHint());
        }
        if (result == null || result.isBlank()) {
            throw new AgentException("Текст результата проверки обязателен: "
                    + "/task validate " + (passed ? "pass" : "fail") + " <результат проверки>.");
        }
        return new TaskState(stage, status, currentStep, expectedAction,
                completedSteps, localInvariants, description, now.toString(),
                plan, planApproved, result.trim(), passed);
    }
}
