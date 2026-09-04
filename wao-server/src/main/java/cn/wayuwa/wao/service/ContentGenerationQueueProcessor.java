package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.AiRunAccountDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

/**
 * 使用 WAO 集中推荐模型消费自媒体成稿队列。
 *
 * <p>Agent 只保存固化后的提示词；本处理器在持有 Vault 能力的 wao-server 内解密模型凭据。
 */
public class ContentGenerationQueueProcessor {
    private static final String EXECUTOR = "WAO_RECOMMENDED";
    private static final int MAX_TAGS = 6;

    private final JdbcTemplate jdbc;
    private final AiAccountService accounts;
    private final DeepSeekApiClient model;
    private final ObjectMapper json;

    public ContentGenerationQueueProcessor(JdbcTemplate jdbc, AiAccountService accounts,
                                           DeepSeekApiClient model, ObjectMapper json) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.model = model;
        this.json = json;
    }

    /** 领取并推进最多三条待生成内容；每条任务独立失败，不阻断后续任务。 */
    public void advance() {
        List<PendingTask> pending = jdbc.query("""
                SELECT id, topic, generation_system_prompt, generation_user_prompt
                FROM agent_publish_task
                WHERE status = 'GENERATING'
                  AND generation_executor = ?
                  AND (generation_lease_until IS NULL OR generation_lease_until < ?)
                ORDER BY updated_at ASC
                LIMIT 3
                """, (rs, rowNum) -> new PendingTask(rs.getString("id"), rs.getString("topic"),
                rs.getString("generation_system_prompt"), rs.getString("generation_user_prompt")),
                EXECUTOR, LocalDateTime.now());
        for (PendingTask task : pending) process(task);
    }

    private void process(PendingTask task) {
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        int claimed = jdbc.update("""
                UPDATE agent_publish_task
                SET generation_claim_token = ?, generation_lease_until = ?, progress = 25, updated_at = ?
                WHERE id = ? AND status = 'GENERATING' AND generation_executor = ?
                  AND (generation_lease_until IS NULL OR generation_lease_until < ?)
                """, token, now.plusMinutes(10), now, task.id(), EXECUTOR, now);
        if (claimed != 1) return;
        try {
            if (!StringUtils.hasText(task.systemPrompt()) || !StringUtils.hasText(task.userPrompt())) {
                throw new IllegalStateException("生成材料不完整，请重新发起创作");
            }
            AiRunAccountDto account = accounts.defaultDocumentPolishAccount();
            if (account == null) {
                throw new IllegalStateException("推荐模型已停用或删除，请在 WAO 的 AI 账号中重新启用后重试");
            }
            String raw = model.complete(account.provider(), account.baseUrl(), account.apiKey(), account.model(),
                    task.systemPrompt(), task.userPrompt());
            GeneratedContent generated = parse(raw, task.topic());
            int updated = jdbc.update("""
                    UPDATE agent_publish_task
                    SET title = ?, content = ?, tags_json = ?, cover_text = ?, structure_json = ?,
                        status = 'WAITING_REVIEW', progress = 45, error_message = NULL, node_id = NULL,
                        generation_system_prompt = NULL, generation_user_prompt = NULL,
                        generation_claim_token = NULL, generation_lease_until = NULL, updated_at = ?
                    WHERE id = ? AND status = 'GENERATING' AND generation_claim_token = ?
                    """, trim(generated.title(), 200), generated.body(), write(generated.tags()),
                    trim(generated.coverText(), 200), write(generated.blocks()), LocalDateTime.now(),
                    task.id(), token);
            if (updated != 1) throw new IllegalStateException("生成任务领取状态已变化");
        } catch (RuntimeException exception) {
            fail(task.id(), token, exception.getMessage());
        }
    }

    private GeneratedContent parse(String raw, String fallbackTitle) {
        try {
            String normalized = jsonObject(raw);
            JsonNode root = json.readTree(normalized);
            String title = root.path("title").asText("").trim();
            String body = root.path("body").asText("").trim();
            List<Block> blocks = new ArrayList<>();
            for (JsonNode item : root.path("blocks")) {
                String text = item.path("text").asText("").trim();
                if (!StringUtils.hasText(text)) continue;
                String label = blankToNull(item.path("label").asText(null));
                Integer seconds = item.hasNonNull("seconds") && item.path("seconds").isNumber()
                        ? item.path("seconds").asInt() : null;
                blocks.add(new Block(label, text, seconds));
            }
            if (!StringUtils.hasText(body) && !blocks.isEmpty()) {
                body = blocks.stream().map(Block::text).reduce((left, right) -> left + "\n\n" + right)
                        .orElse("");
            }
            if (!StringUtils.hasText(title) && !StringUtils.hasText(body) && blocks.isEmpty()) {
                throw new IllegalArgumentException("模型没有返回可用的标题或正文");
            }
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : root.path("tags")) {
                String value = tag.asText("").trim().replace("#", "");
                if (StringUtils.hasText(value) && tags.size() < MAX_TAGS) tags.add(value);
            }
            return new GeneratedContent(StringUtils.hasText(title) ? title : fallbackTitle, body, tags,
                    blankToNull(root.path("coverText").asText(null)), blocks);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("模型返回格式无法解析，请重试", exception);
        }
    }

    private String jsonObject(String raw) {
        if (!StringUtils.hasText(raw)) throw new IllegalArgumentException("模型没有返回内容");
        String value = raw.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) value = value.substring(firstLine + 1, closing).trim();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("模型没有返回 JSON 对象");
        return value.substring(start, end + 1);
    }

    private void fail(String taskId, String token, String message) {
        String reason = StringUtils.hasText(message) ? message : "推荐模型生成失败，请重试";
        jdbc.update("""
                UPDATE agent_publish_task
                SET status = 'FAILED', progress = 25, error_message = ?, node_id = NULL,
                    generation_claim_token = NULL, generation_lease_until = NULL, updated_at = ?
                WHERE id = ? AND status = 'GENERATING' AND generation_claim_token = ?
                """, trim("生成文案失败：" + reason, 900), LocalDateTime.now(), taskId, token);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("生成结果序列化失败", exception);
        }
    }

    private String trim(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) && !"null".equals(value) ? value.trim() : null;
    }

    private record PendingTask(String id, String topic, String systemPrompt, String userPrompt) { }
    private record Block(String label, String text, Integer seconds) { }
    private record GeneratedContent(String title, String body, List<String> tags, String coverText,
                                    List<Block> blocks) { }
}
