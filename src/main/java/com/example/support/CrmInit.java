package com.example.support;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Команда «ai-agent crm-init»: копирует демо-данные CRM в ~/.ai-advent-agent/crm, если каталог пуст. */
public final class CrmInit {

    private static final List<String> FILES = List.of(CrmStore.CUSTOMERS_FILE, CrmStore.TICKETS_FILE);

    private CrmInit() {
    }

    public static int run(PrintStream out, PrintStream err) {
        Path target = SupportPaths.crmDir();
        Path source = SupportPaths.demoCrmDir();
        try {
            if (hasFiles(target)) {
                out.println("crm-init: в " + target + " уже есть данные, копирование пропущено.");
                return 0;
            }
            for (String file : FILES) {
                if (!Files.isRegularFile(source.resolve(file))) {
                    err.println("crm-init: не найден демо-файл " + source.resolve(file));
                    return 1;
                }
            }
            Files.createDirectories(target);
            for (String file : FILES) {
                Files.copy(source.resolve(file), target.resolve(file));
            }
            out.println("crm-init: данные скопированы из " + source + " в " + target);
            return 0;
        } catch (IOException e) {
            err.println("crm-init: " + e.getMessage());
            return 1;
        }
    }

    private static boolean hasFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isPresent();
        }
    }
}
