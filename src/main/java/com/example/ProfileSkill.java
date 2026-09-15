package com.example;

import java.time.Instant;

/**
 * Скилл профиля пользователя: именованный блок инструкций для конкретной
 * доменной задачи. Скилл описывает, ЧТО агент должен сделать при
 * определённом типе запроса (например «карточка фичи» — как оформлять
 * новую фичу: название, цель, критерии приёмки, шаги).
 * Сериализуется в JSON файла профиля; пустые имя и инструкции недопустимы.
 */
public record ProfileSkill(String name, String instructions,
                           String createdAt, String updatedAt) {

    public ProfileSkill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("имя скилла обязательно");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("инструкции скилла обязательны");
        }
        if (createdAt == null || createdAt.isBlank()
                || updatedAt == null || updatedAt.isBlank()) {
            throw new IllegalArgumentException("метки времени скилла обязательны");
        }
    }

    /** Новый скилл с заданным временем создания; updatedAt = createdAt. */
    public static ProfileSkill create(String name, String instructions, Instant now) {
        String timestamp = now.toString();
        return new ProfileSkill(name.trim(), instructions.trim(), timestamp, timestamp);
    }

    /** Инструкции с обновлением метки изменения; createdAt сохраняется. */
    public ProfileSkill withInstructions(String newInstructions, Instant now) {
        return new ProfileSkill(name, newInstructions.trim(), createdAt, now.toString());
    }
}
