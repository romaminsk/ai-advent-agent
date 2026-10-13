package com.example.review;

import java.nio.file.Path;

/** Параметры «ai-agent review»: --base, --head, --project, --out, --max-diff. */
public record ReviewOptions(String base, String head, String project, Path out, Integer maxDiff) {

    public static final String DEFAULT_BASE = "origin/main";
    public static final String DEFAULT_HEAD = "HEAD";

    public static ReviewOptions parse(String[] args) {
        String base = DEFAULT_BASE;
        String head = DEFAULT_HEAD;
        String project = null;
        Path out = null;
        Integer maxDiff = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String name = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }
            switch (name) {
                case "--base", "--head", "--project", "--out", "--max-diff" -> {
                    String value;
                    if (inline != null) {
                        value = inline;
                    } else {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Ошибка: после " + name + " нужно значение.");
                        }
                        value = args[++i];
                    }
                    if (value.isBlank()) {
                        throw new IllegalArgumentException("Ошибка: пустое значение для " + name + ".");
                    }
                    switch (name) {
                        case "--base" -> base = value.strip();
                        case "--head" -> head = value.strip();
                        case "--project" -> project = value;
                        case "--out" -> out = Path.of(value);
                        default -> maxDiff = positive(value, name);
                    }
                }
                default -> throw new IllegalArgumentException("Ошибка: неизвестный аргумент review: " + arg);
            }
        }
        return new ReviewOptions(base, head, project, out, maxDiff);
    }

    private static int positive(String value, String name) {
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed <= 0) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ошибка: " + name + " должен быть положительным целым, получено: " + value);
        }
    }
}
