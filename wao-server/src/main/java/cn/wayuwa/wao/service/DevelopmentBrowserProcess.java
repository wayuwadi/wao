package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 运维固定的只读浏览器验收助手；只管理本次子进程，不提供任意 host shell。 */
final class DevelopmentBrowserProcess implements AutoCloseable {
    static final int MAX_SCRIPT_BYTES = 100_000;
    private static final String PORT_PREFIX = "ACCEPTANCE_BRIDGE_PORT=";
    private static final Set<String> ENV = Set.of("SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "COMSPEC",
            "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "PATH", "PATHEXT",
            "JAVA_HOME", "PYTHONUTF8", "PYTHONIOENCODING", "CODEX_HOME", "GH_CONFIG_DIR",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMW6432");
    @FunctionalInterface interface Starter { Process start(ProcessBuilder builder) throws Exception; }
    private final Process process;
    private final Thread reader;
    private final int port;

    private DevelopmentBrowserProcess(Process process, Thread reader, int port) {
        this.process = process; this.reader = reader; this.port = port;
    }
    /** 只接受 state 根目录预先配置的脚本；模型可写的 worktree/output 不能提供 host 代码。 */
    static DevelopmentBrowserProcess start(Path state, String node, String script, Path worktree, Path output) throws Exception {
        return start(state, node, script, worktree, output, Duration.ofSeconds(60), ProcessBuilder::start);
    }
    static DevelopmentBrowserProcess start(Path state, String node, String script, Path worktree, Path output,
            Duration timeout, Starter starter) throws Exception {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("浏览器助手启动等待须在 60 秒内");
        Path root = state.toAbsolutePath().normalize().toRealPath();
        Path trusted = validateScript(state, script);
        Path work = containedDirectory(state, root, worktree), artifacts = containedDirectory(state, root, output);
        if (work.equals(root) || artifacts.equals(root) || trusted.startsWith(work) || trusted.startsWith(artifacts))
            throw new IllegalStateException("浏览器助手脚本不能来自本次工作区或可写产物目录");
        String executable = validateNode(node);
        var builder = new ProcessBuilder(List.of(executable, trusted.toString(), work.toString(), artifacts.toString()))
                .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key -> !ENV.contains(key.toUpperCase(Locale.ROOT)));
        Process process = starter.start(builder);
        var ready = new CompletableFuture<Integer>();
        Thread reader = new Thread(() -> readReady(process, ready), "development-browser-ready");
        reader.setDaemon(true); reader.start();
        try {
            int port = ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!process.isAlive()) throw new IllegalStateException("浏览器验收助手在就绪后退出");
            return new DevelopmentBrowserProcess(process, reader, port);
        } catch (InterruptedException interrupted) {
            stop(process, reader); Thread.currentThread().interrupt(); throw interrupted;
        } catch (Exception failed) {
            stop(process, reader);
            throw new IllegalStateException("浏览器验收助手未在限定时间内正常就绪；产物已保留", failed);
        }
    }

    static Path validateScript(Path state, String configured) throws Exception {
        if (configured == null || configured.isBlank()) throw new IllegalStateException("未配置浏览器验收脚本");
        Path path = Path.of(configured);
        if (!path.isAbsolute()) path = state.resolve(path);
        path = path.toAbsolutePath().normalize();
        Path root = state.toAbsolutePath().normalize();
        if (!path.getFileName().toString().endsWith(".mjs") || !path.getParent().equals(root)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().getParent().equals(root.toRealPath())
                || !path.toRealPath().equals(path))
            throw new IllegalStateException("浏览器脚本必须是 state 根目录配置的普通 .mjs 文件，不能来自工作区、产物或链接");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_SCRIPT_BYTES + 1);
        }
        if (bytes.length > MAX_SCRIPT_BYTES) throw new IllegalStateException("浏览器脚本不得超过 100KB");
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalStateException("浏览器脚本必须使用有效 UTF-8", invalid);
        }
        return path;
    }
    private static Path containedDirectory(Path state, Path root, Path directory) throws Exception {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(state.toAbsolutePath().normalize()) || !Files.isDirectory(normalized)
                || !normalized.toRealPath().startsWith(root))
            throw new IllegalStateException("浏览器工作区和产物目录必须位于本执行器 state 内");
        return normalized.toRealPath();
    }
    private static String validateNode(String configured) throws Exception {
        if (configured == null || configured.isBlank()) return "node";
        if (Set.of("node", "node.exe").contains(configured)) return configured;
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Set.of("node", "node.exe").contains(path.getFileName().toString().toLowerCase(Locale.ROOT))
                || !Files.isRegularFile(path) || !Files.isExecutable(path))
            throw new IllegalStateException("浏览器执行器只允许已安装的 Node 可执行文件");
        return path.toRealPath().toString();
    }
    private static void readReady(Process process, CompletableFuture<Integer> ready) {
        // 只保留有界的 ready 元数据；stdout/stderr 的页面、账号和工具内容不落日志或文件。
        try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            var line = new StringBuilder(); boolean overflow = false;
            for (int value; (value = input.read()) != -1;) {
                if (value == '\n') {
                    if (!overflow) acceptPort(line.toString().replace("\r", ""), ready);
                    line.setLength(0); overflow = false;
                } else if (line.length() < 80 && !overflow) line.append((char)value);
                else overflow = true;
            }
            if (!overflow && !line.isEmpty()) acceptPort(line.toString().replace("\r", ""), ready);
        } catch (Exception ignored) { /* 仅返回通用失败，不暴露助手输出。 */ }
        ready.completeExceptionally(new IllegalStateException("浏览器助手退出且未提供有效 ready 端口"));
    }
    private static void acceptPort(String line, CompletableFuture<Integer> ready) {
        if (!line.startsWith(PORT_PREFIX) || ready.isDone()) return;
        String value = line.substring(PORT_PREFIX.length());
        if (!value.matches("[0-9]{1,5}")) { ready.completeExceptionally(new IllegalStateException("浏览器助手端口无效")); return; }
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) ready.completeExceptionally(new IllegalStateException("浏览器助手端口超界"));
        else ready.complete(port);
    }
    static boolean applies(boolean analysis, String script, Path output) {
        return !analysis && script != null && !script.isBlank() && output.getFileName().toString().equals("verification");
    }
    String instructions() {
        return "\n本轮只读浏览器验收助手已就绪：http://127.0.0.1:" + port
                + "。使用固定 POST /check 的 JSON 契约：{path:'/automation?tab=development',viewport:{width:390,height:844},theme:'light',visibleTexts:['实际应出现文本'],clickSelectors:['UI选择器']}；theme只能为light或dark，path只能为已允许Web路由。"
                + "\n该 POST 只触发浏览器读取/检查，不授权业务写入。助手拒绝业务写请求、外域、凭据路径和任意JS/shell；只允许同origin回环GET/HEAD与固定Web路由。clickSelectors仅用于允许UI交互，不能用于业务保存/删除。"
                + "\n截图自动保存为本次产物目录browser-proof-N.png及proofJSON，须读取实际结果并记录validation与可核对产物。不能将助手就绪或HTTP成功当成验收通过；失败写blockedReason并停止。禁止改写固定助手脚本或启动另一个host进程。";
    }
    /** 外层读取助手内存证据；模型可写的 JSON 文件或自报成功不能替代冻结提交与截图校验。 */
    JsonNode verificationEvidence(String testedHead, Path output) throws Exception {
        if (!process.isAlive() || testedHead == null || !testedHead.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("浏览器验收助手已退出或缺少冻结提交");
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/summary"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (var input = response.body()) {
            var content = new CompletableFuture<byte[]>();
            var read = new Thread(() -> {
                try { content.complete(input.readNBytes(100_001)); }
                catch (Exception failed) { content.completeExceptionally(new IllegalStateException("浏览器助手摘要读取失败")); }
            }, "development-browser-summary");
            read.setDaemon(true); read.start();
            try { body = content.get(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            catch (Exception failed) { throw new IllegalStateException("浏览器助手摘要读取未在限定时间内完成"); }
        }
        if (response.statusCode() != 200 || body.length > 100_000)
            throw new IllegalStateException("浏览器助手未提供有效内存验收摘要");
        JsonNode summary;
        try { summary = new ObjectMapper().readTree(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("浏览器助手内存验收摘要格式无效");
        }
        if (summary == null || !summary.isObject() || !testedHead.equals(summary.path("testedHead").asText()))
            throw new IllegalStateException("浏览器助手内存摘要与冻结提交不一致");
        var accepted = new ObjectMapper().createArrayNode();
        JsonNode proofs = summary.path("proofs");
        if (!proofs.isArray()) throw new IllegalStateException("浏览器助手内存验收摘要格式无效");
        Path artifacts = output.toAbsolutePath().normalize().toRealPath();
        for (JsonNode proof : proofs) {
            if (!proof.path("passed").isBoolean() || !proof.path("passed").booleanValue()) continue;
            if (!testedHead.equals(proof.path("testedHead").asText()) || !allChecksPassed(proof.path("checks"))
                    || !proof.path("consoleErrorCount").isIntegralNumber() || proof.path("consoleErrorCount").bigIntegerValue().signum() != 0)
                throw new IllegalStateException("浏览器通过证据与冻结提交、逐项检查或控制台结果不一致");
            Path screenshot = Path.of(proof.path("screenshot").asText());
            if (!screenshot.isAbsolute()) screenshot = artifacts.resolve(screenshot);
            screenshot = screenshot.toAbsolutePath().normalize();
            String expected = proof.path("screenshotSha256").asText();
            if (!screenshot.startsWith(artifacts) || !screenshot.getFileName().toString().endsWith(".png")
                    || !Files.isRegularFile(screenshot, LinkOption.NOFOLLOW_LINKS)
                    || !screenshot.toRealPath().startsWith(artifacts) || Files.size(screenshot) == 0
                    || Files.size(screenshot) > 20_000_000 || !expected.matches("[0-9a-f]{64}"))
                throw new IllegalStateException("浏览器截图缺失、越界或校验信息无效");
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(screenshot.toRealPath(), LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536]; long size = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    size += read;
                    if (size > 20_000_000) throw new IllegalStateException("浏览器截图超过校验大小限制");
                    digest.update(buffer, 0, read);
                }
            }
            if (!expected.equals(java.util.HexFormat.of().formatHex(digest.digest())))
                throw new IllegalStateException("浏览器截图与助手内存证据 SHA-256 不符");
            accepted.add(proof.deepCopy());
        }
        if (accepted.isEmpty()) throw new IllegalStateException("缺少真实浏览器逐项通过证据，不能继续交付");
        return new ObjectMapper().createObjectNode().put("testedHead", testedHead).set("proofs", accepted);
    }
    private static boolean allChecksPassed(JsonNode checks) {
        if (!checks.isArray() || checks.isEmpty()) return false;
        for (JsonNode check : checks)
            if (!check.isObject() || !check.path("passed").isBoolean() || !check.path("passed").booleanValue()
                    || !check.path("check").isTextual() || check.path("check").asText().isBlank()) return false;
        return true;
    }
    private static void stop(Process process, Thread reader) {
        var descendants = process.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--)
            if (descendants.get(index).isAlive()) descendants.get(index).destroyForcibly();
        if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); reader.join(1000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    /** 结束成功、失败或取消的本次验收助手，不按端口/进程名清理其他运行。 */
    @Override public void close() { stop(process, reader); }
}
