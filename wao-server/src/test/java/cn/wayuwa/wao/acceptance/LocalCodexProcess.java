package cn.wayuwa.wao.acceptance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 本机验收使用真实 ChatGPT 登录，不读取或复制登录凭据。 */
final class LocalCodexProcess {
    private LocalCodexProcess() { }

    static String execute(String executable, String prompt, Path directory, Duration timeout) throws Exception {
        Files.createDirectories(directory);
        Path output = directory.resolve("result.txt");
        if (Files.exists(output)) throw new IllegalArgumentException("验收目录已有结果，拒绝复用");
        ProcessBuilder builder = new ProcessBuilder(List.of(executable, "exec", "--ignore-user-config",
                "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only",
                "--model", "gpt-6-astra", "--disable", "shell_tool", "--disable", "multi_agent",
                "-c", "forced_login_method=\"chatgpt\"", "-c", "web_search=\"disabled\"",
                "--cd", directory.toAbsolutePath().toString(), "--color", "never",
                "--output-last-message", output.toAbsolutePath().toString(), "-"));
        // 防止父进程中的 API 配置把验收切到额外付费调用。
        builder.environment().remove("OPENAI_API_KEY");
        builder.environment().remove("CODEX_API_KEY");
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("events.log").toFile());
        Process process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(("只处理以下合成验收小记，不调用工具，不读取文件。\n" + prompt).getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("本机 Codex 执行超时，请重试；原文已保留");
            if (process.exitValue() != 0)
                throw new IllegalStateException("本机 Codex 执行失败（退出码 " + process.exitValue() + "），请检查本机登录与网络后重试；原文已保留");
            if (!Files.isRegularFile(output) || Files.size(output) > 120_000)
                throw new IllegalStateException("本机 Codex 未返回有效正文；原文已保留");
            String result = Files.readString(output).trim();
            if (result.isBlank() || result.length() > 30_000)
                throw new IllegalStateException("本机 Codex 返回正文为空或过长；原文已保留");
            return result;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
