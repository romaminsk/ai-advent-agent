package com.example;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FilesChecks extends SelfTestSupport {
    static void run() throws Exception {
        Path base = Files.createTempDirectory("selftest-files-");
        Path outside = Files.createTempDirectory("selftest-files-outside-");
        Path project = base.resolve("project");
        Files.createDirectories(project);
        seed(project);
        Files.writeString(outside.resolve("secret.txt"), "outside");

        group("files: песочница");
        checkSandbox(project, outside);
        group("files: маскирование и запись секретов");
        checkMasking(project);
        group("files: list/read/search");
        checkListReadSearch(project);
        group("files: diff");
        checkDiff(project);
        group("files: write");
        checkWrite(project);
        group("files: MCP stdio");
        checkMcpRoundtrip(project);
    }

    private static void seed(Path project) throws Exception {
        write(project, "docs/a.md", "# A\nMcpClientComponent живёт здесь\nстрока три\n");
        write(project, "src/main/java/com/example/B.java", "class B {\n  McpClientComponent client;\n}\n");
        write(project, "src/main/java/com/example/C.java", "class C { // McpClientComponent\n}\n");
        write(project, "README.md", "readme без совпадений\n");
        write(project, "pom.xml", "<project/>\n");
        write(project, "build.sh", "echo ok\n");
        write(project, ".env", "API_KEY=sk-abcdefghijklmnopqrstuv\n");
        write(project, ".git/config", "[core]\n");
        write(project, ".idea/vcs.xml", "<x/>\n");
        write(project, "artifacts/report.md", "McpClientComponent в артефактах\n");
        write(project, "target/classes/T.java", "McpClientComponent в target\n");
        write(project, ".github/ci.yml", "name: ci\n");
        write(project, "bin/data.bin", "text\u0000binary");
        Files.writeString(project.resolve("big.md"), "x".repeat(210 * 1024));
        write(project, "config/notes.txt", "первая строка\r\nвторая строка\r\n");
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static boolean rejects(Runnable action) {
        try {
            action.run();
            return false;
        } catch (FilesToolException e) {
            return true;
        }
    }

    private static void checkSandbox(Path project, Path outside) throws Exception {
        FilesSandbox sandbox = new FilesSandbox(project);
        FilesTools tools = new FilesTools(sandbox);
        expect("../ выходит за корень и отклоняется", rejects(() -> tools.read("../x.md", null, null, null)));
        expect("абсолютный путь вне корня отклоняется",
                rejects(() -> tools.read(outside.resolve("secret.txt").toString(), null, null, null)));
        Files.createSymbolicLink(project.resolve("link.md"), outside.resolve("secret.txt"));
        expect("симлинк наружу не читается", rejects(() -> tools.read("link.md", null, null, null)));
        expect(".env не читается", rejects(() -> tools.read(".env", null, null, null)));
        expect(".git не читается", rejects(() -> tools.read(".git/config", null, null, null)));
        expect(".idea не читается", rejects(() -> tools.read(".idea/vcs.xml", null, null, null)));
        expect("artifacts не читается", rejects(() -> tools.read("artifacts/report.md", null, null, null)));
        expect("большой файл не читается", rejects(() -> tools.read("big.md", null, null, null)));
        expect("бинарный файл не читается", rejects(() -> tools.read("bin/data.bin", null, null, null)));
        expect("запись в .github отклоняется",
                rejects(() -> tools.write(".github/ci.yml", "x: 1\n", null, null)));
        expect("запись в pom.xml отклоняется", rejects(() -> tools.write("pom.xml", "<p/>\n", null, null)));
        expect("запись в *.sh отклоняется", rejects(() -> tools.write("build.sh", "x\n", null, null)));
        expect("запись в .env отклоняется", rejects(() -> tools.write(".env", "A=1\n", null, null)));
        expect("запись в .gitignore отклоняется",
                rejects(() -> tools.write(".gitignore", "target/\n", null, null)));
        expect("запись бинарного расширения отклоняется",
                rejects(() -> tools.write("docs/image.png", "x\n", null, null)));
        expect("запись в target отклоняется",
                rejects(() -> tools.write("target/x.md", "x\n", null, null)));
        expect("list не показывает .env, .git, artifacts и target",
                noSensitive(tools.list(".", null, 1000)));
        Map<String, Object> search = tools.search("McpClientComponent", false, false, null, 100);
        expect("search не видит artifacts и target и .env",
                !search.toString().contains("artifacts") && !search.toString().contains("target/")
                        && !search.toString().contains(".env"));
        expect("симлинк не попадает в list", !tools.list(".", null, 1000).toString().contains("link.md"));
    }

    private static boolean noSensitive(Map<String, Object> listing) {
        String text = listing.toString();
        return !text.contains(".env") && !text.contains(".git/") && !text.contains(".idea/")
                && !text.contains("artifacts/") && !text.contains("target/") && !text.contains(".github/");
    }

    private static void checkMasking(Path project) throws Exception {
        String masked = FilesSandbox.mask("api_key=abc123 Bearer tok-999 ключ sk-abcdefghijklmnopqrst");
        expect("ключ маскируется, имя ключа остаётся", masked.contains("api_key=<скрыто>"));
        expect("Bearer маскируется", masked.contains("Bearer <скрыто>") && !masked.contains("tok-999"));
        expect("sk-ключ маскируется", !masked.contains("sk-abcdefghijklmnopqrst"));
        FilesTools tools = new FilesTools(new FilesSandbox(project));
        write(project, "docs/secret-note.md", "password: hunter2\n");
        Map<String, Object> read = tools.read("docs/secret-note.md", null, null, null);
        expect("файл с секретом возвращается маскированным", !read.get("text").toString().contains("hunter2"));
        expect("запись секретоподобного текста отклоняется",
                rejects(() -> tools.write("docs/new.md", "token = abcdef123456\n", null, null)));
        expect("секреты не попадают в результат поиска",
                !tools.search("hunter2", false, false, null, 10).toString().contains("hunter2"));
    }

    private static void checkListReadSearch(Path project) throws Exception {
        FilesTools tools = new FilesTools(new FilesSandbox(project));
        Map<String, Object> listing = tools.list(".", "*.md", 200);
        List<String> paths = paths(listing.get("files"));
        expect("list сортирует по пути", paths.equals(sorted(paths)) && !paths.isEmpty());
        expect("list с glob выбирает markdown в корне и docs", paths.contains("README.md")
                && paths.contains("docs/a.md") && !paths.contains("pom.xml"));
        Map<String, Object> limited = tools.list(".", null, 2);
        expect("list maxResults обрезает и помечает truncated",
                Boolean.TRUE.equals(limited.get("truncated")) && paths(limited.get("files")).size() == 2);

        Map<String, Object> read = tools.read("docs/a.md", 2, 2, null);
        expect("read диапазона возвращает одну строку",
                "McpClientComponent живёт здесь".equals(read.get("text")) && read.get("fromLine").equals(2));
        String sha = FilesSandbox.sha256(Files.readAllBytes(project.resolve("docs/a.md")));
        expect("read возвращает sha256 всего файла", sha.equals(read.get("sha256")));
        expect("read считает строки файла", read.get("totalLines").equals(3));
        Map<String, Object> cut = tools.read("docs/a.md", 1, null, 10);
        expect("read с maxChars помечает truncated", Boolean.TRUE.equals(cut.get("truncated")));
        expect("read: fromLine больше числа строк — ошибка",
                rejects(() -> tools.read("docs/a.md", 99, null, null)));

        Map<String, Object> search = tools.search("McpClientComponent", false, false, null, 50);
        List<String> hits = matchPaths(search.get("matches"));
        expect("search находит совпадения в нескольких файлах",
                hits.contains("docs/a.md") && hits.contains("src/main/java/com/example/B.java")
                        && hits.contains("src/main/java/com/example/C.java"));
        expect("search порядок: путь, затем строка", hits.equals(sorted(hits)));
        expect("search регистронезависимый по умолчанию",
                tools.search("mcpclientcomponent", false, false, null, 50).get("totalMatches").equals(3));
        expect("search с caseSensitive не находит другой регистр",
                tools.search("mcpclientcomponent", false, true, null, 50).get("totalMatches").equals(0));
        expect("search regex работает",
                tools.search("Mcp\\w+Component", true, false, null, 50).get("totalMatches").equals(3));
        Map<String, Object> capped = tools.search("McpClientComponent", false, false, null, 1);
        expect("search maxResults помечает truncated",
                Boolean.TRUE.equals(capped.get("truncated")) && paths(capped.get("matches")).size() == 1);
        expect("search: неверный regex — ошибка",
                rejects(() -> tools.search("(", true, false, null, 5)));
        expect("search: строки обрезаются до 200 символов", longLineTruncated(project, tools));
    }

    private static boolean longLineTruncated(Path project, FilesTools tools) throws Exception {
        write(project, "docs/long.md", "маркер " + "y".repeat(400) + "\n");
        Map<String, Object> result = tools.search("маркер", false, false, "docs/long.md", 5);
        List<?> matches = (List<?>) result.get("matches");
        return matches.size() == 1 && ((Map<?, ?>) matches.get(0)).get("text").toString().length() <= 200;
    }

    private static void checkDiff(Path project) throws Exception {
        FilesTools tools = new FilesTools(new FilesSandbox(project));
        Map<String, Object> changed = tools.diff("docs/a.md", "# A\nMcpClientComponent живёт здесь\nновая строка\n");
        expect("diff: изменение найдено", Boolean.TRUE.equals(changed.get("changed"))
                && changed.get("added").equals(1) && changed.get("removed").equals(1));
        String diff = changed.get("diff").toString();
        expect("diff: unified-заголовки и строки", diff.startsWith("--- a/docs/a.md\n+++ b/docs/a.md\n")
                && diff.contains("-строка три") && diff.contains("+новая строка"));
        Map<String, Object> same = tools.diff("docs/a.md", Files.readString(project.resolve("docs/a.md")));
        expect("diff: без изменений changed=false", Boolean.FALSE.equals(same.get("changed")));
        Map<String, Object> created = tools.diff("docs/new.md", "новый\n");
        expect("diff: несуществующий файл — exists=false и добавление",
                Boolean.FALSE.equals(created.get("exists")) && created.get("added").equals(1));
        expect("diff не пишет на диск", !Files.exists(project.resolve("docs/new.md")));
    }

    private static void checkWrite(Path project) throws Exception {
        FilesTools tools = new FilesTools(new FilesSandbox(project));
        Map<String, Object> created = tools.write("docs/created.md", "создан\n", null, true);
        expect("write создаёт новый файл", Boolean.TRUE.equals(created.get("created"))
                && Files.readString(project.resolve("docs/created.md")).equals("создан\n"));
        expect("write createOnly отказывает для существующего",
                rejects(() -> tools.write("docs/created.md", "x\n", null, true)));
        String sha = FilesSandbox.sha256(Files.readAllBytes(project.resolve("docs/created.md")));
        expect("write с неверным expectedSha256 отказывает",
                rejects(() -> tools.write("docs/created.md", "y\n", "0".repeat(64), null)));
        Map<String, Object> updated = tools.write("docs/created.md", "обновлён\n", sha, null);
        expect("write с верным expectedSha256 записывает",
                Files.readString(project.resolve("docs/created.md")).equals("обновлён\n")
                        && updated.get("sha256").equals(FilesSandbox.sha256(Files.readAllBytes(
                        project.resolve("docs/created.md")))));
        Path target = project.resolve("docs/created.md");
        Files.setPosixFilePermissions(target, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
        tools.write("docs/created.md", "права\n", null, null);
        expect("write сохраняет права существующего файла",
                Files.getPosixFilePermissions(target).contains(PosixFilePermission.GROUP_READ)
                        && !Files.getPosixFilePermissions(target).contains(PosixFilePermission.OTHERS_READ));
        Map<String, Object> crlf = tools.write("config/notes.txt", "один\nдва\nтри", null, null);
        String notes = Files.readString(project.resolve("config/notes.txt"));
        expect("write сохраняет CRLF и финальный перевод строки",
                notes.equals("один\r\nдва\r\nтри\r\n") && crlf.get("bytes").equals(notes.getBytes(
                        StandardCharsets.UTF_8).length));
        expect("temp-файлы не остаются после записи",
                noTmpFiles(project));
    }

    private static boolean noTmpFiles(Path project) throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.walk(project)) {
            return stream.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }

    private static void checkMcpRoundtrip(Path project) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String command = "\"" + javaBin + "\" -cp \"" + withChildClasspath() + "\" "
                + FilesMcpServer.class.getName() + " " + FilesMcpServer.PROJECT_ARGUMENT
                + " \"" + project + "\"";
        McpClientComponent client = new McpClientComponent();
        McpClientComponent.ToolCallResult listed = client.callTool(command, "files_list",
                Map.of("path", "docs"));
        expect("MCP files_list возвращает файлы каталога", !listed.error() && listed.text().contains("docs/a.md"));
        McpClientComponent.ToolCallResult escape = client.callTool(command, "files_read",
                Map.of("path", "../outside.md"));
        expect("MCP ошибка возвращается структурированно",
                escape.error() && escape.text().contains("\"error\""));
        McpClientComponent.ToolCallResult unknown = client.callTool(command, "files_read",
                Map.of("path", "docs/a.md", "bogus", 1));
        expect("MCP неизвестный аргумент отклоняется", unknown.error());
        McpClientComponent.ToolListResult listing = client.listTools(command);
        List<String> names = new ArrayList<>();
        listing.tools().forEach(tool -> names.add(tool.name()));
        expect("MCP files регистрирует ровно пять инструментов",
                names.size() == 5 && names.containsAll(FilesMcpServer.TOOL_NAMES));
    }

    private static List<String> paths(Object files) {
        List<String> out = new ArrayList<>();
        for (Object item : (List<?>) files) out.add(((Map<?, ?>) item).get("path").toString());
        return out;
    }

    private static List<String> matchPaths(Object matches) {
        return paths(matches);
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(null);
        return copy;
    }
}
