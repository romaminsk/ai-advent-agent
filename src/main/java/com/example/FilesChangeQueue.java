package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Очередь предложенных изменений в памяти процесса; на диск ничего не пишет. */
final class FilesChangeQueue {
    record Change(int id, String kind, String path, String baseSha256, String newContent,
                  String diff, int added, int removed) {
        boolean create() { return "create".equals(kind); }
    }

    private final List<Change> changes = new ArrayList<>();
    private int nextId = 1;

    synchronized List<Change> list() {
        return List.copyOf(changes);
    }

    synchronized int size() {
        return changes.size();
    }

    synchronized Optional<Change> forPath(String path) {
        return changes.stream().filter(change -> change.path().equals(path)).findFirst();
    }

    /**
     * Добавляет или обновляет запись по пути. Для существующей записи сохраняются id, вид
     * и baseSha256: несколько правок одного файла кумулятивны относительно исходного файла.
     */
    synchronized Change put(String kind, String path, String baseSha256, String newContent,
                            String diff, int added, int removed) {
        Optional<Change> existing = forPath(path);
        Change change;
        if (existing.isPresent()) {
            Change current = existing.get();
            change = new Change(current.id(), current.kind(), path, current.baseSha256(), newContent,
                    diff, added, removed);
            changes.set(changes.indexOf(current), change);
        } else {
            change = new Change(nextId++, kind, path, baseSha256, newContent, diff, added, removed);
            changes.add(change);
        }
        return change;
    }

    synchronized void remove(int id) {
        changes.removeIf(change -> change.id() == id);
    }

    synchronized void clear() {
        changes.clear();
    }
}
