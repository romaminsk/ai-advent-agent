package com.example;

import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Хранилище активного профиля провайдера (/model): одно имя профиля
 * в JSON-файле, переживает перезапуск. Паттерн и атомарная запись — как
 * в RagSettingsStore.
 *
 * Файл по умолчанию: ~/.ai-advent-agent/model-profile.json. Системное
 * свойство ai-agent.model-profile-file переопределяет путь (изоляция
 * self-tests от реальных настроек пользователя); без свойства путь прежний.
 */
public final class ModelProfileStore {

    private final Path file;

    public ModelProfileStore(Path file) {
        this.file = file;
    }

    public static ModelProfileStore defaultStore() {
        String override = System.getProperty("ai-agent.model-profile-file");
        if (override != null && !override.isBlank()) {
            return new ModelProfileStore(Path.of(override));
        }
        return new ModelProfileStore(Path.of(System.getProperty("user.home"),
                ".ai-advent-agent", "model-profile.json"));
    }

    public Path file() {
        return file;
    }

    /**
     * Имя сохранённого профиля; файла нет — cloud (поведение по умолчанию).
     * Значение не проверяется на известность: вызывающий код решает,
     * как поступить с незнакомым именем, файл не изменяется.
     */
    public String load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return ModelProfiles.CLOUD;
        }
        JsonNode root = JsonSupport.MAPPER.readTree(file.toFile());
        return root.path("profile").asText(ModelProfiles.CLOUD);
    }

    /** Атомарная запись имени профиля (tmp в том же каталоге → move). */
    public void save(String profileName) throws IOException {
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        ObjectNode root = JsonSupport.MAPPER.createObjectNode();
        root.put("profile", profileName);
        Path temporary = Files.createTempFile(absolute.getParent(),
                "model-profile.", ".tmp");
        try {
            Files.writeString(temporary, JsonSupport.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
