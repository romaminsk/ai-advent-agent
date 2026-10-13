package com.example.review;

import com.example.assistant.GitReadTools;

import java.util.Locale;
import java.util.Set;

/** Причины пропуска файла из ревью: секреты, бинарные, lock- и сгенерированные файлы. */
public final class ReviewFilter {

    private static final Set<String> LOCK_NAMES = Set.of(
            "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml",
            "composer.lock", "Gemfile.lock", "poetry.lock", "Pipfile.lock", "Cargo.lock",
            "go.sum", "gradle.lockfile", "packages.lock.json");
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "jar", "class",
            "war", "tar", "gz", "tgz", "7z", "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4",
            "mov", "avi", "exe", "dll", "so", "dylib", "bin", "keystore", "p12");
    private static final Set<String> GENERATED_SEGMENTS = Set.of(
            "target", "build", "dist", "node_modules", "generated", "generated-sources");

    private ReviewFilter() {
    }

    /** Причина пропуска или null, если файл можно анализировать. */
    public static String skipReason(String path, boolean binary) {
        if (GitReadTools.isSecretPath(path)) {
            return "файл с секретами (.env), содержимое не отправляется";
        }
        if (binary || hasBinaryExtension(path)) {
            return "бинарный файл";
        }
        if (isLock(path)) {
            return "lock-файл";
        }
        if (isGenerated(path)) {
            return "сгенерированный файл";
        }
        return null;
    }

    static boolean isLock(String path) {
        String name = fileName(path);
        return LOCK_NAMES.contains(name) || name.endsWith(".lock") || name.endsWith(".lockfile");
    }

    static boolean isGenerated(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css") || lower.endsWith(".map")
                || lower.endsWith(".pb.go") || lower.endsWith("_pb2.py") || lower.endsWith(".g.dart")) {
            return true;
        }
        for (String segment : lower.split("/")) {
            if (GENERATED_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasBinaryExtension(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot > 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
