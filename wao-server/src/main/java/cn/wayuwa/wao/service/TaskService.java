package cn.wayuwa.wao.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.CommentDto;
import cn.wayuwa.wao.dto.CustomProp;
import cn.wayuwa.wao.dto.PatchTaskRequest;
import cn.wayuwa.wao.dto.Step;
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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TaskService.class);
    /** 已完成状态：进入时记完成时间，离开时清掉。 */
    public static final String DONE = "已完成";
    /** 想让同伴一起补充创意的状态：任务一进这个状态就自动分享到飞书群。 */
    public static final String COLLECTING = "收集中";

    private final TaskMapper taskMapper;
    private final ObjectMapper json;
    private final FeishuCommentNotifier commentNotifier;
    private final FeishuTaskNotifier taskNotifier;
    private final MemberService members;
    private final ProjectService projects;

    public TaskService(TaskMapper taskMapper, ObjectMapper json, FeishuCommentNotifier commentNotifier,
            FeishuTaskNotifier taskNotifier, MemberService members, ProjectService projects) {
        this.taskMapper = taskMapper;
        this.json = json;
        this.commentNotifier = commentNotifier;
        this.taskNotifier = taskNotifier;
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
        return create(req, null);
    }

    /**
     * 新建任务并记录由服务端会话推导出的创建人。
     *
     * @param req 新建请求
     * @param createdBy 当前创建人的成员 id；历史兼容调用可为空
     * @return 创建后的任务
     */
    public TaskDto create(CreateTaskRequest req, String createdBy) {
        TaskEntity e = new TaskEntity();
        e.setId("t" + System.currentTimeMillis());
        e.setTitle(req.title().trim());
        e.setProjectId(req.project());
        e.setStatus("待办");
        e.setPr(StringUtils.hasText(req.pr()) ? req.pr() : "P2");
        e.setOwnerId(StringUtils.hasText(req.owner()) ? req.owner() : "wg");
        e.setCreatedBy(createdBy);
        e.setDueDate(StringUtils.hasText(req.due()) ? req.due() : today());
        e.setDescription(req.desc() == null ? "" : req.desc());
        TaskDetail detail = new TaskDetail(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Activity("ri-add-line", "#9A9DA9", "你创建了任务", "刚刚")),
                nn(req.images()));
        e.setDetailJson(write(detail));
        taskMapper.insert(e);
        announceCreated(e, createdBy);
        return toDto(e);
    }

    /**
     * 局部更新任务；被修改的字段会写入活动时间线。
     *
     * @param id       任务 id
     * @param req      局部更新请求（空字段不改）
     * @param memberId 当前操作人成员 id，用于「收集中」通知里排掉自己
     * @return 更新后的任务
     * @throws NotFoundException 任务不存在时
     */
    public TaskDto patch(String id, PatchTaskRequest req, String memberId) {
        TaskEntity e = require(id);
        String previousStatus = e.getStatus();
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
            e.setCompletedAt(DONE.equals(req.status()) ? LocalDateTime.now(ZoneId.of("Asia/Shanghai")) : null);
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
        announceCollecting(e, previousStatus, memberId);
        announceStatusChanged(e, previousStatus, memberId);
        return toDto(e);
    }

    /**
     * 全量替换 / 新增任务（前端本地变更后的写透）。
     *
     * @param id       任务 id
     * @param dto      完整任务
     * @param memberId 当前操作人成员 id，用于「收集中」通知里排掉自己
     * @return 保存后的任务
     */
    public TaskDto replace(String id, TaskDto dto, String memberId) {
        TaskEntity previous = taskMapper.selectById(id);
        String previousStatus = previous == null ? null : previous.getStatus();
        TaskEntity e = new TaskEntity();
        e.setId(id);
        e.setTitle(dto.title());
        e.setProjectId(dto.project());
        e.setStatus(dto.status());
        e.setPr(dto.pr());
        e.setOwnerId(dto.owner());
        e.setCreatedBy(previous == null ? dto.createdBy() : previous.getCreatedBy());
        e.setStartDate(dto.start());
        e.setDueDate(dto.due());
        e.setWaiting(dto.waiting());
        e.setDescription(dto.desc());
        // 完成时间由服务端根据状态迁移推导，不信任客户端写透的值。
        if (DONE.equals(dto.status())) {
            e.setCompletedAt(DONE.equals(previousStatus) && previous.getCompletedAt() != null
                    ? previous.getCompletedAt() : LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        } else {
            e.setCompletedAt(null);
        }
        TaskDetail detail = new TaskDetail(dto.parts(), dto.steps(), dto.subs(), dto.files(),
                dto.docs(), dto.linkTasks(), dto.comments(), dto.custom(), dto.activity(), dto.images());
        e.setDetailJson(write(detail));
        if (previous == null) {
            taskMapper.insert(e);
            announceCreated(e, memberId);
        } else {
            taskMapper.updateById(e);
            announceNewSubs(e, previous, dto.subs());
        }
        announceCollecting(e, previousStatus, memberId);
        announceStatusChanged(e, previousStatus, memberId);
        return toDto(e);
    }

    /**
     * 任务刚进入「收集中」时，自动把它分享到飞书群请同伴一起补充。
     *
     * <p>这一步原本是手工的：改完状态、复制链接、发群里、@人。手工步骤越多，越容易在
     * 想法最热的那一刻被打断，所以直接由状态变化触发。只在「进入」这个状态时发一次，
     * 之后再保存任何字段都不会重复打扰群里。
     *
     * @param entity         保存后的任务
     * @param previousStatus 保存前的状态；新建任务时为 null
     * @param memberId       当前操作人成员 id
     */
    private void announceCollecting(TaskEntity entity, String previousStatus, String memberId) {
        if (!COLLECTING.equals(entity.getStatus()) || COLLECTING.equals(previousStatus)) {
            return;
        }
        try {
            taskNotifier.notifyCollecting(entity.getId(), entity.getProjectId(), entity.getTitle(),
                    projectName(entity.getProjectId()), entity.getDescription(), taskNotifier.feishuOpenId(memberId));
        } catch (RuntimeException ex) {
            // 通知失败不该让状态改不动：状态已经落库，群里没收到最多是少一次提醒
            LOG.warn("任务进入收集中的飞书通知发送失败：{}", ex.getMessage());
        }
    }

    /** 新建任务后同步到飞书群；通知失败只记日志，不影响创建结果。 */
    private void announceCreated(TaskEntity entity, String creatorId) {
        try {
            taskNotifier.notifyTaskCreated(entity.getId(), entity.getProjectId(), entity.getTitle(),
                    projectName(entity.getProjectId()), memberName(creatorId), entity.getDescription());
        } catch (RuntimeException ex) {
            LOG.warn("新建任务的飞书通知发送失败：{}", ex.getMessage());
        }
    }

    /**
     * 任务状态变了就同步到飞书群；进入「收集中」由 {@link #announceCollecting} 单独发
     * 创意收集卡片，这里跳过避免同一次变化打两次扰。
     */
    private void announceStatusChanged(TaskEntity entity, String previousStatus, String memberId) {
        if (entity.getStatus() == null || entity.getStatus().equals(previousStatus)
                || COLLECTING.equals(entity.getStatus()) || previousStatus == null) {
            return;
        }
        try {
            taskNotifier.notifyStatusChanged(entity.getId(), entity.getProjectId(), entity.getTitle(),
                    projectName(entity.getProjectId()), previousStatus, entity.getStatus(), memberName(memberId));
        } catch (RuntimeException ex) {
            LOG.warn("任务状态变化的飞书通知发送失败：{}", ex.getMessage());
        }
    }

    /** 写透保存里比对新旧子任务 id，新增的合并成一张卡片同步到飞书群。 */
    private void announceNewSubs(TaskEntity entity, TaskEntity previous, List<Step> subs) {
        Set<String> known = nn(parse(previous.getDetailJson()).subs()).stream()
                .map(Step::id).filter(StringUtils::hasText).collect(Collectors.toSet());
        List<String> added = nn(subs).stream()
                .filter(s -> StringUtils.hasText(s.id()) && !known.contains(s.id()))
                .map(Step::text).filter(StringUtils::hasText).toList();
        if (added.isEmpty()) {
            return;
        }
        try {
            taskNotifier.notifySubtasksAdded(entity.getId(), entity.getProjectId(), entity.getTitle(),
                    projectName(entity.getProjectId()), added);
        } catch (RuntimeException ex) {
            LOG.warn("新增子任务的飞书通知发送失败：{}", ex.getMessage());
        }
    }

    /** 项目名称；项目不存在时回落到 id，别让通知因此失败。 */
    private String projectName(String projectId) {
        try {
            return projects.get(projectId).name();
        } catch (NotFoundException ex) {
            return projectId;
        }
    }

    /** 成员名字；查不到时回落到 id，空 id 返回 null。 */
    private String memberName(String memberId) {
        if (!StringUtils.hasText(memberId)) {
            return null;
        }
        return members.list().stream().filter(m -> m.id().equals(memberId))
                .map(m -> m.name()).findFirst().orElse(memberId);
    }

    /** 上海时区的今天，任务默认截止日期用它，不再写死原型日期。 */
    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    }

    /**
     * 群聊快速收集：把飞书群里的一句话直接变成任务。
     *
     * <p>标题取第一行、描述留全文，并打上「群聊快速收集」标签 —— 事后一眼能认出这条是
     * 随口冒出来的想法，而不是正式排期的需求。
     *
     * @param projectId 落点项目 id
     * @param title     任务标题
     * @param desc      任务描述（原始消息全文）
     * @param ownerId   负责人成员 id；识别不出发言人时由调用方给默认值
     * @param source    标签值，例如「群聊快速收集」
     * @return 创建出来的任务
     */
    public TaskDto createFromChat(String projectId, String title, String desc, String ownerId, String source) {
        TaskEntity e = new TaskEntity();
        e.setId("t" + System.currentTimeMillis());
        e.setTitle(title);
        e.setProjectId(projectId);
        e.setStatus("待办");
        e.setPr("P2");
        e.setOwnerId(ownerId);
        e.setCreatedBy(ownerId);
        e.setDueDate(today());
        e.setDescription(desc == null ? "" : desc);
        TaskDetail detail = new TaskDetail(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new CustomProp("来源", source)),
                List.of(new Activity("ri-chat-quote-line", "#9A9DA9", "由飞书群聊快速收集创建", "刚刚")),
                List.of());
        e.setDetailJson(write(detail));
        taskMapper.insert(e);
        announceCreated(e, ownerId);
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
                nn(d.linkTasks()), nn(d.comments()), nn(d.custom()), nn(d.activity()), nn(d.images()),
                e.getCreatedBy(), e.getCompletedAt() == null ? null : e.getCompletedAt().toString());
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
