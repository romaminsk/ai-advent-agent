package com.example.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Выбор LLM для ревью: облако (LLM_API_URL https + LLM_API_KEY), иначе локальная Ollama.
 * При сбое облака ревью повторяется на локальной модели. Ключ читается из окружения
 * или из файла .env с правами 600 и никогда не попадает в вывод.
 */
public final class ReviewLlmConfig {

    public static final String MODE_CLOUD = "облако";
    public static final String MODE_LOCAL = "локально";
    public static final String LOCAL_URL = "http://localhost:11434/v1/chat/completions";
    public static final String LOCAL_DEFAULT_MODEL = "qwen2.5:3b-rag8k";
    public static final int LOCAL_DEFAULT_WINDOW = 8192;
    /** Файл настроек по умолчанию (вне рабочей копии репозитория). */
    public static final String ENV_FILE_VARIABLE = "REVIEW_ENV_FILE";

    private static final Set<String> KEYS = Set.of("LLM_API_URL", "LLM_API_KEY", "LLM_MODEL",
            "LLM_CONTEXT_WINDOW_TOKENS", "REVIEW_LOCAL_MODEL", "REVIEW_LOCAL_CONTEXT_WINDOW_TOKENS",
            "EMBEDDING_BASE_URL", "EMBEDDING_MODEL");
    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Бэкенд: режим, модель, адрес и ключ. toString не содержит ключ. */
    public record Backend(String mode, String model, String url, String apiKey) {
        @Override
        public String toString() {
            return mode + ":" + model;
        }
    }

    /** Порядок попыток (облако, затем локально) и окно модели для бюджета (минимум по бэкендам). */
    public record Selection(List<Backend> backends, Integer windowTokens, List<String> warnings) {
    }

    private ReviewLlmConfig() {
    }

    /** Файл .env: REVIEW_ENV_FILE или ~/.ai-advent-agent/.env. */
    public static Path envFile(Map<String, String> env) {
        String configured = env.get(ENV_FILE_VARIABLE);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return Path.of(System.getProperty("user.home"), ".ai-advent-agent", ".env");
    }

    /** Читает только известные ключи; файл с правами, отличными от 600, игнорируется с предупреждением. */
    public static Map<String, String> readEnvFile(Path file, List<String> warnings) {
        Map<String, String> values = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return values;
        }
        try {
            if (!Files.getPosixFilePermissions(file).equals(OWNER_ONLY)) {
                warnings.add("файл настроек " + file + " должен иметь права 600; его значения не используются");
                return values;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.strip();
                int eq = text.indexOf('=');
                if (text.isEmpty() || text.startsWith("#") || eq <= 0) {
                    continue;
                }
                String key = text.substring(0, eq).strip();
                if (KEYS.contains(key)) {
                    values.put(key, unquote(text.substring(eq + 1).strip()));
                }
            }
        } catch (IOException | UnsupportedOperationException e) {
            warnings.add("файл настроек " + file + " не прочитан");
        }
        return values;
    }

    /** Значения из окружения имеют приоритет над файлом; пустые значения игнорируются. */
    public static Map<String, String> merged(Map<String, String> env, Map<String, String> file) {
        Map<String, String> result = new HashMap<>(file);
        for (String key : KEYS) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                result.put(key, value.strip());
            }
        }
        return result;
    }

    public static Selection select(Map<String, String> values) {
        List<String> warnings = new ArrayList<>();
        String localModel = orDefault(values.get("REVIEW_LOCAL_MODEL"), LOCAL_DEFAULT_MODEL);
        Integer localWindow = positive(values.get("REVIEW_LOCAL_CONTEXT_WINDOW_TOKENS"));
        if (localWindow == null) {
            localWindow = LOCAL_DEFAULT_WINDOW;
        }
        Backend local = new Backend(MODE_LOCAL, localModel, LOCAL_URL, "ollama");
        String url = values.get("LLM_API_URL");
        String key = values.get("LLM_API_KEY");
        String model = values.get("LLM_MODEL");
        List<Backend> backends = new ArrayList<>();
        Integer window = localWindow;
        if (url != null || key != null) {
            if (url == null || key == null) {
                warnings.add("облако не выбрано: нужны оба значения LLM_API_URL и LLM_API_KEY");
            } else if (!url.startsWith("https://")) {
                warnings.add("облако не выбрано: LLM_API_URL должен быть https");
            } else if (model == null) {
                warnings.add("облако не выбрано: не задан LLM_MODEL");
            } else {
                backends.add(new Backend(MODE_CLOUD, model, url, key));
                Integer cloudWindow = positive(values.get("LLM_CONTEXT_WINDOW_TOKENS"));
                if (cloudWindow != null) {
                    window = Math.min(cloudWindow, localWindow);
                }
            }
        }
        backends.add(local);
        return new Selection(backends, window, warnings);
    }

    /** Убирает значение ключа из текста ошибки (ключ может прийти в ответе сервера). */
    public static String scrub(String text, String apiKey) {
        if (apiKey == null || apiKey.length() < 8 || text == null) {
            return text;
        }
        return text.replace(apiKey, "[скрыто]");
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Integer positive(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
