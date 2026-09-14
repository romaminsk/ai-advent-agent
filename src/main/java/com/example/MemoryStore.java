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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Хранилище долговременной памяти (слой 3 модели памяти ассистента):
 * профиль пользователя, принятые решения, накопленные знания
 * и предпочтения — сведения, которые переживают отдельные задачи
 * и сессии.
 *
 * Хранение — отдельный JSON-файл, не смешанный с историей беседы.
 * По умолчанию ~/.ai-advent-agent/memory.json (путь не зависит от текущего
 * каталога запуска); путь можно заменить абсолютным путём через переменную
 * окружения LLM_MEMORY_FILE. Файл не стирается /clear и /reset, не хранит
 * секретов и переживает перезапуск процесса.
 *
 * Техника работы с файлом — как в {@link JsonConversationStore}:
 * - отсутствие файла допускается (пустая память, файл заранее не создаётся);
 * - проверка структуры и версии формата при чтении; повреждённый файл
 *   или неизвестная версия не «угадываются» — ошибка с путём,
 *   файл не изменяется системой чтения;
 * - безопасная запись: временный файл в том же каталоге, полный JSON
 *   в UTF-8, принудительный сброс на диск (FileChannel.force)
 *   и атомарная замена; на файловых системах без атомарной замены —
 *   ошибка, а не тихая перезапись;
 * - на POSIX-системах права только владельца.
 *
 * Формат файла (schemaVersion 1):
 * {
 *   "schemaVersion": 1,
 *   "entries": { "ключ": {"value": "...", "createdAt": "...", "updatedAt": "..."} }
 * }
 */
public final class MemoryStore {

    /** Текущая поддерживаемая версия формата файла памяти. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final String DEFAULT_DIR_NAME = ".ai-advent-agent";
    private static final String DEFAULT_FILE_NAME = "memory.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    /** Открывает хранилище по указанному пути; каталог создаётся при необходимости. */
    public MemoryStore(Path memoryFile) {
        this.file = memoryFile.toAbsolutePath().normalize();
        try {
            Path dir = file.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
                applyOwnerOnlyPermissions(dir, true);
            }
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось создать каталог для долговременной памяти: "
                            + file.getParent() + " (" + e.getMessage() + ").", e);
        }
    }

    /**
     * Открывает хранилище с путём по умолчанию либо заданным переменной
     * окружения LLM_MEMORY_FILE (только абсолютный путь: относительный
     * сделал бы память зависимой от каталога запуска).
     */
    public static MemoryStore openDefault() {
        return new MemoryStore(defaultMemoryFile(System.getenv()));
    }

    /**
     * Путь файла долговременной памяти по умолчанию (для тестов передаётся
     * набор переменных окружения): LLM_MEMORY_FILE с абсолютным путём либо
     * общий файл ~/.ai-advent-agent/memory.json, который переживает запуски.
     */
    static Path defaultMemoryFile(java.util.Map<String, String> env) {
        String configured = env.get("LLM_MEMORY_FILE");
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("user.home"),
                    DEFAULT_DIR_NAME, DEFAULT_FILE_NAME);
        }
        Path specified;
        try {
            specified = Path.of(configured);
        } catch (InvalidPathException e) {
            throw new ConversationStoreException(
                    "LLM_MEMORY_FILE содержит некорректный путь: " + configured, e);
        }
        if (!specified.isAbsolute()) {
            throw new ConversationStoreException(
                    "LLM_MEMORY_FILE содержит относительный путь: " + configured
                            + ". Укажите абсолютный путь, чтобы память не зависела "
                            + "от каталога, из которого запущено приложение.");
        }
        return specified;
    }

    /** Путь к файлу памяти (для диагностики и тестов). */
    public Path file() {
        return file;
    }

    /**
     * Читает долговременную память. Отсутствующий файл — пустая память,
     * файл заранее не создаётся; повреждённый файл или неизвестная версия —
     * ошибка повреждения, файл при этом не изменяется.
     */
    public LinkedHashMap<String, MemoryEntry> load() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось прочитать файл долговременной памяти: " + file
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
                    "Неизвестная версия формата файла долговременной памяти: " + file
                            + " (schemaVersion=" + version.asText()
                            + "); приложение поддерживает schemaVersion="
                            + SUPPORTED_SCHEMA_VERSION + ". Файл не изменён: "
                            + "переместите его вручную (сохранив копию).");
        }
        JsonNode entriesNode = root.get("entries");
        if (entriesNode == null || entriesNode.isNull() || entriesNode.isMissingNode()) {
            return new LinkedHashMap<>();
        }
        if (!entriesNode.isObject()) {
            throw corrupt("entries не является объектом");
        }
        LinkedHashMap<String, MemoryEntry> entries = new LinkedHashMap<>();
        var fields = entriesNode.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            JsonNode node = field.getValue();
            if (key.isBlank() || node == null || !node.isObject()) {
                throw corrupt("запись памяти «" + key + "» повреждена");
            }
            String value = node.path("value").asText(null);
            String createdAt = node.path("createdAt").asText(null);
            String updatedAt = node.path("updatedAt").asText(null);
            try {
                entries.put(key, new MemoryEntry(key, value, createdAt, updatedAt));
            } catch (IllegalArgumentException e) {
                throw corrupt("запись памяти «" + key + "» повреждена ("
                        + e.getMessage() + ")");
            }
        }
        return entries;
    }

    /**
     * Полностью заменяет файл памяти переданными записями (порядок сохраняется).
     * Запись атомарная: сбой не оставляет повреждённого файла, прежняя версия
     * при сбое остаётся пригодной. Вставка дубликата ключа — обновление записи
     * (createdAt сохраняется, updatedAt — вызвал).
     */
    public void save(LinkedHashMap<String, MemoryEntry> entries) {
        java.util.Objects.requireNonNull(entries, "entries");
        // Ограничение длины применяется только к новым ключам (в {@link #put});
        // старые записи (в том числе без явного ключа, длинный текст в ключе)
        // читаются и перезаписываются без потерь.
        Path temp = null;
        try {
            String json = serialize(entries);
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
                        "Не удалось атомарно заменить файл памяти " + file
                                + ": файловая система не поддерживает атомарную замену. "
                                + "Предыдущее содержимое не изменено.", e);
            }
            temp = null;
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось сохранить долговременную память в файл " + file
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

    /** Добавляет или обновляет запись по ключу; возвращает хранилище после записи. */
    public LinkedHashMap<String, MemoryEntry> put(String key, String value) {
        requireKeyLength(key.trim());
        LinkedHashMap<String, MemoryEntry> entries = load();
        Instant now = Instant.now();
        MemoryEntry existing = entries.get(key.trim());
        entries.put(key.trim(), existing == null
                ? MemoryEntry.create(key.trim(), value, now)
                : existing.withValue(value, now));
        save(entries);
        return entries;
    }

    /** Удаляет запись по ключу; возвращает true, если запись была удалена. */
    public boolean remove(String key) {
        LinkedHashMap<String, MemoryEntry> entries = load();
        boolean removed = entries.remove(key) != null;
        if (removed) {
            save(entries);
        }
        return removed;
    }

    private static String serialize(LinkedHashMap<String, MemoryEntry> entries)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SUPPORTED_SCHEMA_VERSION);
        ObjectNode entriesNode = root.putObject("entries");
        for (Map.Entry<String, MemoryEntry> entry : entries.entrySet()) {
            ObjectNode node = entriesNode.putObject(entry.getKey());
            node.put("value", entry.getValue().value());
            node.put("createdAt", entry.getValue().createdAt());
            node.put("updatedAt", entry.getValue().updatedAt());
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    /** Единая ошибка повреждённого файла памяти: путь и безопасный шаг. */
    private ConversationStoreException corrupt(String detail) {
        return new ConversationStoreException(
                "Файл долговременной памяти повреждён или имеет неподдерживаемую "
                        + "структуру: " + file + " (" + detail + "). Файл долговременной "
                        + "памяти не изменён приложением. Чтобы начать пустую память, "
                        + "вручную переместите этот файл (сохранив копию) и запустите "
                        + "приложение заново.");
    }

    /** Ключ новой записи ограничен по длине (как имя ветки); старые записи не трогает. */
    private static void requireKeyLength(String key) {
        if (key == null || key.isBlank()) {
            throw new AgentException("Ключ записи памяти обязателен и не может быть пустым.");
        }
        if (key.length() > BranchData.MAX_NAME_LENGTH) {
            throw new AgentException("Ключ записи памяти не длиннее "
                    + BranchData.MAX_NAME_LENGTH + " символов, получено: " + key.length() + ".");
        }
    }

    /**
     * На POSIX-файловой системе ограничивает доступ только владельцем
     * (каталог rwx------, файл rw-------). На системах без POSIX-прав
     * запись не ломается — остаются права по умолчанию.
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
