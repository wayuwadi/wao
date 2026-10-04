package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 从实际执行器发现可见模型；保留最后成功目录，绝不替换用户选择。 */
@Service
public class LocalCodexModelService {
    public record Model(String id, String label, List<String> reasoningEfforts) {
        public Model { reasoningEfforts = reasoningEfforts == null || reasoningEfforts.isEmpty()
                ? List.of("medium") : List.copyOf(reasoningEfforts); }
        public Model(String id, String label) { this(id, label, List.of("medium")); }
    }
    public record Catalog(List<Model> models, String updatedAt, boolean stale, String message) { }
    private final Environment environment;
    private final ObjectMapper json;
    private Catalog last;
    private long nextRead;

    public LocalCodexModelService(Environment environment, ObjectMapper json) {
        this.environment = environment; this.json = json;
    }

    /** 最多每五分钟刷新一次；失败保留历史，六十秒后允许重试，不返回认证信息。 */
    public synchronized Catalog catalog() {
        if (!environment.getProperty("wao.ai.local-notes.enabled", Boolean.class, false))
            return new Catalog(List.of(), null, true, "本机 Codex 尚未启用");
        if (System.currentTimeMillis() < nextRead && last != null) return last;
        nextRead = System.currentTimeMillis() + 60_000;
        if (last == null) {
            try {
                Catalog cached = json.readValue(Files.readString(cachePath()), Catalog.class);
                if (cached.models() != null && !cached.models().isEmpty()
                        && cached.models().stream().allMatch(m -> validId(m.id()) && m.label() != null)) last = cached;
            } catch (Exception ignored) { /* 首次启动没有历史目录。 */ }
        }
        try {
            List<Model> models = readModels();
            if (models.isEmpty()) throw new IOException("模型目录为空");
            last = new Catalog(List.copyOf(models), Instant.now().toString(), false, "模型来自本机 Codex，自动刷新");
            nextRead = System.currentTimeMillis() + 300_000;
            try {
                Path path = cachePath(); Files.createDirectories(path.getParent());
                Path temp = Files.createTempFile(path.getParent(), "models-", ".tmp");
                try {
                    Files.writeString(temp, json.writeValueAsString(last));
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temp); }
            } catch (Exception ignored) { /* 本轮目录仍有效；缓存失败不会伪造后续读取成功。 */ }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            last = new Catalog(last == null ? List.of() : last.models(), last == null ? null : last.updatedAt(), true,
                    "模型目录暂时无法更新；保留已有选择，请检查本机 Codex 登录和网络后重试");
        }
        return last;
    }

    /** 仅接受执行器可见的精确模型标识；空值沿用既有默认，未知值明确报错。 */
    public String validate(String model) {
        String value = model == null || model.isBlank() ? "gpt-6-astra" : model.strip();
        if (!validId(value) || catalog().models().stream().noneMatch(m -> m.id().equals(value)))
            throw new BadRequestException("所选模型不在本机 Codex 可用目录中；请刷新 AI 设置的模型目录后重新选择");
        return value;
    }

    /** 思考深度必须来自所选模型当前可见的支持列表。 */
    public String validateEffort(String model, String effort) {
        String selected = validate(model);
        String value = effort == null || effort.isBlank() ? "medium" : effort.strip();
        Model available = catalog().models().stream().filter(item -> item.id().equals(selected)).findFirst().orElseThrow();
        if (available.reasoningEfforts() == null || !available.reasoningEfforts().contains(value))
            throw new BadRequestException("所选模型不支持该思考深度；请刷新 AI 设置的模型目录后重新选择");
        return value;
    }

    private Path cachePath() {
        return Path.of(environment.getProperty("wao.ai.local-notes.directory",
                Path.of(System.getProperty("user.home"), ".wao", "local-notes").toString())).resolve("model-catalog.json");
    }

    List<Model> readModels() throws Exception {
        return LocalCodexQuotaProcess.run(environment.getProperty("wao.ai.local-notes.executable", "codex"),
                Duration.ofSeconds(15), process -> {
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    var output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                LocalCodexQuotaProcess.send(output, "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"wao-models\",\"version\":\"1\"},\"capabilities\":{}}}");
                LocalCodexQuotaProcess.response(input, 1);
                LocalCodexQuotaProcess.send(output, "{\"method\":\"initialized\"}");
                LocalCodexQuotaProcess.send(output, "{\"id\":2,\"method\":\"account/read\",\"params\":{\"refreshToken\":false}}");
                if (!"chatgpt".equals(LocalCodexQuotaProcess.response(input, 2).path("account").path("type").asText()))
                    throw new IOException("需使用已有 ChatGPT 登录");
                var models = new LinkedHashMap<String, Model>();
                String cursor = null;
                for (int id = 3; id < 23; id++) {
                    var params = json.createObjectNode().put("limit", 100).put("includeHidden", false);
                    if (cursor != null) params.put("cursor", cursor);
                    var request = json.createObjectNode().put("id", id).put("method", "model/list");
                    request.set("params", params);
                    LocalCodexQuotaProcess.send(output, json.writeValueAsString(request));
                    JsonNode result = LocalCodexQuotaProcess.response(input, id);
                    for (Model model : normalize(result)) models.putIfAbsent(model.id(), model);
                    cursor = result.path("nextCursor").asText(null);
                    if (cursor == null || cursor.isBlank()) return List.copyOf(models.values());
                }
                throw new IOException("模型目录分页超限");
            }
        });
    }

    static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"); }
    static List<Model> normalize(JsonNode result) {
        var models = new ArrayList<Model>();
        for (JsonNode node : result.path("data")) {
            String id = node.path("model").asText("");
            if (node.path("hidden").asBoolean(false) || !validId(id)) continue;
            String label = node.path("displayName").asText(id);
            List<String> efforts = new ArrayList<>();
            for (JsonNode option : node.path("supportedReasoningEfforts")) {
                String effort = option.path("reasoningEffort").asText("");
                if (List.of("none", "minimal", "low", "medium", "high", "xhigh", "max").contains(effort)
                        && !efforts.contains(effort)) efforts.add(effort);
            }
            models.add(new Model(id, label.substring(0, Math.min(label.length(), 160)),
                    efforts.isEmpty() ? List.of("medium") : List.copyOf(efforts)));
        }
        return models;
    }
}
