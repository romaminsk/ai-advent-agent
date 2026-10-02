package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * JSON-хранилище контекста беседы. По умолчанию файл истории создаётся в
 * ~/.ai-advent-agent/ с уникальным именем на каждый запуск
 * («chat-<дата-время>.json»: сессии не затирают друг друга, запуск не требует
 * переменных окружения); путь можно задать абсолютным путём через переменную
 * окружения LLM_HISTORY_FILE.
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

    /** Текущая поддерживаемая версия формата (+ блок фактов, ветки и состояние диалога). */
    public static final int SUPPORTED_SCHEMA_VERSION = 3;

    /** Версии без резюме/фактов/веток; по-прежнему читаются полностью. */
    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int DAY9_SCHEMA_VERSION = 2;

    private static final String DEFAULT_DIR_NAME = ".ai-advent-agent";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Path lockFile;
    /** true, если файл истории сгенерирован per-run (LLM_HISTORY_FILE не задан). */
    private final boolean generatedHistoryFile;
    private FileChannel lockChannel;
    private FileLock lock;

    /**
     * Открывает хранилище по указанному пути: создаёт родительский каталог
     * при необходимости (на POSIX — с правами только владельца) и берёт
     * файловую блокировку. Пока хранилище открыто, второй экземпляр
     * приложения с той же историей не запустится.
     */
    public JsonConversationStore(Path historyFile) {
        this(historyFile, false);
    }

    /** Перегрузка для тестов: явный флаг per-run генерации имени истории. */
    public JsonConversationStore(Path historyFile, boolean generatedHistoryFile) {
        this.generatedHistoryFile = generatedHistoryFile;
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
     * Открывает хранилище по умолчанию либо с путём из переменной окружения
     * LLM_HISTORY_FILE (только абсолютный путь: относительный сделал бы
     * историю разной в зависимости от каталога запуска). Если последняя
     * сессия занята параллельным экземпляром, запуск не ломается — работа
     * идёт в новом per-run файле (прежнее поведение параллельных запусков).
     */
    public static JsonConversationStore openDefault() {
        java.util.Map.Entry<Path, Boolean> resolved = defaultHistoryFileWithFlag(System.getenv());
        try {
            return new JsonConversationStore(resolved.getKey(), resolved.getValue());
        } catch (ConversationBusyException busy) {
            return new JsonConversationStore(newChatFile(
                    Path.of(System.getProperty("user.home"), DEFAULT_DIR_NAME)), true);
        }
    }

    /** История занята другим экземпляром приложения (файловая блокировка). */
    static final class ConversationBusyException extends ConversationStoreException {
        ConversationBusyException(String message) {
            super(message);
        }
    }

    /**
     * Возвращает путь файла истории по умолчанию и флаг, был ли файл
     * сгенерирован per-run (LLM_HISTORY_FILE отсутствует), а не задан явно.
     */
    static java.util.Map.Entry<Path, Boolean> defaultHistoryFileWithFlag(
            java.util.Map<String, String> env) {
        String configured = env.get("LLM_HISTORY_FILE");
        boolean generated = configured == null || configured.isBlank();
        return java.util.Map.entry(defaultHistoryFile(env), generated);
    }

    /**
     * Путь файла истории по умолчанию (для тестов передаётся набор переменных
     * окружения; {@link #openDefault()} передаёт System.getenv).
     *
     * Если LLM_HISTORY_FILE не задана — открывается последняя сессия
     * ~/.ai-advent-agent/ (самый свежий «chat-*.json»): история и dialogState
     * переживают перезапуск; новый per-run файл создаётся только при первом
     * запуске (или занятости последней сессии — см. openDefault).
     * Файл долговременной памяти при этом общий (~/.ai-advent-agent/
     * memory.json) и переживает запуски.
     */
    static Path defaultHistoryFile(java.util.Map<String, String> env) {
        String configured = env.get("LLM_HISTORY_FILE");
        if (configured != null && !configured.isBlank()) {
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
            return specified;
        }
        // Без переменной — продолжение последней сессии: самый свежий
        // chat-*.json каталога истории (история и dialogState переживают
        // перезапуск); новый per-run файл создаётся, только если сессий
        // ещё нет (или последняя занята — см. openDefault).
        return continueOrNewChatFile(
                Path.of(System.getProperty("user.home"), DEFAULT_DIR_NAME));
    }

    /** Продолжение последней сессии каталога либо новый per-run файл (для тестов). */
    static Path continueOrNewChatFile(Path dir) {
        Path latest = latestChatFile(dir);
        return latest != null ? latest : newChatFile(dir);
    }

    /**
     * Самый свежий per-run файл сессии в каталоге (имя — нулепadded метка
     * времени, лексикографический максимум = хронологический); null — сессий нет
     * или каталог недоступен (запуску это не мешает: будет создан новый файл).
     */
    static Path latestChatFile(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("chat-") && name.endsWith(".json")
                                && Files.isRegularFile(p);
                    })
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Имя нового per-run файла: дата-время с миллисекундами + nanoTime-суффикс. */
    private static Path newChatFile(Path dir) {
        java.time.format.DateTimeFormatter stamp = java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss-SSS", java.util.Locale.ROOT);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String nanoSuffix = String.format(java.util.Locale.ROOT, "%04x",
                System.nanoTime() & 0xFFFF);
        return dir.resolve("chat-" + now.format(stamp) + "-" + nanoSuffix + ".json");
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
                && schemaVersion != LEGACY_SCHEMA_VERSION
                && schemaVersion != DAY9_SCHEMA_VERSION) {
            // Неизвестную версию не угадываем и файл молча не переписываем.
            throw new ConversationStoreException(
                    "Неизвестная версия формата файла истории: " + file
                            + " (schemaVersion=" + version.asText()
                            + "); приложение поддерживает schemaVersion="
                            + SUPPORTED_SCHEMA_VERSION + " и читает старые файлы версий "
                            + LEGACY_SCHEMA_VERSION + " и " + DAY9_SCHEMA_VERSION
                            + ". Файл не изменён: чтобы начать новую беседу, вручную "
                            + "переименуйте или переместите его (сохранив копию) "
                            + "и запустите приложение заново.");
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

        ConversationSummary summary = parseSummaryForVersion(schemaVersion, root);
        LinkedHashMap<String, String> facts;
        BranchData branches;
        DialogTaskState dialogState;
        try {
            facts = schemaVersion >= DAY9_SCHEMA_VERSION
                    ? parseFacts(root.get("facts"))
                    : null;
            branches = schemaVersion >= SUPPORTED_SCHEMA_VERSION
                    ? parseBranches(root.get("branches"), messages.size())
                    : null;
            dialogState = schemaVersion >= SUPPORTED_SCHEMA_VERSION
                    ? parseDialogState(root.get("dialogState"))
                    : null;
        } catch (ConversationStoreException e) {
            // Ошибка необязательной сущности тоже содержит путь к файлу.
            throw new ConversationStoreException(e.getMessage() + " Файл: " + file, e);
        }
        if (branches == null) {
            branches = BranchData.empty();
        } else {
            if (branches.checkpointIndex() > messages.size()) {
                throw corrupt("branches.checkpointIndex больше числа сохранённых сообщений");
            }
            BranchData.Branch activeBranch = branches.branchByName(branches.active());
            if (activeBranch != null
                    && branches.checkpointIndex() + activeBranch.messages().size()
                    != messages.size()) {
                // Полная история активной ветки (префикс + хвост) должна
                // совпадать с массивом messages: иначе модель веток рассинхронизирована.
                throw corrupt("хвост активной ветки не совпадает с массивом messages");
            }
        }
        return new ConversationState(sessionIdNode.asText(), messages, summary, facts,
                branches, dialogState);
    }

    /** Summary читается в версиях 2+; в старых файлах его нет. */
    private static ConversationSummary parseSummaryForVersion(int schemaVersion, JsonNode root) {
        return schemaVersion >= DAY9_SCHEMA_VERSION ? parseSummary(root.get("summary")) : null;
    }





    /**
     * Читает необязательную сущность summary. Старый файл без поля
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

    /**
     * Читает необязательный блок фактов. Отсутствие — null.
     * Повреждённое поле (не текст, пустой ключ) — ошибка повреждения:
     * применять молча нельзя.
     */
    private static LinkedHashMap<String, String> parseFacts(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw corruptEntity("блок фактов (facts)",
                    "ожидается объект вида {\"ключ\": \"значение\"}");
        }
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        Iterator<String> nameIterator = node.fieldNames();
        while (nameIterator.hasNext()) {
            String key = nameIterator.next();
            JsonNode valueNode = node.get(key);
            if (key.isBlank() || !valueNode.isTextual() || valueNode.asText().isBlank()) {
                throw corruptEntity("блок фактов (facts)",
                        "ключ «" + key + "» пуст или значение не является текстом");
            }
            facts.put(key, valueNode.asText());
        }
        return facts;
    }

    /**
     * Читает необязательное состояние диалога (dialogState): цель, уточнения,
     * ограничения, термины и открытые вопросы. Отсутствие поля — null
     * (совместимость с файлами, записанными до его появления). Повреждённое
     * поле — ошибка повреждения: применять молча нельзя.
     */
    private static DialogTaskState parseDialogState(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw corruptEntity("состояние диалога (dialogState)",
                    "ожидается объект с полями goal, clarifications, constraints, "
                            + "terms, openQuestions");
        }
        String goal = null;
        JsonNode goalNode = node.get("goal");
        if (goalNode != null && !goalNode.isNull()) {
            if (!goalNode.isTextual() || goalNode.asText().isBlank()) {
                throw corruptEntity("состояние диалога (dialogState)",
                        "goal не является непустым текстом");
            }
            goal = goalNode.asText();
        }
        return new DialogTaskState(goal,
                parseStringList(node.get("clarifications"), "clarifications"),
                parseStringList(node.get("constraints"), "constraints"),
                parseStringList(node.get("terms"), "terms"),
                parseStringList(node.get("openQuestions"), "openQuestions"));
    }

    /** Список непустых строк необязательного поля dialogState; отсутствие — пустой список. */
    private static List<String> parseStringList(JsonNode node, String name) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw corruptEntity("состояние диалога (dialogState)",
                    name + " не является массивом строк");
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item == null || !item.isTextual() || item.asText().isBlank()) {
                throw corruptEntity("состояние диалога (dialogState)",
                        name + "[" + i + "] не является непустым текстом");
            }
            items.add(item.asText());
        }
        return items;
    }

    /**
     * Читает необязательную модель веток. Отсутствие — null
     * (совместимость со старыми файлами). Хвост каждой ветки — целые пары,
     * начинающиеся с user; имена уникальны и валидны; активная ветка есть.
     */
    private static BranchData parseBranches(JsonNode node, int totalMessages) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw corruptEntity("модель веток (branches)", "ожидается объект");
        }
        JsonNode checkpoint = node.get("checkpointIndex");
        if (checkpoint == null || !checkpoint.isIntegralNumber()) {
            throw corruptEntity("модель веток (branches)",
                    "отсутствует целое checkpointIndex");
        }
        int checkpointIndex = checkpoint.asInt();
        if (checkpointIndex < 0 || checkpointIndex % 2 != 0) {
            throw corruptEntity("модель веток (branches)",
                    "checkpointIndex должен быть неотрицательным чётным числом");
        }
        JsonNode activeNode = node.get("active");
        if (activeNode == null || !activeNode.isTextual() || activeNode.asText().isBlank()) {
            throw corruptEntity("модель веток (branches)", "отсутствует имя активной ветки");
        }
        JsonNode listNode = node.get("list");
        if (listNode == null || !listNode.isArray() || listNode.isEmpty()) {
            throw corruptEntity("модель веток (branches)",
                    "отсутствует непустой массив list");
        }
        List<BranchData.Branch> branches = new ArrayList<>();
        java.util.Set<String> usedNames = new java.util.LinkedHashSet<>();
        for (int i = 0; i < listNode.size(); i++) {
            JsonNode item = listNode.get(i);
            if (item == null || !item.isObject()) {
                throw corruptEntity("модель веток (branches)",
                        "list[" + i + "] не является объектом");
            }
            String name = item.path("name").asText(null);
            if (name == null || name.isBlank()) {
                throw corruptEntity("модель веток (branches)",
                        "list[" + i + "] без имени");
            }
            try {
                name = BranchData.validateName(name);
            } catch (AgentException e) {
                throw corruptEntity("модель веток (branches)", e.getMessage());
            }
            if (!usedNames.add(name)) {
                throw corruptEntity("модель веток (branches)",
                        "повторяющееся имя ветки: " + name);
            }
            JsonNode tailNode = item.get("messages");
            List<ChatMessage> tail = new ArrayList<>();
            int limit = tailNode != null && tailNode.isArray() ? tailNode.size() : 0;
            for (int j = 0; j < limit; j++) {
                JsonNode msg = tailNode.get(j);
                String role = msg.path("role").asText(null);
                String content = msg.path("content").asText(null);
                String expectedRole = (j % 2 == 0) ? "user" : "assistant";
                if (role == null || !expectedRole.equals(role)) {
                    throw corruptEntity("модель веток (branches)",
                            "хвост ветки «" + name + "»: ожидалась роль \""
                                    + expectedRole + "\"");
                }
                if (content == null || content.isBlank()) {
                    throw corruptEntity("модель веток (branches)",
                            "хвост ветки «" + name + "»: пустой текст сообщения");
                }
                tail.add(new ChatMessage(role, content));
            }
            branches.add(new BranchData.Branch(name, tail));
        }
        String active = activeNode.asText().toLowerCase(java.util.Locale.ROOT);
        try {
            active = BranchData.validateName(active);
        } catch (AgentException e) {
            throw corruptEntity("модель веток (branches)", e.getMessage());
        }
        try {
            return new BranchData(checkpointIndex, active, branches);
        } catch (IllegalArgumentException e) {
            throw corruptEntity("модель веток (branches)", e.getMessage());
        }
    }

    /** Единая ошибка повреждённой необязательной сущности (файл не изменён). */
    private static ConversationStoreException corruptEntity(String entity, String detail) {
        return new ConversationStoreException(
                "Необязательная сущность файла истории повреждена (" + entity + ": "
                        + detail + "). Файл не изменён приложением. Чтобы начать новую "
                        + "беседу, вручную переименуйте или переместите файл "
                        + "(сохранив копию) и запустите приложение заново.");
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
        removeLockFileQuietly();
    }

    /**
     * Идемпотентная уборка lock-файла per-run истории: выполняется после
     * release и закрытия канала. Удаляется только lock сгенерированного
     * per-run файла; при явно заданном LLM_HISTORY_FILE lock-файл не
     * удаляется, чтобы не создать гонку между процессами. Повторный вызов
     * и отсутствие файла — без ошибок; ошибка удаления не роняет выход
     * (тихая строка в stderr без путей и секретов).
     */
    void removeLockFileQuietly() {
        if (!generatedHistoryFile || lockFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException | RuntimeException e) {
            System.err.println("Не удалось удалить lock-файл истории — он останется на диске.");
        }
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
        if (state.facts() != null && !state.facts().isEmpty()) {
            ObjectNode facts = root.putObject("facts");
            for (Map.Entry<String, String> entry : state.facts().entrySet()) {
                facts.put(entry.getKey(), entry.getValue());
            }
        }
        BranchData branches = state.branches();
        if (branches != null && !isTrivialBranching(branches)) {
            ObjectNode branchesNode = root.putObject("branches");
            branchesNode.put("checkpointIndex", branches.checkpointIndex());
            branchesNode.put("active", branches.active());
            ArrayNode list = branchesNode.putArray("list");
            for (BranchData.Branch branch : branches.branches()) {
                ObjectNode item = list.addObject();
                item.put("name", branch.name());
                ArrayNode tail = item.putArray("messages");
                for (ChatMessage message : branch.messages()) {
                    ObjectNode node = tail.addObject();
                    node.put("role", message.role());
                    node.put("content", message.content());
                }
            }
        }
        DialogTaskState dialogState = state.dialogState();
        if (dialogState != null && !dialogState.isEmpty()) {
            ObjectNode stateNode = root.putObject("dialogState");
            if (dialogState.goal() != null) {
                stateNode.put("goal", dialogState.goal());
            }
            putStringArray(stateNode, "clarifications", dialogState.clarifications());
            putStringArray(stateNode, "constraints", dialogState.constraints());
            putStringArray(stateNode, "terms", dialogState.terms());
            putStringArray(stateNode, "openQuestions", dialogState.openQuestions());
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    /** Непустой список строк dialogState в JSON; пустой не сериализуется. */
    private static void putStringArray(ObjectNode parent, String name, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        ArrayNode array = parent.putArray(name);
        for (String item : items) {
            array.add(item);
        }
    }

    /**
     * Тривиальное ветвление (только ветка main, checkpoint в начале)
     * не сериализуется: полная история ветки main и есть массив messages.
     */
    private static boolean isTrivialBranching(BranchData branches) {
        return branches.checkpointIndex() == 0
                && branches.branches().size() == 1
                && BranchData.DEFAULT_ACTIVE.equals(branches.active());
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
        return new ConversationBusyException(
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
