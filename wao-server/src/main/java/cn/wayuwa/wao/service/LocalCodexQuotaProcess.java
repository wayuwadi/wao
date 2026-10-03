package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 只读 Codex app-server 协议；不创建会话、不执行模型、不读取认证文件。 */
final class LocalCodexQuotaProcess {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ENV = Set.of("SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "COMSPEC",
            "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "PATH", "PATHEXT",
            "CODEX_HOME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY");

    static RunnerCodexQuotaReport read(String executable, Duration timeout) throws Exception {
        return run(executable, timeout, LocalCodexQuotaProcess::exchange);
    }

    interface Exchange<T> { T read(Process process) throws Exception; }

    static <T> T run(String executable, Duration timeout, Exchange<T> exchange) throws Exception {
        var builder = new ProcessBuilder(LocalCodexExecutable.resolve(executable), "app-server");
        builder.environment().keySet().removeIf(key -> !ENV.contains(key.toUpperCase(Locale.ROOT)));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        var executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "codex-quota-reader"); t.setDaemon(true); return t; });
        try {
            return executor.submit(() -> exchange.read(process)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            executor.shutdownNow();
            process.waitFor(1, TimeUnit.SECONDS);
        }
    }

    private static RunnerCodexQuotaReport exchange(Process process) throws Exception {
        try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                var output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            send(output, "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"wao-quota\",\"version\":\"1\"},\"capabilities\":{}}}");
            response(input, 1);
            send(output, "{\"method\":\"initialized\"}");
            send(output, "{\"id\":2,\"method\":\"account/read\",\"params\":{\"refreshToken\":false}}");
            JsonNode account = response(input, 2);
            send(output, "{\"id\":3,\"method\":\"account/rateLimits/read\"}");
            return normalize(account, response(input, 3));
        }
    }

    static void send(BufferedWriter output, String value) throws IOException {
        output.write(value); output.newLine(); output.flush();
    }

    static JsonNode response(BufferedReader input, int id) throws IOException {
        for (int count = 0; count < 200; count++) {
            // 有界读取，异常原文可能含供应商信息，不进入日志或异常消息。
            var line = new StringBuilder();
            int ch;
            while ((ch = input.read()) != -1 && ch != '\n') {
                if (line.length() >= 262_144) throw new IOException("Codex 响应过长");
                line.append((char) ch);
            }
            if (ch == -1) throw new IOException("Codex 额度接口提前退出");
            JsonNode event;
            try { event = JSON.readTree(line.toString()); } catch (IOException ignored) { continue; }
            if (event == null || !event.path("id").isInt() || event.path("id").asInt() != id) continue;
            if (event.hasNonNull("error")) throw new IOException("Codex 额度接口失败，请检查本机登录和网络");
            return event.path("result");
        }
        throw new IOException("Codex 额度接口未返回结果");
    }

    static RunnerCodexQuotaReport normalize(JsonNode accountResult, JsonNode rates) {
        JsonNode account = accountResult.path("account"), legacy = rates.path("rateLimits");
        String email = account.path("email").asText("").strip().toLowerCase(Locale.ROOT);
        if (!"chatgpt".equals(account.path("type").asText()) || email.isEmpty() || email.length() > 255)
            throw new IllegalArgumentException("请使用具备邮箱标识的 ChatGPT 账号登录本机 Codex");
        JsonNode buckets = rates.path("rateLimitsByLimitId");
        var entries = new LinkedHashMap<String, JsonNode>();
        if (buckets.isObject() && !buckets.isEmpty()) buckets.fields().forEachRemaining(entry -> entries.put(entry.getKey(), entry.getValue()));
        else entries.put(legacy.path("limitId").asText("codex"), legacy);
        var limits = new ArrayList<CodexQuotaLimitDto>();
        entries.entrySet().stream().limit(20).forEach(entry -> {
            JsonNode bucket = entry.getValue();
            var windows = new ArrayList<CodexQuotaWindowDto>();
            for (String name : List.of("primary", "secondary")) {
                JsonNode window = bucket.path(name), used = window.path("usedPercent");
                if (!used.isNumber() || !Double.isFinite(used.asDouble())) continue;
                windows.add(new CodexQuotaWindowDto((int) Math.max(0, Math.min(100, Math.round(used.asDouble()))),
                        positive(window.path("windowDurationMins"), 525600), positive(window.path("resetsAt"), 253402300799L)));
            }
            if (!windows.isEmpty()) limits.add(new CodexQuotaLimitDto(text(bucket.path("limitId"), entry.getKey(), 64),
                    text(bucket.path("limitName"), "通用使用限额", 100), windows));
        });
        if (limits.isEmpty()) throw new IllegalArgumentException("Codex 未返回可解析额度");
        CodexResetCreditsDto reset = null;
        JsonNode rawReset = rates.path("rateLimitResetCredits"), count = rawReset.path("availableCount");
        if (count.isIntegralNumber() && count.canConvertToInt() && count.asInt() >= 0 && count.asInt() <= 1_000_000) {
            var credits = new ArrayList<CodexResetCreditsDto.Credit>();
            if (rawReset.path("credits").isArray()) for (JsonNode item : rawReset.path("credits")) {
                if (credits.size() >= 50) break;
                credits.add(new CodexResetCreditsDto.Credit(positive(item.path("grantedAt"), 253402300799L), positive(item.path("expiresAt"), 253402300799L)));
            }
            reset = new CodexResetCreditsDto(count.asInt(), credits);
        }
        return new RunnerCodexQuotaReport(email, text(legacy.path("planType"), account.path("planType").asText(""), 32), null, limits, reset);
    }

    private static Long positive(JsonNode value, long max) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0 && value.asLong() <= max ? value.asLong() : null;
    }
    private static String text(JsonNode node, String fallback, int max) {
        String value = node.isTextual() && !node.asText().isBlank() ? node.asText() : fallback;
        return value.substring(0, Math.min(max, value.length()));
    }
}
