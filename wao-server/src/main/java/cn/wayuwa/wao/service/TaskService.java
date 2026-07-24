package cn.wayuwa.wao.service;

import java.util.ArrayList;
import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.PatchTaskRequest;
import cn.wayuwa.wao.dto.TaskDetail;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 任务读取、创建与局部更新服务。 */
@Service
public class TaskService {

    private static final String TODAY = "2026-07-23";

    private final TaskMapper taskMapper;
    private final ObjectMapper json;

    public TaskService(TaskMapper taskMapper, ObjectMapper json) {
        this.taskMapper = taskMapper;
        this.json = json;
    }

    /**
     * 列出全部任务（跨项目）。
     *
     * @return 任务列表
     */
    public List<TaskDto> list() {
        return taskMapper.selectList(null).stream().map(this::toDto).toList();
    }

    /**
     * 按 id 获取单个任务。
     *
     * @param id 任务 id
     * @return 任务
     * @throws NotFoundException 任务不存在时
     */
    public TaskDto get(String id) {
        return toDto(require(id));
    }

    /**
     * 新建任务（默认状态待办）。
     *
     * @param req 新建请求
     * @return 创建后的任务
     */
    public TaskDto create(CreateTaskRequest req) {
        TaskEntity e = new TaskEntity();
        e.setId("t" + System.currentTimeMillis());
        e.setTitle(req.title().trim());
        e.setProjectId(req.project());
        e.setStatus("待办");
        e.setPr(StringUtils.hasText(req.pr()) ? req.pr() : "P2");
        e.setOwnerId(StringUtils.hasText(req.owner()) ? req.owner() : "wg");
        e.setDueDate(StringUtils.hasText(req.due()) ? req.due() : TODAY);
        e.setDescription("");
        TaskDetail detail = new TaskDetail(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Activity("ri-add-line", "#9A9DA9", "你创建了任务", "刚刚")));
        e.setDetailJson(write(detail));
        taskMapper.insert(e);
        return toDto(e);
    }

    /**
     * 局部更新任务；被修改的字段会写入活动时间线。
     *
     * @param id  任务 id
     * @param req 局部更新请求（空字段不改）
     * @return 更新后的任务
     * @throws NotFoundException 任务不存在时
     */
    public TaskDto patch(String id, PatchTaskRequest req) {
        TaskEntity e = require(id);
        TaskDetail d = parse(e.getDetailJson());
        List<Activity> activity = new ArrayList<>(nn(d.activity()));

        if (StringUtils.hasText(req.title())) {
            e.setTitle(req.title().trim());
        }
        if (req.desc() != null) {
            e.setDescription(req.desc());
        }
        if (StringUtils.hasText(req.status()) && !req.status().equals(e.getStatus())) {
            e.setStatus(req.status());
            activity.add(0, edit("你将状态改为「" + req.status() + "」"));
        }
        if (StringUtils.hasText(req.pr()) && !req.pr().equals(e.getPr())) {
            e.setPr(req.pr());
            activity.add(0, edit("你将优先级改为 " + req.pr()));
        }
        if (StringUtils.hasText(req.owner()) && !req.owner().equals(e.getOwnerId())) {
            e.setOwnerId(req.owner());
            activity.add(0, edit("你更换了负责人"));
        }

        TaskDetail updated = new TaskDetail(d.parts(), d.steps(), d.subs(), d.files(), d.docs(),
                d.linkTasks(), d.comments(), d.custom(), activity);
        e.setDetailJson(write(updated));
        taskMapper.updateById(e);
        return toDto(e);
    }

    /**
     * 全量替换 / 新增任务（前端本地变更后的写透）。
     *
     * @param id  任务 id
     * @param dto 完整任务
     * @return 保存后的任务
     */
    public TaskDto replace(String id, TaskDto dto) {
        TaskEntity e = new TaskEntity();
        e.setId(id);
        e.setTitle(dto.title());
        e.setProjectId(dto.project());
        e.setStatus(dto.status());
        e.setPr(dto.pr());
        e.setOwnerId(dto.owner());
        e.setStartDate(dto.start());
        e.setDueDate(dto.due());
        e.setWaiting(dto.waiting());
        e.setDescription(dto.desc());
        TaskDetail detail = new TaskDetail(dto.parts(), dto.steps(), dto.subs(), dto.files(),
                dto.docs(), dto.linkTasks(), dto.comments(), dto.custom(), dto.activity());
        e.setDetailJson(write(detail));
        if (taskMapper.selectById(id) == null) {
            taskMapper.insert(e);
        } else {
            taskMapper.updateById(e);
        }
        return toDto(e);
    }

    private TaskEntity require(String id) {
        TaskEntity e = taskMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("任务不存在：" + id);
        }
        return e;
    }

    private TaskDto toDto(TaskEntity e) {
        TaskDetail d = parse(e.getDetailJson());
        return new TaskDto(
                e.getId(), e.getTitle(), e.getProjectId(), e.getStatus(), e.getPr(), e.getOwnerId(),
                nn(d.parts()), e.getStartDate(), e.getDueDate(), e.getWaiting(),
                e.getDescription() == null ? "" : e.getDescription(),
                nn(d.steps()), nn(d.subs()), nn(d.files()), nn(d.docs()),
                nn(d.linkTasks()), nn(d.comments()), nn(d.custom()), nn(d.activity()));
    }

    private static Activity edit(String text) {
        return new Activity("ri-edit-line", "#9A9DA9", text, "刚刚");
    }

    private TaskDetail parse(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return new TaskDetail(null, null, null, null, null, null, null, null, null);
        }
        try {
            return json.readValue(detailJson, TaskDetail.class);
        } catch (JsonProcessingException ex) {
            return new TaskDetail(null, null, null, null, null, null, null, null, null);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list;
    }
}
