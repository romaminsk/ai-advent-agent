package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JSON-хранилище контекста беседы. По умолчанию файл истории —
 * ~/.ai-advent-agent/conversation.json (путь не зависит от текущего каталога
 * запуска); путь можно заменить абсолютным путём через переменную окружения
 * LLM_HISTORY_FILE.
 *
 * Обязанности только этого класса — работа с файлом истории:
 * - создание родительского каталога (на POSIX — с правами только владельца);
 * - монопольная блокировка через отдельный lock-файл рядом с JSON
 *   (сам conversation.json не блокируется — он заменяется при сохранении);
 *   остаточный lock-файл сам по себе не признак активного процесса —
 *   занятость определяется реальной файловой блокировкой;
 * - проверка структуры и версии формата при чтении; неизвестная версия
 *   не «угадывается» — файл не изменяется, запуск останавливается с ошибкой;
 * - безопасная запись: временный файл в том же каталоге, полный JSON в UTF-8,
 *   принудительный сброс на диск (FileChannel.force) и атомарная замена;
 *   при неподдерживаемой атомарной замене прежний файл сохраняется
 *   и выдаётся ошибка — тихой небезопасной перезаписи нет.
 *
 * Формат файла (schemaVersion 1):
 * {
 *   "schemaVersion": 1,
 *   "sessionId": "...",
 *   "messages": [ {"role": "user", "content": "..."},
 *                 {"role": "assistant", "content": "..."} ]
 * }
 *
 * В файле хранятся только завершённые пары user/assistant: системная
 * инструкция, служебные команды, ошибки и неудачные запросы не сохраняются.
 * Секреты (ключ API, Authorization, содержимое .env) и служебные поля
 * ответов (в том числе reasoning_content) в файл не попадают.
 */
public final class JsonConversationStore implements ConversationStore {

    /** Текущая поддерживаемая версия формата файла истории (День 9: + summary). */
    public static final int SUPPORTED_SCHEMA_VERSION = 2;

    /** Legacy-версия без отдельного резюме; по-прежнему читается полностью. */
    public static final int LEGACY_SCHEMA_VERSION = 1;

    private static final String DEFAULT_DIR_NAME = ".ai-advent-agent";
    private static final String DEFAULT_FILE_NAME = "conversation.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Path lockFile;
    private FileChannel lockChannel;
    private FileLock lock;

    /**
     * Открывает хранилище по указанному пути: создаёт родительский каталог
     * при необходимости (на POSIX — с правами только владельца) и берёт
     * файловую блокировку. Пока хранилище открыто, второй экземпляр
     * приложения с той же историей не запустится.
     */
    public JsonConversationStore(Path historyFile) {
        this.file = historyFile.toAbsolutePath().normalize();
        Path dir = file.getParent();
        try {
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
                applyOwnerOnlyPermissions(dir, true);
            }
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось создать каталог для истории: " + dir
                            + " (" + e.getMessage() + ").", e);
        }
        this.lockFile = (dir != null ? dir : Path.of("."))
                .resolve(file.getFileName().toString() + ".lock");
        acquireLock();
    }

    /**
     * Открывает хранилище с путём по умолчанию либо заданным переменной
     * окружения LLM_HISTORY_FILE (только абсолютный путь: относительный
     * сделал бы историю разной в зависимости от каталога запуска).
     */
    public static JsonConversationStore openDefault() {
        String configured = System.getenv("LLM_HISTORY_FILE");
        Path historyFile;
        if (configured == null || configured.isBlank()) {
            historyFile = Path.of(System.getProperty("user.home"),
                    DEFAULT_DIR_NAME, DEFAULT_FILE_NAME);
        } else {
            Path specified;
            try {
                specified = Path.of(configured);
            } catch (InvalidPathException e) {
                throw new ConversationStoreException(
                        "LLM_HISTORY_FILE содержит некорректный путь: " + configured, e);
            }
            if (!specified.isAbsolute()) {
                throw new ConversationStoreException(
                        "LLM_HISTORY_FILE содержит относительный путь: " + configured
                                + ". Укажите абсолютный путь, чтобы история не зависела "
                                + "от каталога, из которого запущено приложение.");
            }
            historyFile = specified;
        }
        return new JsonConversationStore(historyFile);
    }

    /** Путь к основному файлу истории (для диагностики и тестов). */
    public Path file() {
        return file;
    }

    @Override
    public ConversationState load() {
        if (!Files.exists(file)) {
            // Истории ещё нет — начинаем новую беседу, ничего не записываем.
            return ConversationState.newEmpty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось прочитать файл истории: " + file
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
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION
                && schemaVersion != LEGACY_SCHEMA_VERSION) {
            // Неизвестную версию не угадываем и файл молча не переписываем.
            throw new ConversationStoreException(
                    "Неизвестная версия формата файла истории: " + file
                            + " (schemaVersion=" + version.asText()
                            + "); приложение поддерживает schemaVersion="
                            + SUPPORTED_SCHEMA_VERSION + " и читает старые файлы версии "
                            + LEGACY_SCHEMA_VERSION + ". Файл не изменён: чтобы начать "
                            + "новую беседу, вручную переименуйте или переместите его "
                            + "(сохранив копию) и запустите приложение заново.");
        }

        JsonNode sessionIdNode = root.get("sessionId");
        if (sessionIdNode == null || !sessionIdNode.isTextual()
                || sessionIdNode.asText().isBlank()) {
            throw corrupt("отсутствует непустой sessionId");
        }

        JsonNode messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            throw corrupt("отсутствует массив messages");
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < messagesNode.size(); i++) {
            JsonNode node = messagesNode.get(i);
            if (node == null || !node.isObject()) {
                throw corrupt("messages[" + i + "] не является объектом");
            }
            String role = node.path("role").asText(null);
            String content = node.path("content").asText(null);
            // Сохраняются только целые пары: чётный индекс — user, нечётный — assistant.
            String expectedRole = (i % 2 == 0) ? "user" : "assistant";
            if (role == null || !expectedRole.equals(role)) {
                throw corrupt("messages[" + i + "]: ожидалась роль \""
                        + expectedRole + "\" (пары должны быть завершёнными)");
            }
            if (content == null || content.isBlank()) {
                throw corrupt("messages[" + i + "]: текст сообщения пуст");
            }
            messages.add(new ChatMessage(role, content));
        }
        if (!messages.isEmpty() && !"assistant".equals(messages.get(messages.size() - 1).role())) {
            // Незавершённый пользовательский ввод в файл не сохраняется
            // и при чтении не принимается.
            throw corrupt("незавершённая пара: последнее сообщение должно быть assistant");
        }

        ConversationSummary summary = schemaVersion >= SUPPORTED_SCHEMA_VERSION
                ? parseSummary(root.get("summary"))
                : null;
        return new ConversationState(sessionIdNode.asText(), messages, summary);
    }

    /**
     * Читает необязательную сущность summary (День 9). Старый файл без поля
     * summary остаётся совместимым — возвращается null. Повреждённое или
     * неоднозначное поле — ошибка повреждения: применять его молча нельзя.
     */
    private static ConversationSummary parseSummary(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw new ConversationStoreException(
                    "Резюме покрытой истории (summary) повреждено: ожидается объект. "
                            + "Файл не изменён приложением. Чтобы начать новую беседу, "
                            + "вручную переименуйте или переместите файл (сохранив копию) "
                            + "и запустите приложение заново — архив будет загружен без "
                            + "резюме после восстановления структуры вручную.");
        }
        String text = node.path("text").asText(null);
        Integer coveredMessages = readPositiveInt(node.get("coveredMessages"),
                "summary.coveredMessages");
        String fingerprint = node.path("coveredFingerprint").asText(null);
        Integer formatVersion = readPositiveInt(node.get("formatVersion"),
                "summary.formatVersion");
        try {
            return new ConversationSummary(text, coveredMessages, fingerprint, formatVersion);
        } catch (IllegalArgumentException e) {
            throw new ConversationStoreException(
                    "Резюме покрытой истории (summary) повреждено ("
                            + e.getMessage() + "). Файл не изменён приложением. "
                            + "Чтобы начать новую беседу, вручную переименуйте или "
                            + "переместите файл (сохранив копию) и запустите приложение "
                            + "заново.");
        }
    }

    private static Integer readPositiveInt(JsonNode node, String name) {
        if (node == null || !node.isIntegralNumber()) {
            throw new IllegalArgumentException(
                    "поле " + name + " — обязательное целое положительное число");
        }
        int value = node.asInt();
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "поле " + name + " должно быть положительным");
        }
        return value;
    }

    @Override
    public void save(ConversationState state) {
        java.util.Objects.requireNonNull(state, "state");
        Path temp = null;
        try {
            String json = serialize(state);
            // Временный файл в том же каталоге: замена остаётся атомарной.
            temp = Files.createTempFile(file.getParent(),
                    file.getFileName().toString() + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // Принудительный сброс данных и метаданных до замены основного файла.
                channel.force(true);
            }
            applyOwnerOnlyPermissions(temp, false);
            try {
                Files.move(temp, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // На «тихую» небезопасную перезапись не переходим:
                // прежний файл остаётся, временный удаляем, сообщаем об ошибке.
                throw new ConversationStoreException(
                        "Не удалось атомарно заменить файл истории " + file
                                + ": файловая система не поддерживает атомарную замену. "
                                + "Предыдущая история не изменена.", e);
            }
            temp = null;
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось сохранить историю в файл " + file
                            + " (" + e.getMessage() + "). Предыдущая история не изменена.", e);
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

    @Override
    public void close() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Блокировка будет снята системой при закрытии процесса в крайнем случае.
        }
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
        }
        lock = null;
        lockChannel = null;
    }

    /**
     * Собирает JSON состояния: версия формата, sessionId, пары user/assistant
     * и необязательная сущность summary (всегда schemaVersion 2).
     */
    private String serialize(ConversationState state) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SUPPORTED_SCHEMA_VERSION);
        root.put("sessionId", state.sessionId());
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : state.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        }
        if (state.summary() != null) {
            ObjectNode summary = root.putObject("summary");
            summary.put("formatVersion", state.summary().formatVersion());
            summary.put("coveredMessages", state.summary().coveredMessages());
            summary.put("coveredFingerprint", state.summary().coveredFingerprint());
            summary.put("text", state.summary().text());
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    /** Открывает lock-файл и берёт блокировку на всё время работы с беседой. */
    private void acquireLock() {
        try {
            lockChannel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new ConversationStoreException(
                    "Не удалось открыть lock-файл " + lockFile
                            + " (" + e.getMessage() + ").", e);
        }
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            closeQuietly();
            throw busyError();
        } catch (IOException e) {
            closeQuietly();
            throw new ConversationStoreException(
                    "Не удалось заблокировать файл истории " + lockFile
                            + " (" + e.getMessage() + ").", e);
        }
        if (lock == null) {
            closeQuietly();
            throw busyError();
        }
        applyOwnerOnlyPermissions(lockFile, false);
    }

    private ConversationStoreException busyError() {
        return new ConversationStoreException(
                "Эта история уже открыта другим экземпляром приложения: " + file
                        + ". Закройте другой экземпляр либо укажите другой абсолютный "
                        + "путь в переменной LLM_HISTORY_FILE.");
    }

    private void closeQuietly() {
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
        }
        lockChannel = null;
    }

    /**
     * На POSIX-файловой системе ограничивает доступ только владельцем
     * (каталог rwx------, файл rw-------). На системах без POSIX-прав
     * запуск и запись не ломаются — остаются права по умолчанию.
     */
    private static void applyOwnerOnlyPermissions(Path path, boolean directory) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions
                    .fromString(directory ? "rwx------" : "rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            // Права ограничивают доступ, но не шифруют: см. README.
        }
    }

    /** Единая ошибка повреждённого файла: путь и безопасный следующий шаг. */
    private ConversationStoreException corrupt(String detail) {
        return new ConversationStoreException(
                "Файл истории повреждён или имеет неподдерживаемую структуру: "
                        + file + " (" + detail + "). Файл не изменён приложением. "
                        + "Чтобы начать новую беседу, вручную переименуйте или переместите "
                        + "этот файл (сохранив копию) и запустите приложение заново.");
    }
}
