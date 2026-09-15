package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Хранилище ПРОФИЛЯ ПОЛЬЗОВАТЕЛЯ — отдельная сущность поверх модели памяти:
 * обращение к пользователю, его предпочтения по стилю и формату ответов,
 * ограничения и пайплайны скиллов.
 *
 * Это НЕ настройка модели: /mode fast|balanced|detailed задаёт режим ответа
 * (лимит генерации), а профиль — нюансы конкретного пользователя.
 * Один профиль на пользователя: не список записей, как в долговременной
 * памяти ({@link MemoryStore}), а один объект с полями, где каждое поле
 * хранит одно текущее значение (последнее заданное). Поэтому профиль —
 * не растущий накопитель, а редактируемая карточка.
 *
 * Отдельный файл ~/.ai-advent-agent/profile.json (путь — переменная
 * LLM_PROFILE_FILE, только абсолютный путь): профиль переживает /clear,
 * /reset и перезапуск, но хранится отдельно от памяти и истории.
 *
 * Техника работы с файлом — как у {@link MemoryStore}: отсутствие файла
 * допускается (пустой профиль, файл заранее не создаётся); валидация при
 * чтении; повреждённый файл — ошибка без изменения файла; атомарная запись
 * через временный файл и FileChannel.force; на POSIX-системах права только
 * владельца.
 */
public final class ProfileStore {

    /** Текущая поддерживаемая версия формата файла профиля. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final String DEFAULT_DIR_NAME = ".ai-advent-agent";
    private static final String DEFAULT_FILE_NAME = "profile.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** Открывает хранилище по указанному пути; каталог создаётся при необходимости. */
    public ProfileStore(Path profileFile) {
        this.file = profileFile.toAbsolutePath().normalize();
        try {
            Path dir = file.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
                applyOwnerOnlyPermissions(dir, true);
            }
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось создать каталог для профиля пользователя: "
                            + file.getParent() + " (" + e.getMessage() + ").", e);
        }
    }

    /**
     * Открывает хранилище с путём по умолчанию либо заданным переменной
     * окружения LLM_PROFILE_FILE (только абсолютный путь).
     */
    public static ProfileStore openDefault() {
        return new ProfileStore(defaultProfileFile(System.getenv()));
    }

    /**
     * Путь файла профиля по умолчанию (для тестов передаётся набор переменных
     * окружения): LLM_PROFILE_FILE с абсолютным путём либо общий файл
     * ~/.ai-advent-agent/profile.json.
     */
    static Path defaultProfileFile(Map<String, String> env) {
        String configured = env.get("LLM_PROFILE_FILE");
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("user.home"),
                    DEFAULT_DIR_NAME, DEFAULT_FILE_NAME);
        }
        Path specified;
        try {
            specified = Path.of(configured);
        } catch (InvalidPathException e) {
            throw new ConversationStoreException(
                    "LLM_PROFILE_FILE содержит некорректный путь: " + configured, e);
        }
        if (!specified.isAbsolute()) {
            throw new ConversationStoreException(
                    "LLM_PROFILE_FILE содержит относительный путь: " + configured
                            + ". Укажите абсолютный путь, чтобы профиль не зависел "
                            + "от каталога, из которого запущено приложение.");
        }
        return specified;
    }

    /** Путь к файлу профиля (для диагностики и тестов). */
    public Path file() {
        return file;
    }

    /**
     * Читает профиль. Отсутствующий файл — пустой профиль, файл заранее
     * не создаётся; повреждённый файл или неизвестная версия — ошибка
     * повреждения, файл при этом не изменяется.
     */
    public UserProfile load() {
        if (!Files.exists(file)) {
            return UserProfile.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось прочитать файл профиля пользователя: " + file
                            + " (" + e.getMessage() + ").", e);
        }
        if (root == null || !root.isObject()) {
            throw corrupt("ожидается JSON-объект");
        }
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isIntegralNumber()) {
            throw corrupt("отсутствует версия формата schemaVersion");
        }
        int schemaVersion = version.asInt();
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new ConversationStoreException(
                    "Неизвестная версия формата файла профиля: " + file
                            + " (schemaVersion=" + version.asText()
                            + "); приложение поддерживает schemaVersion="
                            + SUPPORTED_SCHEMA_VERSION + ". Файл не изменён: "
                            + "переместите его вручную (сохранив копию).");
        }
        JsonNode profileNode = root.get("profile");
        if (profileNode == null || profileNode.isNull() || profileNode.isMissingNode()) {
            return UserProfile.empty();
        }
        if (!profileNode.isObject()) {
            throw corrupt("profile не является объектом");
        }
        try {
            return new UserProfile(
                    textOrNull(profileNode.get("name")),
                    textOrNull(profileNode.get("style")),
                    textOrNull(profileNode.get("format")),
                    stringList(profileNode.get("constraints")),
                    skills(profileNode.get("skills")),
                    pipelines(profileNode.get("pipelines")),
                    textOrNull(profileNode.get("createdAt")),
                    textOrNull(profileNode.get("updatedAt")));
        } catch (IllegalArgumentException e) {
            throw corrupt("профиль повреждён (" + e.getMessage() + ")");
        }
    }

    /** Целиком заменяет файл профиля переданным объектом. Запись атомарная. */
    public void save(UserProfile profile) {
        java.util.Objects.requireNonNull(profile, "profile");
        Path temp = null;
        try {
            String json = serialize(profile);
            temp = Files.createTempFile(file.getParent(),
                    file.getFileName().toString() + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            applyOwnerOnlyPermissions(temp, false);
            try {
                Files.move(temp, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new ConversationStoreException(
                        "Не удалось атомарно заменить файл профиля " + file
                                + ": файловая система не поддерживает атомарную замену. "
                                + "Предыдущее содержимое не изменено.", e);
            }
            temp = null;
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось сохранить профиль пользователя в файл " + file
                            + " (" + e.getMessage() + "). Прежнее содержимое не изменено.", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Неудачный временный файл не вредит: основной файл не тронут.
                }
            }
        }
    }

    private static String serialize(UserProfile profile) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SUPPORTED_SCHEMA_VERSION);
        ObjectNode profileNode = root.putObject("profile");
        if (profile.name() != null) {
            profileNode.put("name", profile.name());
        }
        if (profile.style() != null) {
            profileNode.put("style", profile.style());
        }
        if (profile.format() != null) {
            profileNode.put("format", profile.format());
        }
        if (!profile.constraints().isEmpty()) {
            var constraintsNode = profileNode.putArray("constraints");
            for (String constraint : profile.constraints()) {
                constraintsNode.add(constraint);
            }
        }
        if (!profile.skills().isEmpty()) {
            ObjectNode skillsNode = profileNode.putObject("skills");
            for (Map.Entry<String, ProfileSkill> entry : profile.skills().entrySet()) {
                ProfileSkill skill = entry.getValue();
                ObjectNode node = skillsNode.putObject(skill.name());
                node.put("instructions", skill.instructions());
                node.put("createdAt", skill.createdAt());
                node.put("updatedAt", skill.updatedAt());
            }
        }
        if (!profile.pipelines().isEmpty()) {
            ObjectNode pipelinesNode = profileNode.putObject("pipelines");
            for (Map.Entry<String, List<String>> entry : profile.pipelines().entrySet()) {
                var namesNode = pipelinesNode.putArray(entry.getKey());
                for (String skillName : entry.getValue()) {
                    namesNode.add(skillName);
                }
            }
        }
        profileNode.put("createdAt", profile.createdAt());
        profileNode.put("updatedAt", profile.updatedAt());
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("текстовое поле должно быть строкой");
        }
        return node.asText();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("constraints должен быть массивом строк");
        }
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("ограничение должно быть строкой");
            }
            values.add(item.asText());
        }
        return values;
    }

    private static LinkedHashMap<String, ProfileSkill> skills(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("skills должен быть объектом");
        }
        LinkedHashMap<String, ProfileSkill> skills = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            JsonNode skillNode = field.getValue();
            if (key.isBlank() || skillNode == null || !skillNode.isObject()) {
                throw new IllegalArgumentException("скилл «" + key + "» повреждён");
            }
            String instructions = skillNode.path("instructions").asText(null);
            String createdAt = skillNode.path("createdAt").asText(null);
            String updatedAt = skillNode.path("updatedAt").asText(null);
            if (instructions == null || createdAt == null || updatedAt == null) {
                throw new IllegalArgumentException("скилл «" + key + "» повреждён");
            }
            skills.put(key, new ProfileSkill(key, instructions, createdAt, updatedAt));
        }
        return skills;
    }

    private static LinkedHashMap<String, List<String>> pipelines(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("pipelines должен быть объектом");
        }
        LinkedHashMap<String, List<String>> pipelines = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            JsonNode names = field.getValue();
            if (key.isBlank() || names == null || !names.isArray()) {
                throw new IllegalArgumentException("пайплайн «" + key + "» повреждён");
            }
            List<String> skillNames = new java.util.ArrayList<>();
            for (JsonNode item : names) {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw new IllegalArgumentException(
                            "пайплайн «" + key + "» повреждён: имя скилла обязательно");
                }
                skillNames.add(item.asText());
            }
            pipelines.put(key, skillNames);
        }
        return pipelines;
    }

    /** Единая ошибка повреждённого файла профиля: путь и безопасный шаг. */
    private ConversationStoreException corrupt(String detail) {
        return new ConversationStoreException(
                "Файл профиля пользователя повреждён или имеет неподдерживаемую "
                        + "структуру: " + file + " (" + detail + "). Файл профиля "
                        + "не изменён приложением. Чтобы начать с пустого профиля, "
                        + "вручную переместите этот файл (сохранив копию) и запустите "
                        + "приложение заново.");
    }

    /**
     * На POSIX-файловой системе ограничивает доступ только владельцем
     * (как в {@link MemoryStore}); на системах без POSIX-прав не ломается.
     */
    private static void applyOwnerOnlyPermissions(Path path, boolean directory) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions
                    .fromString(directory ? "rwx------" : "rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    /** Сравнение имён скиллов без учёта регистра — единое правило. */
    static boolean sameSkillName(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
