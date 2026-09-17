package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Хранилище ИНВАРИАНТОВ — жёстких ограничений, которые агент не имеет права
 * нарушать (выбранная архитектура, принятые технические решения, ограничения
 * по стеку, бизнес-правила). Инвариант — не предпочтение и не факт памяти:
 * это рамка, которую агент обязан учитывать в рассуждениях и отказываться
 * нарушать.
 *
 * Отдельная сущность поверх модели памяти: инварианты НЕ хранятся в рабочей
 * памяти ({@link WorkingMemory}, сессионное) и НЕ в истории диалога — только
 * в отдельном файле ~/.ai-advent-agent/invariants.json. Инварианты переживают
 * /clear, /reset и перезапуск, как долговременная память
 * ({@link MemoryStore}) и профиль ({@link ProfileStore}).
 *
 * Техника работы с файлом — как у существующих хранилищ: отсутствие файла
 * допускается (пустой список, файл заранее не создаётся); валидация при
 * чтении; повреждённый файл — ошибка без изменения файла; атомарная запись
 * через временный файл и FileChannel.force; на POSIX-системах права только
 * владельца. Путь можно задать переменной окружения LLM_INVARIANT_FILE
 * (только абсолютный путь).
 */
public final class InvariantStore {

    /** Текущая поддерживаемая версия формата файла инвариантов. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final String DEFAULT_DIR_NAME = ".ai-advent-agent";
    private static final String DEFAULT_FILE_NAME = "invariants.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** Открывает хранилище по указанному пути; каталог создаётся при необходимости. */
    public InvariantStore(Path invariantFile) {
        this.file = invariantFile.toAbsolutePath().normalize();
        try {
            Path dir = file.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
                applyOwnerOnlyPermissions(dir, true);
            }
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось создать каталог для инвариантов: "
                            + file.getParent() + " (" + e.getMessage() + ").", e);
        }
    }

    /**
     * Открывает хранилище с путём по умолчанию либо заданным переменной
     * окружения LLM_INVARIANT_FILE (только абсолютный путь).
     */
    public static InvariantStore openDefault() {
        return new InvariantStore(defaultInvariantFile(System.getenv()));
    }

    /**
     * Путь файла инвариантов по умолчанию (для тестов передаётся набор
     * переменных окружения): LLM_INVARIANT_FILE с абсолютным путём либо
     * общий файл ~/.ai-advent-agent/invariants.json, который переживает
     * запуски.
     */
    static Path defaultInvariantFile(Map<String, String> env) {
        String configured = env.get("LLM_INVARIANT_FILE");
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("user.home"),
                    DEFAULT_DIR_NAME, DEFAULT_FILE_NAME);
        }
        Path specified;
        try {
            specified = Path.of(configured);
        } catch (InvalidPathException e) {
            throw new ConversationStoreException(
                    "LLM_INVARIANT_FILE содержит некорректный путь: " + configured, e);
        }
        if (!specified.isAbsolute()) {
            throw new ConversationStoreException(
                    "LLM_INVARIANT_FILE содержит относительный путь: " + configured
                            + ". Укажите абсолютный путь, чтобы инварианты не зависели "
                            + "от каталога, из которого запущено приложение.");
        }
        return specified;
    }

    /** Путь к файлу инвариантов (для диагностики и тестов). */
    public Path file() {
        return file;
    }

    /**
     * Читает инварианты в порядке сохранения. Отсутствующий файл — пустой
     * список, файл заранее не создаётся; повреждённый файл или неизвестная
     * версия — ошибка повреждения, файл при этом не изменяется.
     */
    public List<Invariant> load() {        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось прочитать файл инвариантов: " + file
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
                    "Неизвестная версия формата файла инвариантов: " + file
                            + " (schemaVersion=" + version.asText()
                            + "); приложение поддерживает schemaVersion="
                            + SUPPORTED_SCHEMA_VERSION + ". Файл не изменён: "
                            + "переместите его вручную (сохранив копию).");
        }
        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || itemsNode.isNull() || itemsNode.isMissingNode()) {
            return new ArrayList<>();
        }
        if (!itemsNode.isArray()) {
            throw corrupt("items должен быть массивом");
        }
        List<Invariant> invariants = new ArrayList<>();
        for (JsonNode node : itemsNode) {
            if (node == null || !node.isObject()) {
                throw corrupt("запись инварианта повреждена");
            }
            String id = node.path("id").asText(null);
            String text = node.path("text").asText(null);
            String category = node.path("category").asText(null);
            String createdAt = node.path("createdAt").asText(null);
            if (createdAt == null) {
                throw corrupt("у записи инварианта нет метки времени");
            }
            try {
                invariants.add(new Invariant(id, text, category, Instant.parse(createdAt)));
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                throw corrupt("запись инварианта повреждена (" + e.getMessage() + ")");
            }
        }
        return invariants;
    }

    /** Спецификационный метод чтения: возвращает список инвариантов. */
    public List<Invariant> list() {
        return load();
    }

    /**
     * Целиком заменяет файл инвариантов переданным списком (порядок
     * сохраняется). Запись атомарная: сбой не оставляет повреждённого файла,
     * прежняя версия при сбое остаётся пригодной.
     */
    public void save(List<Invariant> invariants) {
        java.util.Objects.requireNonNull(invariants, "invariants");
        Path temp = null;
        try {
            String json = serialize(invariants);
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
                        "Не удалось атомарно заменить файл инвариантов " + file
                                + ": файловая система не поддерживает атомарную замену. "
                                + "Предыдущее содержимое не изменено.", e);
            }
            temp = null;
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось сохранить инварианты в файл " + file
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

    /** Добавляет инвариант и сохраняет файл; возвращает созданную запись. */
    public Invariant add(String text, String category) {
        // Категория по умолчанию — other (для группировки в выводе).
        String normalized = category == null || category.isBlank()
                ? "other" : category.trim().toLowerCase(java.util.Locale.ROOT);
        List<Invariant> invariants = load();
        // Идентификатор уникален в списке: коллизия короткого id генерируется заново.
        Set<String> existingIds = new java.util.HashSet<>();
        for (Invariant invariant : invariants) {
            existingIds.add(invariant.id());
        }
        Invariant created = Invariant.create(text, normalized, Instant.now());
        while (existingIds.contains(created.id())) {
            created = Invariant.create(text, normalized, created.createdAt());
        }
        invariants.add(created);
        save(invariants);
        return created;
    }

    /** Удаляет инвариант по идентификатору; true — запись была и удалена. */
    public boolean remove(String id) {
        List<Invariant> invariants = load();
        boolean removed = invariants.removeIf(i -> i.id().equals(id));
        if (removed) {
            save(invariants);
        }
        return removed;
    }

    /** Очищает все инварианты; файл остаётся с пустым списком (schemaVersion 1). */
    public void clear() {
        save(new ArrayList<>());
    }

    private static String serialize(List<Invariant> invariants) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SUPPORTED_SCHEMA_VERSION);
        ArrayNode items = root.putArray("items");
        for (Invariant invariant : invariants) {
            ObjectNode node = items.addObject();
            node.put("id", invariant.id());
            node.put("text", invariant.text());
            if (invariant.category() != null) {
                node.put("category", invariant.category());
            }
            node.put("createdAt", invariant.createdAt().toString());
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    /** Единая ошибка повреждённого файла инвариантов: путь и безопасный шаг. */
    private ConversationStoreException corrupt(String detail) {
        return new ConversationStoreException(
                "Файл инвариантов повреждён или имеет неподдерживаемую "
                        + "структуру: " + file + " (" + detail + "). Файл инвариантов "
                        + "не изменён приложением. Чтобы начать с пустого списка, "
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
}
