package com.example;

import com.example.assistant.DevAssistant;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Команды чата /files: запуск агента, просмотр очереди, применение, отмена и статус. */
final class FilesCommands {
    static final String USAGE = """
            /files <цель> — агент сам ищет и читает файлы проекта и предлагает изменения
            /files diff — показать очередь предложенных изменений
            /files apply — применить очередь (проверка sha256, затем запись)
            /files discard — очистить очередь без записи
            /files status — размер очереди и последний журнал""";
    static final String THRESHOLD_WARNING = "Меняются README или docs/: это меняет корпус help;"
            + " порог 0.479250 нужно перекалибровать.";

    record ApplyReport(List<String> applied, String failedPath, String failure, List<String> notApplied,
                       List<String> conflicts) {
        boolean ok() { return conflicts.isEmpty() && failure == null; }
    }

    private final FilesChangeQueue queue = new FilesChangeQueue();
    private final Path projectRoot;
    private final Path journalDir;
    private final String serverCommand;

    FilesCommands(Path projectRoot, Path journalDir) {
        this(projectRoot, journalDir, null);
    }

    /** serverCommand == null — штатный запуск files-сервера (как в установленном CLI). */
    FilesCommands(Path projectRoot, Path journalDir, String serverCommand) {
        this.projectRoot = projectRoot;
        this.journalDir = journalDir;
        this.serverCommand = serverCommand;
    }

    private McpRegistry openRegistry() {
        return serverCommand == null ? McpRegistry.openFiles(projectRoot)
                : McpRegistry.open(Map.of("files", serverCommand), new McpClientComponent());
    }

    static FilesCommands forCurrentProject() {
        Path root;
        try {
            root = DevAssistant.resolveProject(null).path();
        } catch (RuntimeException e) {
            root = Path.of("").toAbsolutePath().normalize();
        }
        return new FilesCommands(root, FilesAgent.defaultJournalDir());
    }

    int pending() {
        return queue.size();
    }

    void handle(String raw, TerminalUi ui, FilesAgent.Model model) {
        String argument = raw == null ? "" : raw.strip();
        argument = argument.length() > "/files".length() ? argument.substring("/files".length()).strip() : "";
        String keyword = argument.toLowerCase(Locale.ROOT);
        switch (keyword) {
            case "" -> ui.showSystem(USAGE);
            case "diff" -> showDiff(ui);
            case "apply" -> apply(ui);
            case "discard" -> discard(ui);
            case "status" -> status(ui);
            default -> run(argument, ui, model);
        }
    }

    void warnIfPending(TerminalUi ui) {
        int count = queue.size();
        if (count > 0) {
            ui.showSystem("В очереди /files " + count + " непримённых изменений: при выходе они будут потеряны."
                    + " /files apply — применить, /files discard — отменить.");
        }
    }

    private void run(String goal, TerminalUi ui, FilesAgent.Model model) {
        ui.showSystem("Файловый агент: " + goal);
        Thread runner = Thread.currentThread();
        SignalHandler previous = Signal.handle(new Signal("INT"), signal -> runner.interrupt());
        FilesAgent.Outcome outcome;
        try (McpRegistry registry = openRegistry()) {
            McpRegistry.ServerStatus status = registry.status("files");
            if (status == null || !status.available()) {
                ui.showError("Файловый сервер недоступен: " + (status == null ? "нет статуса" : status.reason()));
                return;
            }
            FilesAgent agent = new FilesAgent(registry, projectRoot, queue, model, journalDir, ui::showSystem);
            outcome = agent.run(goal);
        } catch (RuntimeException e) {
            ui.showError("Ошибка /files: " + (e.getMessage() == null ? "неизвестная ошибка" : e.getMessage()));
            return;
        } finally {
            Signal.handle(new Signal("INT"), previous);
            Thread.interrupted();
        }
        if (outcome.answer() != null) {
            ui.showMessage(outcome.answer());
        }
        if (outcome.stopped()) {
            ui.showSystem(outcome.stopReason());
        }
        List<FilesChangeQueue.Change> changes = queue.list();
        if (changes.isEmpty()) {
            ui.showSystem("Изменений не предложено.");
        } else {
            ui.showSystem("Предложено изменений: " + changes.size() + ". Ниже — очередь.");
            showChanges(changes, ui);
            ui.showSystem("/files apply — применить, /files discard — отменить.");
        }
        ui.showSystem("Ходов: " + outcome.steps().size() + ", длительность: "
                + outcome.durationMillis() / 1000 + " с. Журнал: "
                + (outcome.journal() == null ? "не записан" : outcome.journal().toString()));
    }

    private void showDiff(TerminalUi ui) {
        List<FilesChangeQueue.Change> changes = queue.list();
        if (changes.isEmpty()) {
            ui.showSystem("Очередь пуста.");
            return;
        }
        showChanges(changes, ui);
    }

    private static void showChanges(List<FilesChangeQueue.Change> changes, TerminalUi ui) {
        for (FilesChangeQueue.Change change : changes) {
            ui.showSystem("#" + change.id() + " " + change.kind() + " " + change.path()
                    + " (+" + change.added() + " −" + change.removed() + ")");
            ui.showSystem(change.diff().isEmpty() ? "(новый файл)" : change.diff());
        }
    }

    private void apply(TerminalUi ui) {
        List<FilesChangeQueue.Change> changes = queue.list();
        if (changes.isEmpty()) {
            ui.showSystem("Очередь пуста: применять нечего.");
            return;
        }
        ui.showSystem("К применению файлов: " + changes.size());
        for (FilesChangeQueue.Change change : changes) {
            ui.showSystem("  " + change.kind() + " " + change.path());
        }
        if (changes.stream().anyMatch(change -> isDocumentation(change.path()))) {
            ui.showSystem("⚠ " + THRESHOLD_WARNING);
        }
        try (McpRegistry registry = openRegistry()) {
            ApplyReport report = applyQueue(changes, new FilesTools(new FilesSandbox(projectRoot)), registry, queue);
            report(report, ui);
        } catch (RuntimeException e) {
            ui.showError("Применение не выполнено, ничего не записано: "
                    + (e.getMessage() == null ? "неизвестная ошибка" : e.getMessage()));
        }
    }

    /** Сначала проверка всех записей (без записи); затем запись по одному через files_write. */
    static ApplyReport applyQueue(List<FilesChangeQueue.Change> changes, FilesTools tools,
                                  McpRegistry registry, FilesChangeQueue queue) {
        List<String> conflicts = new ArrayList<>();
        for (FilesChangeQueue.Change change : changes) {
            try {
                FilesTools.Snapshot current = tools.snapshot(change.path());
                if (change.create() && current.exists()) {
                    conflicts.add(change.path() + ": файл появился после предложения");
                } else if (!change.create() && !current.exists()) {
                    conflicts.add(change.path() + ": файл исчез после предложения");
                } else if (!change.create() && !current.sha256().equals(change.baseSha256())) {
                    conflicts.add(change.path() + ": файл изменился после предложения (sha256 не совпадает)");
                }
            } catch (FilesToolException e) {
                conflicts.add(change.path() + ": " + e.getMessage());
            }
        }
        if (!conflicts.isEmpty()) {
            return new ApplyReport(List.of(), null, null, paths(changes), conflicts);
        }
        List<String> applied = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            FilesChangeQueue.Change change = changes.get(i);
            String error = writeOne(change, registry);
            if (error != null) {
                return new ApplyReport(applied, change.path(), error, paths(changes.subList(i + 1, changes.size())),
                        List.of());
            }
            applied.add(change.path());
            queue.remove(change.id());
        }
        return new ApplyReport(applied, null, null, List.of(), List.of());
    }

    private static String writeOne(FilesChangeQueue.Change change, McpRegistry registry) {
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("path", change.path());
        args.put("content", change.newContent());
        if (change.create()) {
            args.put("createOnly", true);
        } else {
            args.put("expectedSha256", change.baseSha256());
        }
        try {
            McpClientComponent.ToolCallResult result = registry.call("files", "files_write", args);
            if (!result.error()) return null;
            return errorText(result.text());
        } catch (McpClientComponent.McpClientException e) {
            return e.getMessage() == null ? "ошибка MCP" : e.getMessage();
        }
    }

    private static String errorText(String json) {
        try {
            var node = JsonSupport.MAPPER.readTree(json);
            return node.has("error") ? node.get("error").asText() : json;
        } catch (Exception e) {
            return json;
        }
    }

    private void report(ApplyReport report, TerminalUi ui) {
        if (!report.conflicts().isEmpty()) {
            ui.showError("Расхождение, ничего не записано. Изменились файлы:");
            report.conflicts().forEach(line -> ui.showSystem("  " + line));
            ui.showSystem("Пересоздай предложение: /files <цель> или /files discard.");
            return;
        }
        if (!report.applied().isEmpty()) {
            ui.showSystem("Применено: " + String.join(", ", report.applied()));
        }
        if (report.failure() != null) {
            ui.showError("Сбой при записи " + report.failedPath() + ": " + report.failure());
            ui.showSystem("Не применено: " + String.join(", ", report.notApplied()));
            return;
        }
        ui.showSystem("Очередь применена.");
    }

    private void discard(TerminalUi ui) {
        int count = queue.size();
        queue.clear();
        ui.showSystem("Очередь очищена (изменений: " + count + "). Файлы не записывались.");
    }

    private void status(TerminalUi ui) {
        List<FilesChangeQueue.Change> changes = queue.list();
        ui.showSystem("В очереди: " + changes.size() + (changes.isEmpty() ? "" : " ("
                + String.join(", ", paths(changes)) + ")"));
        ui.showSystem("Последний журнал: " + latestJournal());
    }

    private String latestJournal() {
        if (!Files.isDirectory(journalDir)) return "нет";
        try (Stream<Path> files = Files.list(journalDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .max(java.util.Comparator.comparing(path -> path.getFileName().toString()))
                    .map(Path::toString).orElse("нет");
        } catch (IOException e) {
            return "нет";
        }
    }

    static boolean isDocumentation(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return name.startsWith("README") || path.startsWith("docs/");
    }

    private static List<String> paths(List<FilesChangeQueue.Change> changes) {
        return changes.stream().map(FilesChangeQueue.Change::path).toList();
    }
}
