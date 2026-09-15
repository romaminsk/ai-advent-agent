package com.example;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Профиль пользователя — отдельная сущность поверх модели памяти:
 * как обращаться к пользователю, его предпочтения по стилю и формату
 * ответов, ограничения и (расширенно) пайплайны скиллов под задачи.
 *
 * Отличие от долговременной памяти ({@link MemoryStore}): память —
 * накопитель независимых записей «ключ: значение», растущий со временем;
 * профиль — один объект с фиксированными полями, один на пользователя,
 * описывающий правила работы с ним. Профиль не растёт как список записей:
 * каждое поле имеет одно текущее значение (последнее заданное).
 *
 * Неизменяемый по смыслу record: изменяющие методы возвращают копию
 * с новой меткой updatedAt. Сериализуется в JSON файла профиля
 * ({@link ProfileStore}); поля name/style/format/constraints/skills/pipelines
 * необязательны, метки времени обязательны. Пустой профиль (все поля
 * не заданы) в запрос к модели не подставляется.
 */
public record UserProfile(String name, String style, String format,
                          List<String> constraints,
                          LinkedHashMap<String, ProfileSkill> skills,
                          LinkedHashMap<String, List<String>> pipelines,
                          String createdAt, String updatedAt) {

    public UserProfile {
        name = blankToNull(name);
        style = blankToNull(style);
        format = blankToNull(format);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        skills = skills == null ? new LinkedHashMap<>() : new LinkedHashMap<>(skills);
        pipelines = pipelines == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(pipelines);
        if (createdAt == null || createdAt.isBlank()
                || updatedAt == null || updatedAt.isBlank()) {
            throw new IllegalArgumentException("метки времени профиля обязательны");
        }
    }

    /** Пустой профиль: все поля не заданы, метки — момент создания. */
    public static UserProfile empty() {
        return empty(Instant.now());
    }

    /** Пустой профиль с заданным временем создания (для тестов и чтения). */
    public static UserProfile empty(Instant now) {
        String timestamp = now.toString();
        return new UserProfile(null, null, null, List.of(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), timestamp, timestamp);
    }

    /** true, если все содержательные поля не заданы. */
    public boolean isEmpty() {
        return name == null && style == null && format == null
                && constraints.isEmpty() && skills.isEmpty() && pipelines.isEmpty();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private UserProfile updated(String name, String style, String format,
                                List<String> constraints,
                                LinkedHashMap<String, ProfileSkill> skills,
                                LinkedHashMap<String, List<String>> pipelines, Instant now) {
        return new UserProfile(name, style, format, constraints, skills, pipelines,
                createdAt, now.toString());
    }

    /** Копия с новым обращением (/profile name). */
    public UserProfile withName(String newName, Instant now) {
        return updated(newName, style, format, constraints, skills, pipelines, now);
    }

    /** Копия с новым стилем (/profile style). */
    public UserProfile withStyle(String newStyle, Instant now) {
        return updated(name, newStyle, format, constraints, skills, pipelines, now);
    }

    /** Копия с новым форматом (/profile format). */
    public UserProfile withFormat(String newFormat, Instant now) {
        return updated(name, style, newFormat, constraints, skills, pipelines, now);
    }

    /** Копия с добавленным ограничением (/profile constraint); дубликат не добавляется. */
    public UserProfile withConstraint(String constraint, Instant now) {
        List<String> current = new ArrayList<>(constraints);
        String normalized = constraint.trim();
        if (!current.contains(normalized)) {
            current.add(normalized);
        }
        return updated(name, style, format, current, skills, pipelines, now);
    }

    /** Копия без всех ограничений (/profile constraint clear). */
    public UserProfile withoutConstraints(Instant now) {
        return updated(name, style, format, List.of(), skills, pipelines, now);
    }

    /** Копия со скиллом: новый — добавлен, существующий — обновлён. */
    public UserProfile withSkill(ProfileSkill skill, Instant now) {
        LinkedHashMap<String, ProfileSkill> copy = new LinkedHashMap<>(skills);
        copy.put(skill.name(), skill);
        return updated(name, style, format, constraints, copy, pipelines, now);
    }

    /** Копия без скилла; true — скилл был и удалён. */
    public boolean removeSkill(String skillName) {
        return skills.remove(skillName.trim()) != null
                || skills.remove(skillName.trim().toLowerCase(java.util.Locale.ROOT)) != null;
    }

    /** Копия с пайплайном для триггера (порядок скиллов сохраняется). */
    public UserProfile withPipeline(String trigger, List<String> skillNames, Instant now) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>(pipelines);
        copy.put(trigger.trim(), List.copyOf(skillNames));
        return updated(name, style, format, constraints, skills, copy, now);
    }

    /** Копия без всех пайплайнов. */
    public UserProfile withoutPipelines(Instant now) {
        return updated(name, style, format, constraints, skills,
                new LinkedHashMap<>(), now);
    }

    /** Неизменяемое представление скиллов (порядок сохранения). */
    public LinkedHashMap<String, ProfileSkill> skillsView() {
        return new LinkedHashMap<>(skills);
    }

    /** Неизменяемое представление пайплайнов (порядок сохранения). */
    public LinkedHashMap<String, List<String>> pipelinesView() {
        return new LinkedHashMap<>(pipelines);
    }

    /** Скилл по имени без учёта регистра; null — не найден. */
    public ProfileSkill skill(String skillName) {
        for (String existing : skills.keySet()) {
            if (existing.equalsIgnoreCase(skillName.trim())) {
                return skills.get(existing);
            }
        }
        return null;
    }

    /** Удаление скилла по имени без учёта регистра; true — удалён. */
    public boolean skillRemoved(String skillName) {
        String normalized = skillName.trim().toLowerCase(java.util.Locale.ROOT);
        String found = null;
        for (String existing : skills.keySet()) {
            if (existing.toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                found = existing;
                break;
            }
        }
        if (found == null) {
            return false;
        }
        skills.remove(found);
        // Пайплайны, использующие удалённый скилл, теряют его из списка.
        if (!pipelines.isEmpty()) {
            LinkedHashMap<String, List<String>> remaining = new LinkedHashMap<>();
            for (var entry : pipelines.entrySet()) {
                List<String> names = new ArrayList<>(entry.getValue());
                names.removeIf(n -> n.trim().toLowerCase(java.util.Locale.ROOT)
                        .equals(normalized));
                if (names.isEmpty()) {
                    continue; // пайплайн без скиллов не имеет смысла
                }
                remaining.put(entry.getKey(), names);
            }
            pipelines.clear();
            pipelines.putAll(remaining);
        }
        return true;
    }

}
