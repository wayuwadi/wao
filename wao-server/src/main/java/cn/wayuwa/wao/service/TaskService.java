package cn.wayuwa.wao.service;

import java.util.ArrayList;
import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.CommentDto;
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
    private final FeishuCommentNotifier commentNotifier;
    private final MemberService members;
    private final ProjectService projects;

    public TaskService(TaskMapper taskMapper, ObjectMapper json, FeishuCommentNotifier commentNotifier,
            MemberService members, ProjectService projects) {
        this.taskMapper = taskMapper;
        this.json = json;
        this.commentNotifier = commentNotifier;
        this.members = members;
        this.projects = projects;
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
        e.setDescription(req.desc() == null ? "" : req.desc());
        TaskDetail detail = new TaskDetail(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Activity("ri-add-line", "#9A9DA9", "你创建了任务", "刚刚")),
                nn(req.images()));
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
                d.linkTasks(), d.comments(), d.custom(), activity, d.images());
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
                dto.docs(), dto.linkTasks(), dto.comments(), dto.custom(), dto.activity(), dto.images());
        e.setDetailJson(write(detail));
        if (taskMapper.selectById(id) == null) {
            taskMapper.insert(e);
        } else {
            taskMapper.updateById(e);
        }
        return toDto(e);
    }

    /** 原子追加一条评论并同步飞书群，避免全文自动保存请求相互覆盖评论。 */
    public CommentDto addComment(String id, String memberId, String text) {
        TaskEntity entity = require(id);
        TaskDetail detail = parse(entity.getDetailJson());
        CommentDto comment = new CommentDto("c" + System.currentTimeMillis(), memberId,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(), text.trim());
        List<CommentDto> comments = new ArrayList<>(nn(detail.comments()));
        comments.add(comment);
        saveComments(entity, detail, comments, "添加了评论");
        TaskDto task = toDto(entity);
        String memberName = members.list().stream().filter(m -> m.id().equals(memberId)).map(m -> m.name()).findFirst().orElse(memberId);
        String projectName;
        try { projectName = projects.get(task.project()).name(); } catch (NotFoundException ex) { projectName = task.project(); }
        commentNotifier.notifyComment(task, comment, memberName, projectName);
        return comment;
    }

    /**
     * 改一条评论的正文。只有评论人本人能改 —— 别人的话不该被替换掉。
     *
     * @param id        任务 id
     * @param commentId 评论 id（老数据没有 id 时传下标，如 {@code 2}）
     * @param memberId  当前成员 id
     * @param text      新正文
     * @return 更新后的评论
     * @throws NotFoundException   任务或评论不存在时
     * @throws BadRequestException 改的不是自己的评论、或正文为空时
     */
    public CommentDto updateComment(String id, String commentId, String memberId, String text) {
        if (!StringUtils.hasText(text)) {
            throw new BadRequestException("评论内容不能为空");
        }
        TaskEntity entity = require(id);
        TaskDetail detail = parse(entity.getDetailJson());
        List<CommentDto> comments = new ArrayList<>(nn(detail.comments()));
        int index = indexOfComment(comments, commentId);
        CommentDto old = comments.get(index);
        requireAuthor(old, memberId);
        CommentDto updated = new CommentDto(blankOr(old.id(), commentId), old.who(), old.time(), text.trim());
        comments.set(index, updated);
        saveComments(entity, detail, comments, "修改了评论");
        return updated;
    }

    /**
     * 删一条评论。同样只有评论人本人能删。
     *
     * @param id        任务 id
     * @param commentId 评论 id（老数据没有 id 时传下标）
     * @param memberId  当前成员 id
     * @throws NotFoundException   任务或评论不存在时
     * @throws BadRequestException 删的不是自己的评论时
     */
    public void deleteComment(String id, String commentId, String memberId) {
        TaskEntity entity = require(id);
        TaskDetail detail = parse(entity.getDetailJson());
        List<CommentDto> comments = new ArrayList<>(nn(detail.comments()));
        int index = indexOfComment(comments, commentId);
        requireAuthor(comments.get(index), memberId);
        comments.remove(index);
        saveComments(entity, detail, comments, "删除了评论");
    }

    /**
     * 删除一个任务。
     *
     * @param id 任务 id
     * @throws NotFoundException 任务不存在时
     */
    public void delete(String id) {
        require(id);
        taskMapper.deleteById(id);
    }

    /** 评论定位：优先按 id，老评论（没有 id）回落到下标。 */
    private int indexOfComment(List<CommentDto> comments, String commentId) {
        for (int i = 0; i < comments.size(); i++) {
            if (commentId != null && commentId.equals(comments.get(i).id())) {
                return i;
            }
        }
        try {
            int index = Integer.parseInt(commentId == null ? "" : commentId.trim());
            if (index >= 0 && index < comments.size() && comments.get(index).id() == null) {
                return index;
            }
        } catch (NumberFormatException ignored) {
            // 不是下标就按找不到处理
        }
        throw new NotFoundException("评论不存在：" + commentId);
    }

    private static void requireAuthor(CommentDto comment, String memberId) {
        if (comment.who() != null && !comment.who().equals(memberId)) {
            throw new BadRequestException("只能修改或删除自己的评论");
        }
    }

    /** 写回评论列表并记一条活动。 */
    private void saveComments(TaskEntity entity, TaskDetail detail, List<CommentDto> comments, String activityText) {
        List<Activity> activity = new ArrayList<>(nn(detail.activity()));
        activity.add(0, edit(activityText));
        entity.setDetailJson(write(new TaskDetail(detail.parts(), detail.steps(), detail.subs(), detail.files(), detail.docs(),
                detail.linkTasks(), comments, detail.custom(), activity, detail.images())));
        taskMapper.updateById(entity);
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
                nn(d.linkTasks()), nn(d.comments()), nn(d.custom()), nn(d.activity()), nn(d.images()));
    }

    private static Activity edit(String text) {
        return new Activity("ri-edit-line", "#9A9DA9", text, "刚刚");
    }

    private TaskDetail parse(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return new TaskDetail(null, null, null, null, null, null, null, null, null, null);
        }
        try {
            return json.readValue(detailJson, TaskDetail.class);
        } catch (JsonProcessingException ex) {
            return new TaskDetail(null, null, null, null, null, null, null, null, null, null);
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
