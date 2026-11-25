package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.AgyQuotaBucketDto;
import cn.wayuwa.wao.dto.AgyQuotaGroupDto;
import cn.wayuwa.wao.dto.AgyQuotaReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.util.StringUtils;

/** 只读 Antigravity CLI `/usage` 输出；不创建会话、不执行模型、不读取凭据文件。 */
final class LocalAgyQuotaProcess {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STDOUT_BYTES = 524_288;
    private static final int MAX_GROUPS = 10;
    private static final int MAX_BUCKETS_PER_GROUP = 6;
    private static final long MAX_EPOCH_SECONDS = 253_402_300_799L;
    private static final Set<String> ENV = Set.of(
            "SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "COMSPEC",
            "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP",
            "PATH", "PATHEXT", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
            "ANTIGRAVITY_HOME", "GEMINI_HOME");

    interface Exchange<T> {
        T read(Process process) throws Exception;
    }

    private LocalAgyQuotaProcess() {
    }

    static AgyQuotaReport read(String executable, String accountLabel, Duration timeout) throws Exception {
        return run(executable, timeout, process -> parseOutput(readBoundedStdout(process), accountLabel));
    }

    static <T> T run(String executable, Duration timeout, Exchange<T> exchange) throws Exception {
        if (!StringUtils.hasText(executable)) {
            throw new IllegalArgumentException("未配置本机 Agy CLI 命令");
        }
        var builder = new ProcessBuilder(executable.strip(), "--print", "/usage", "--output-format", "json");
        builder.environment().keySet().removeIf(key -> !ENV.contains(key.toUpperCase(Locale.ROOT)));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        process.getOutputStream().close();
        var executor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "agy-quota-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return executor.submit(() -> exchange.read(process)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            executor.shutdownNow();
            process.waitFor(1, TimeUnit.SECONDS);
        }
    }

    private static String readBoundedStdout(Process process) throws IOException, InterruptedException {
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_STDOUT_BYTES) {
                    throw new IOException("Agy 额度响应过长");
                }
                out.write(buffer, 0, read);
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException("Agy 额度命令执行失败，请检查本机登录和网络");
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    static AgyQuotaReport parseOutput(String rawJson, String accountLabel) throws IOException {
        if (!StringUtils.hasText(rawJson)) {
            throw new IOException("Agy 额度接口未返回结果");
        }
        JsonNode root;
        try {
            root = JSON.readTree(rawJson);
        } catch (IOException ex) {
            throw new IOException("Agy 额度输出无法解析");
        }
        String status = root.path("status").asText("");
        if (!"SUCCESS".equals(status)) {
            throw new IOException("Agy 额度查询未成功，请检查本机登录和网络");
        }
        JsonNode command = root.path("command");
        if (!"usage".equals(command.path("name").asText(""))) {
            throw new IOException("Agy 额度响应缺少 usage 数据");
        }
        JsonNode rawGroups = command.path("data").path("groups");
        if (!rawGroups.isArray() || rawGroups.isEmpty()) {
            throw new IllegalArgumentException("Agy 未返回可解析额度分组");
        }
        List<AgyQuotaGroupDto> groups = new ArrayList<>();
        for (JsonNode groupNode : rawGroups) {
            if (groups.size() >= MAX_GROUPS) break;
            String groupName = limit(groupNode.path("name").asText(""), 100);
            JsonNode rawBuckets = groupNode.path("buckets");
            if (!StringUtils.hasText(groupName) || !rawBuckets.isArray()) continue;
            List<AgyQuotaBucketDto> buckets = new ArrayList<>();
            for (JsonNode bucketNode : rawBuckets) {
                if (buckets.size() >= MAX_BUCKETS_PER_GROUP) break;
                JsonNode fraction = bucketNode.path("remaining_fraction");
                if (!fraction.isNumber() || !Double.isFinite(fraction.asDouble())) continue;
                int remainingPercent = (int) Math.max(0, Math.min(100, Math.round(fraction.asDouble() * 100.0)));
                String id = limit(bucketNode.path("id").asText(""), 64);
                String name = limit(bucketNode.path("name").asText(""), 100);
                String window = limit(bucketNode.path("window").asText(""), 32);
                if (!StringUtils.hasText(id) && !StringUtils.hasText(window)) continue;
                Long resetsAt = parseEpochSeconds(bucketNode.path("reset_time").asText(""));
                buckets.add(new AgyQuotaBucketDto(
                        StringUtils.hasText(id) ? id : null,
                        StringUtils.hasText(name) ? name : null,
                        StringUtils.hasText(window) ? window : null,
                        remainingPercent,
                        resetsAt));
            }
            if (!buckets.isEmpty()) {
                groups.add(new AgyQuotaGroupDto(groupName, List.copyOf(buckets)));
            }
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("Agy 未返回可解析额度");
        }
        String label = StringUtils.hasText(accountLabel) ? limit(accountLabel.strip(), 120) : "Agy";
        return new AgyQuotaReport(label, List.copyOf(groups));
    }

    static Long parseEpochSeconds(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            long epoch = Instant.parse(value.strip()).getEpochSecond();
            // 未消耗满额时 agy 返回 1970-01-01T00:00:00Z（epoch 0），统一视为尚未开始计时。
            return epoch > 0 && epoch <= MAX_EPOCH_SECONDS ? epoch : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String limit(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
