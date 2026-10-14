package com.example.support;

import java.nio.file.Path;

/** Каталоги support: данные CRM, корпус документации и отдельный индекс. Тесты переопределяют через системные свойства. */
final class SupportPaths {

    static final String CRM_DIR_PROPERTY = "ai-agent.crm-dir";
    static final String PROJECT_DIR_PROPERTY = "ai-agent.project-dir";
    static final String DOCS_DIR_PROPERTY = "ai-agent.support-docs-dir";
    static final String INDEX_DIR_PROPERTY = "ai-agent.support-index-dir";

    private SupportPaths() {
    }

    static Path projectDir() {
        String property = System.getProperty(PROJECT_DIR_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        String env = System.getenv("PROJECT_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env.strip());
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    static Path crmDir() {
        return configured(CRM_DIR_PROPERTY, ".ai-advent-agent", "crm");
    }

    static Path demoCrmDir() {
        return projectDir().resolve("demo").resolve("crm");
    }

    static Path supportDocsDir() {
        String property = System.getProperty(DOCS_DIR_PROPERTY);
        return property != null && !property.isBlank()
                ? Path.of(property) : projectDir().resolve("support-docs");
    }

    static Path supportIndexDir() {
        return configured(INDEX_DIR_PROPERTY, ".ai-advent-agent", "support-index");
    }

    private static Path configured(String property, String first, String second) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            return Path.of(value);
        }
        return Path.of(System.getProperty("user.home"), first, second);
    }
}
