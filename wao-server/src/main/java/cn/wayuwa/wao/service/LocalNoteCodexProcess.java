package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 小记专用本机 CLI：沿用 ChatGPT 登录，禁用脚本、外部 MCP 和额外付费 API。 */
final class LocalNoteCodexProcess {
    private static final Set<String> ENVIRONMENT = Set.of("SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "COMSPEC",
            "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "PATH", "PATHEXT",
            "CODEX_HOME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY");

    record Result(String text, byte[] png) { }

    static List<String> command(String executable, String model, Path directory, boolean image) {
        var args = new ArrayList<>(List.of(executable, "exec", "--ignore-user-config", "--ignore-rules",
                "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only", "--model", model,
                "--disable", "shell_tool", "--disable", "multi_agent", "--disable", "view_image",
                "-c", "forced_login_method=\"chatgpt\"", "-c", "web_search=\"disabled\"",
                "--cd", directory.toString(), "--json", "--color", "never",
                "--output-last-message", directory.resolve("result.txt").toString()));
        args.addAll(List.of(image ? "--enable" : "--disable", "image_generation", "-"));
        return args;
    }

    static Result execute(String executable, String model, String prompt, Path root, Duration timeout,
            boolean image) throws Exception {
        Path directory = Files.createTempDirectory(root, "job-");
        Path events = directory.resolve("events.jsonl");
        Process process = null;
        try {
            var builder = new ProcessBuilder(command(LocalCodexExecutable.resolve(executable), model, directory, image));
            builder.environment().keySet().removeIf(key -> !ENVIRONMENT.contains(key.toUpperCase(java.util.Locale.ROOT)));
            builder.redirectErrorStream(true).redirectOutput(events.toFile());
            process = builder.start();
            try (var input = process.getOutputStream()) { input.write(prompt.getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("本机 AI 执行超时；原文已保留，请稍后重试");
            if (process.exitValue() != 0)
                throw new IllegalStateException("本机 Codex 执行失败，请检查 ChatGPT 登录、网络和额度后重试；原文已保留");
            if (image) return new Result(null, generatedPng(events));
            Path output = directory.resolve("result.txt");
            if (!Files.isRegularFile(output) || Files.size(output) > 120_000)
                throw new IllegalStateException("本机 AI 未返回有效正文；原文已保留，请重试");
            String text = Files.readString(output).trim();
            if (text.isEmpty() || text.length() > 30_000)
                throw new IllegalStateException("本机 AI 返回正文为空或过长；原文已保留，请重试");
            return new Result(text, null);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            // 只清理由本次 createTempDirectory 创建的工作目录，不遍历或复制登录凭据。
            // Windows 子进程的输出句柄可能稍晚释放，清理失败不能覆盖原始超时 / 取消原因。
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException ignored) { /* 留在本机专用目录，按登记归属处理。 */ }
        }
    }

    private static byte[] generatedPng(Path events) throws Exception {
        String home = System.getenv("CODEX_HOME");
        Path generated = (home == null || home.isBlank() ? Path.of(System.getProperty("user.home"), ".codex")
                : Path.of(home)).resolve("generated_images");
        String thread = threadId(events);
        if (thread == null) throw new IllegalStateException("本机 AI 未生成可确认归属的图片，请检查图片工具后重试");
        Path directory = generated.resolve(thread);
        Path png = uniquePng(generated, directory);
        byte[] bytes = Files.readAllBytes(png);
        ArticleImageService.validatePng(bytes);
        // 文件由 CLI 本次 thread.started 对应的工具生成，绝不采用模型最终回答中的路径。
        Files.delete(png);
        try { Files.delete(directory); } catch (java.nio.file.DirectoryNotEmptyException ignored) { }
        return bytes;
    }

    static String threadId(Path events) throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (var lines = Files.lines(events)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("{")) continue;
                var event = json.readTree(line);
                String id = event.path("thread_id").asText();
                if ("thread.started".equals(event.path("type").asText())
                        && id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) return id;
            }
        }
        return null;
    }

    static Path uniquePng(Path generated, Path directory) throws Exception {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(generated) || Files.isSymbolicLink(directory)
                || !directory.toRealPath().getParent().equals(generated.toRealPath()))
            throw new IllegalStateException("本机图片工具未返回本次生成的 PNG，请检查图片能力后重试");
        List<Path> candidates;
        try (var files = Files.list(directory)) {
            candidates = files.filter(path -> path.getFileName().toString().endsWith(".png")).toList();
        }
        if (candidates.size() != 1) throw new IllegalStateException("本次配图结果为空或多于一张，请重试");
        Path png = candidates.get(0);
        if (!Files.isRegularFile(png, LinkOption.NOFOLLOW_LINKS) || Files.size(png) > ImageService.MAX_BYTES)
            throw new IllegalStateException("本次生成图片无效或超过 10MB，请重试");
        return png;
    }
}
