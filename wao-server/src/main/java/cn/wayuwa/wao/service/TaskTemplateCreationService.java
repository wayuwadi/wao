package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.dto.TaskTemplateCreateRequest;
import cn.wayuwa.wao.mapper.TaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskTemplateCreationService {
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private final JdbcTemplate db;
    private final TaskTemplateService templates;
    private final TaskService tasks;
    private final TaskMapper taskMapper;
    private record Receipt(String account, String member, String template, String project) {}

    public TaskTemplateCreationService(JdbcTemplate db, TaskTemplateService templates, TaskService tasks, TaskMapper taskMapper) {
        this.db = db; this.templates = templates; this.tasks = tasks; this.taskMapper = taskMapper;
    }

    @Transactional
    public TaskDto create(String taskId, String member, TaskTemplateCreateRequest request) {
        if (request == null || taskId == null || !ID.matcher(taskId).matches() || request.accountId() == null || request.accountId().isBlank()
                || request.accountId().length() > 64 || request.templateId() == null || !ID.matcher(request.templateId()).matches()
                || request.projectId() == null || request.projectId().length() > 64 || !request.projectId().equals(request.projectId().trim())) {
            throw new BadRequestException("创建参数无效");
        }
        try {
            db.update("INSERT INTO task_template_creation(task_id, account_id, member_id, template_id, project_id) VALUES(?,?,?,?,?)",
                    taskId, request.accountId(), member, request.templateId(), request.projectId());
        } catch (DuplicateKeyException ex) {
            Receipt receipt = db.query("SELECT account_id, member_id, template_id, project_id FROM task_template_creation WHERE task_id=?",
                    (row, index) -> new Receipt(row.getString("account_id"), row.getString("member_id"), row.getString("template_id"), row.getString("project_id")), taskId)
                    .stream().findFirst().orElseThrow(() -> new ConflictException("创建冲突，请重试"));
            if (!receipt.account().equals(request.accountId()) || !receipt.member().equals(member)) throw new NotFoundException("任务不存在");
            if (!receipt.template().equals(request.templateId()) || !receipt.project().equals(request.projectId())) throw new ConflictException("创建编号已用于其他任务");
            // Never replay a full task snapshot over later edits. A deleted task stays deleted.
            return tasks.get(taskId, member);
        }
        if (taskMapper.lockById(taskId) != null) throw new ConflictException("任务编号已被占用，请重新创建");
        var content = templates.get(request.templateId(), member).content();
        Map<String, String> ids = new HashMap<>();
        for (var step : content.steps()) {
            ids.put(step.key(), "s" + UUID.nameUUIDFromBytes((taskId + ":" + step.key()).getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""));
        }
        String createdAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
        List<Step> subs = content.steps().stream().map(step -> new Step(step.title(), false, ids.get(step.key()), step.description(), List.of(), null,
                step.parentKey() == null || step.parentKey().isBlank() ? null : ids.get(step.parentKey()), member, "", null,
                member, createdAt, List.of(), List.of(), "P2")).toList();
        TaskDto task = new TaskDto(taskId, content.title(), request.projectId(), "待办", "P2", member,
                List.of(), "", "", null, content.description(), List.of(), subs, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), member);
        return tasks.replace(taskId, task, member);
    }
}
