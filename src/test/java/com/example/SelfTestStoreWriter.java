package com.example;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Помощник SelfTest: пишет в monitor store из отдельного JVM-процесса,
 * повторяя операции при временной блокировке другим процессом.
 * Аргументы: путь к git-monitor.json, число расписаний для этого процесса.
 */
public final class SelfTestStoreWriter {
    public static void main(String[] args) throws Exception {
        Path storeFile = Path.of(args[0]);
        int count = Integer.parseInt(args[1]);
        char role = args.length > 2 ? args[2].charAt(0) : 'a';
        MonitorStore store = new MonitorStore(storeFile);
        int done = 0;
        for (int i = 0; i < count; i++) {
            String root = "repo-" + role + "-" + ProcessHandle.current().pid() + "-" + i;
            boolean saved = false;
            for (int attempt = 0; attempt < 500 && !saved; attempt++) {
                try {
                    store.create(root, Duration.ofSeconds(30), Duration.ofMinutes(1));
                    saved = true;
                } catch (MonitorException busy) {
                    if (attempt == 499) throw new IllegalStateException("store занят слишком долго");
                    Thread.sleep(20);
                }
            }
            done++;
        }
        System.out.println("DONE " + done);
    }
}
