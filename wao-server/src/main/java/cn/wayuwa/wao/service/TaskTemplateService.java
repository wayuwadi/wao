package cn.wayuwa.wao.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.TaskTemplateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskTemplateService {
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern STEP_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public record Template(String id, TaskTemplateRequest content) {}
    private record Stored(String member, String content, boolean deleted) {}

    public TaskTemplateService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    public List<Template> list(String member) {
        return db.query("SELECT id, content_json FROM task_template WHERE member_id=? AND deleted=0 ORDER BY created_at DESC, id DESC",
                (row, index) -> new Template(row.getString("id"), read(row.getString("content_json"))), member);
    }

    public Template get(String id, String member) {
        return db.query("SELECT id, content_json FROM task_template WHERE id=? AND member_id=? AND deleted=0",
                (row, index) -> new Template(row.getString("id"), read(row.getString("content_json"))), id, member)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("模板不存在"));
    }

    @Transactional
    public Template save(String id, String member, TaskTemplateRequest content) {
        if (id == null || !ID.matcher(id).matches()) throw new BadRequestException("模板编号无效");
        validate(content);
        String serialized;
        try { serialized = json.writeValueAsString(content); }
        catch (JsonProcessingException ex) { throw new BadRequestException("模板内容无效"); }
        if (serialized.length() > 1_000_000) throw new BadRequestException("模板内容超过大小限制");
        try {
            db.update("INSERT INTO task_template(id, member_id, content_json) VALUES(?,?,?)", id, member, serialized);
        } catch (DuplicateKeyException ex) {
            Stored existing = db.query("SELECT member_id, content_json, deleted FROM task_template WHERE id=?",
                    (row, index) -> new Stored(row.getString("member_id"), row.getString("content_json"), row.getBoolean("deleted")), id)
                    .stream().findFirst().orElseThrow(() -> new ConflictException("模板保存冲突，请重试"));
            if (!existing.member().equals(member)) throw new NotFoundException("模板不存在");
            if (existing.deleted()) throw new ConflictException("模板已删除，请放弃旧草稿");
            if (!existing.content().equals(serialized)) throw new ConflictException("模板编号已被其他内容使用");
        }
        return new Template(id, content);
    }

    public void delete(String id, String member) {
        // Retain only an ID tombstone so a delayed retry cannot resurrect deleted content.
        if (db.update("UPDATE task_template SET content_json='', deleted=1 WHERE id=? AND member_id=? AND deleted=0", id, member) == 0) {
            boolean alreadyDeleted = db.query("SELECT member_id, content_json, deleted FROM task_template WHERE id=?",
                    (row, index) -> new Stored(row.getString("member_id"), row.getString("content_json"), row.getBoolean("deleted")), id)
                    .stream().anyMatch(row -> row.member().equals(member) && row.deleted());
            if (!alreadyDeleted) throw new NotFoundException("模板不存在");
        }
    }

    private TaskTemplateRequest read(String value) {
        try { return json.readValue(value, TaskTemplateRequest.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("模板内容无法读取", ex); }
    }

    static void validate(TaskTemplateRequest value) {
        if (value == null || blankOrTooLong(value.name(), 128) || blankOrTooLong(value.title(), 200)
                || value.description() == null || tooLong(value.description(), 20000) || value.steps() == null || value.steps().size() > 500) {
            throw new BadRequestException("模板内容无效或超过大小限制");
        }
        Map<String, String> parents = new HashMap<>();
        for (var step : value.steps()) {
            if (step == null || step.key() == null || !STEP_KEY.matcher(step.key()).matches()
                    || blankOrTooLong(step.title(), 200) || step.description() == null || tooLong(step.description(), 20000)
                    || parents.containsKey(step.key())) throw new BadRequestException("模板子任务无效");
            parents.put(step.key(), step.parentKey() == null || step.parentKey().isBlank() ? "" : step.parentKey());
        }
        for (String key : parents.keySet()) {
            Set<String> seen = new HashSet<>();
            String current = key;
            while (!current.isEmpty()) {
                if (!seen.add(current) || !parents.containsKey(current)) throw new BadRequestException("模板子任务层级无效");
                current = parents.get(current);
            }
        }
    }
    private static boolean tooLong(String text, int max) { return text != null && text.length() > max; }
    private static boolean blankOrTooLong(String text, int max) { return text == null || text.isBlank() || text.length() > max; }
}
