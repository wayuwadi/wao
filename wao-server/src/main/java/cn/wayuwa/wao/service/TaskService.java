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
import cn.wayuwa.wao.dto.PdcaCycle;
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
     * 列出当前成员有权进入的项目中的任务（跨项目）。
     *
     * @param memberId 当前登录成员 id
     * @return 可见任务列表
     */
    public List<TaskDto> list(String memberId) {
        return taskMapper.selectList(null).stream()
                .filter(task -> visibleTo(task, memberId))
                .map(this::toDto)
                .toList();
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
     * 按 id 获取当前成员有权进入的项目中的任务。
     *
     * @param id 任务 id
     * @param memberId 当前登录成员 id
     * @return 任务
     * @throws NotFoundException 任务不存在或所属项目对当前成员不可见时
     */
    public TaskDto get(String id, String memberId) {
        return toDto(requireVisible(id, memberId));
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
        requireProjectVisible(req.project(), createdBy);
        TaskEntity e = new TaskEntity();
        e.setId("t" + System.currentTimeMillis());
        e.setTitle(req.title().trim());
        e.setProjectId(req.project());
        e.setStatus("待办");
        e.setPr(StringUtils.hasText(req.pr()) ? req.pr() : "P2");
        e.setOwnerId(StringUtils.hasText(req.owner()) ? req.owner() : "wg");
        e.setCreatedBy(createdBy);
        e.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        e.setDueDate(StringUtils.hasText(req.due()) ? req.due() : today());
        e.setDescription(req.desc() == null ? "" : req.desc());
        String startAt = dateTime(req.startAt());
        String endAt = dateTime(req.endAt());
        validateRange(startAt, endAt);
        List<PdcaCycle> pdcaCycles = validatePdca(req.pdcaCycles());
        TaskDetail detail = new TaskDetail(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Activity("ri-add-line", "#9A9DA9", "创建了任务", now(), createdBy)),
                nn(req.images()), StringUtils.hasText(req.groupId()) ? req.groupId() : null,
                startAt, endAt, pdcaCycles);
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
        TaskEntity e = requireVisible(id, memberId);
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
            activity.add(0, edit("将状态改为「" + req.status() + "」", memberId));
        }
        if (StringUtils.hasText(req.pr()) && !req.pr().equals(e.getPr())) {
            e.setPr(req.pr());
            activity.add(0, edit("将优先级改为 " + req.pr(), memberId));
        }
        if (StringUtils.hasText(req.owner()) && !req.owner().equals(e.getOwnerId())) {
            e.setOwnerId(req.owner());
            activity.add(0, edit("更换了负责人", memberId));
        }
        // 日期用「非 null 即修改」而不是「非空即修改」：空串是「清空日期」这个明确意图，
        // 按空字段跳过的话，日期就成了只能改不能删的字段。清空写空串而不是 null，
        // 因为 MyBatis-Plus 的 updateById 默认跳过 null 字段，写 null 等于没改。
        String start = date(req.start());
        if (start != null && !start.equals(nz(e.getStartDate()))) {
            e.setStartDate(start);
            activity.add(0, edit(start.isEmpty() ? "清空了开始日期" : "将开始日期改为 " + start, memberId));
        }
        String due = date(req.due());
        if (due != null && !due.equals(nz(e.getDueDate()))) {
            e.setDueDate(due);
            activity.add(0, edit(due.isEmpty() ? "清空了截止日期" : "将截止日期改为 " + due, memberId));
        }

        TaskDetail updated = new TaskDetail(d.parts(), d.steps(), d.subs(), d.files(), d.docs(),
                d.linkTasks(), d.comments(), d.custom(), activity, d.images(), d.groupId(),
                d.startAt(), d.endAt(), d.pdcaCycles());
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
        if (previous != null) {
            requireVisible(id, memberId);
        } else {
            requireProjectVisible(dto.project(), memberId);
        }
        String previousStatus = previous == null ? null : previous.getStatus();
        TaskEntity e = new TaskEntity();
        e.setId(id);
        e.setTitle(dto.title());
        e.setProjectId(dto.project());
        e.setStatus(dto.status());
        e.setPr(dto.pr());
        e.setOwnerId(dto.owner());
        // 前端用本地生成的稳定 id 走 PUT upsert，首次写入的 DTO 还没有服务端创建人；
        // 必须从当前会话补齐，否则创建人筛选和后续协作通知都会丢失身份。
        e.setCreatedBy(previous == null ? blankOr(dto.createdBy(), memberId) : previous.getCreatedBy());
        // 创建时间由服务端掌管：首次 PUT upsert 取当前时间，后续全文保存必须原样保留。
        e.setCreatedAt(previous == null
                ? LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                : previous.getCreatedAt());
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
        String startAt = dateTime(dto.startAt());
        String endAt = dateTime(dto.endAt());
        validateRange(startAt, endAt);
        List<PdcaCycle> pdcaCycles = validatePdca(dto.pdcaCycles());
        TaskDetail detail = new TaskDetail(dto.parts(), dto.steps(), dto.subs(), dto.files(),
                dto.docs(), dto.linkTasks(), dto.comments(), dto.custom(), dto.activity(), dto.images(),
                dto.groupId(), startAt, endAt, pdcaCycles);
        e.setDetailJson(write(detail));
        if (previous == null) {
            taskMapper.insert(e);
            announceCreated(e, memberId);
        } else {
            taskMapper.updateById(e);
            announceNewSubs(e, previous, dto.subs(), memberId);
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

    /**
     * 写透保存里比对新旧子任务 id，新增的合并成一张卡片同步到飞书群。
     *
     * <p>卡片要说清是**谁**加的、加到**谁的**任务下：娃哥往娃弟建的任务里塞子任务时，群里
     * 原来只看得到「某任务新增了子任务」，既不知道是娃哥动的手，任务的创建人和负责人也
     * 没被 @ 到，等于没通知。现在带上操作人，并单独 @ 该知道这件事的人（创建人 + 负责人，
     * 减去操作人自己）。
     *
     * @param entity   保存后的任务
     * @param previous 保存前的任务，用于比对哪些子任务是新的
     * @param subs     本次写透的完整子任务列表
     * @param memberId 当前操作人成员 id
     */
    private void announceNewSubs(TaskEntity entity, TaskEntity previous, List<Step> subs, String memberId) {
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
                    projectName(entity.getProjectId()), added, memberName(memberId),
                    memberName(entity.getCreatedBy()), stakeholderOpenIds(entity, memberId));
        } catch (RuntimeException ex) {
            LOG.warn("新增子任务的飞书通知发送失败：{}", ex.getMessage());
        }
    }

    /**
     * 该为这条任务收到点名通知的人：任务创建人与负责人，去掉操作人自己。
     *
     * @param entity   任务
     * @param memberId 当前操作人成员 id
     * @return 飞书 open_id 列表；没人绑过飞书时为空
     */
    private List<String> stakeholderOpenIds(TaskEntity entity, String memberId) {
        List<String> out = new ArrayList<>();
        for (String candidate : List.of(nz(entity.getCreatedBy()), nz(entity.getOwnerId()))) {
            if (!StringUtils.hasText(candidate) || candidate.equals(memberId)) {
                continue;
            }
            String openId = taskNotifier.feishuOpenId(candidate);
            if (StringUtils.hasText(openId) && !out.contains(openId)) {
                out.add(openId);
            }
        }
        return out;
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
     * 群聊快速收集：把飞书群里的一句话直接变成未排期任务。
     *
     * <p>标题取第一行、描述留全文，并打上「群聊快速收集」标签 —— 事后一眼能认出这条是
     * 随口冒出来的想法，而不是正式排期的需求。
     *
     * @param projectId 落点项目 id
     * @param title     任务标题
     * @param desc      任务描述（原始消息全文）
     * @param ownerId   已绑定的发言人成员 id
     * @param source    标签值，例如「群聊快速收集」
     * @return 创建出来的任务
     */
    public TaskDto createFromChat(String projectId, String title, String desc, String ownerId, String source) {
        requireProjectVisible(projectId, ownerId);
        TaskEntity e = new TaskEntity();
        e.setId("t" + System.currentTimeMillis());
        e.setTitle(title);
        e.setProjectId(projectId);
        e.setStatus("待办");
        e.setPr("P2");
        e.setOwnerId(ownerId);
        e.setCreatedBy(ownerId);
        e.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        // 群里随口记下的通常是未来想做、但尚未排期的事，不能全部压到今天。
        e.setDueDate(null);
        e.setDescription(desc == null ? "" : desc);
        TaskDetail detail = new TaskDetail(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new CustomProp("来源", source)),
                List.of(new Activity("ri-chat-quote-line", "#9A9DA9", "由飞书群聊快速收集创建", now())),
                List.of(), null);
        e.setDetailJson(write(detail));
        taskMapper.insert(e);
        announceCreated(e, ownerId);
        return toDto(e);
    }

    /** 原子追加一条评论并同步飞书群，避免全文自动保存请求相互覆盖评论。 */
    public CommentDto addComment(String id, String memberId, String text) {
        TaskEntity entity = requireVisible(id, memberId);
        TaskDetail detail = parse(entity.getDetailJson());
        CommentDto comment = new CommentDto("c" + System.currentTimeMillis(), memberId,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(), text.trim());
        List<CommentDto> comments = new ArrayList<>(nn(detail.comments()));
        comments.add(comment);
        saveComments(entity, detail, comments, "添加了评论", memberId);
        TaskDto task = toDto(entity);
        String memberName = members.list().stream().filter(m -> m.id().equals(memberId)).map(m -> m.name()).findFirst().orElse(memberId);
        String projectName;
        try { projectName = projects.get(task.project()).name(); } catch (NotFoundException ex) { projectName = task.project(); }
        commentNotifier.notifyComment(task, comment, memberName, projectName, mentionOpenIds(comment.text(), memberId));
        return comment;
    }

    /** 解析评论正文里 @ 到的成员，换成飞书 open_id；@自己不通知，没绑飞书的解析不出来就跳过。 */
    private List<String> mentionOpenIds(String text, String operatorMemberId) {
        if (!StringUtils.hasText(text) || !text.contains("@")) {
            return List.of();
        }
        return members.list().stream()
                .filter(m -> !m.id().equals(operatorMemberId) && text.contains("@" + m.name()))
                .map(m -> taskNotifier.feishuOpenId(m.id()))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
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
        TaskEntity entity = requireVisible(id, memberId);
        TaskDetail detail = parse(entity.getDetailJson());
        List<CommentDto> comments = new ArrayList<>(nn(detail.comments()));
        int index = indexOfComment(comments, commentId);
        CommentDto old = comments.get(index);
        requireAuthor(old, memberId);
        CommentDto updated = new CommentDto(blankOr(old.id(), commentId), old.who(), old.time(), text.trim());
        comments.set(index, updated);
        saveComments(entity, detail, comments, "修改了评论", memberId);
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
        TaskEntity entity = requireVisible(id, memberId);
        TaskDetail detail = parse(entity.getDetailJson());
        List<CommentDto> comments = new ArrayList<>(nn(detail.comments()));
        int index = indexOfComment(comments, commentId);
        requireAuthor(comments.get(index), memberId);
        comments.remove(index);
        saveComments(entity, detail, comments, "删除了评论", memberId);
    }

    /**
     * 删除一个任务。
     *
     * @param id 任务 id
     * @param memberId 当前登录成员 id
     * @throws NotFoundException 任务不存在或所属项目对当前成员不可见时
     */
    public void delete(String id, String memberId) {
        requireVisible(id, memberId);
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
    private void saveComments(TaskEntity entity, TaskDetail detail, List<CommentDto> comments,
            String activityText, String memberId) {
        List<Activity> activity = new ArrayList<>(nn(detail.activity()));
        activity.add(0, edit(activityText, memberId));
        entity.setDetailJson(write(new TaskDetail(detail.parts(), detail.steps(), detail.subs(), detail.files(), detail.docs(),
                detail.linkTasks(), comments, detail.custom(), activity, detail.images(), detail.groupId(),
                detail.startAt(), detail.endAt(), detail.pdcaCycles())));
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

    /** 未授权与任务不存在都返回 404，避免泄露任务及其所属项目的存在。 */
    private TaskEntity requireVisible(String id, String memberId) {
        TaskEntity entity = require(id);
        if (!visibleTo(entity, memberId)) {
            throw new NotFoundException("任务不存在：" + id);
        }
        return entity;
    }

    private boolean visibleTo(TaskEntity entity, String memberId) {
        if (!StringUtils.hasText(memberId)) {
            return true;
        }
        try {
            projects.get(entity.getProjectId(), memberId);
            return true;
        } catch (NotFoundException ex) {
            return false;
        }
    }

    private void requireProjectVisible(String projectId, String memberId) {
        if (StringUtils.hasText(memberId)) {
            projects.get(projectId, memberId);
        }
    }

    private TaskDto toDto(TaskEntity e) {
        TaskDetail d = parse(e.getDetailJson());
        return new TaskDto(
                e.getId(), e.getTitle(), e.getProjectId(), e.getStatus(), e.getPr(), e.getOwnerId(),
                nn(d.parts()), e.getStartDate(), e.getDueDate(), e.getWaiting(),
                e.getDescription() == null ? "" : e.getDescription(),
                nn(d.steps()), nn(d.subs()), nn(d.files()), nn(d.docs()),
                nn(d.linkTasks()), nn(d.comments()), nn(d.custom()), nn(d.activity()), nn(d.images()),
                e.getCreatedBy(), e.getCompletedAt() == null ? null : e.getCompletedAt().toString(),
                d.groupId(), d.startAt(), d.endAt(), nn(d.pdcaCycles()),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }

    /**
     * 一条编辑类活动。
     *
     * <p>正文不带主语、时间写真实时刻：以前统一写「你……」和「刚刚」，结果别人打开这条任务
     * 看到的也是「你改的」，而且不管过多久都显示「刚刚」。
     *
     * @param text     不含主语的事件描述
     * @param memberId 操作人成员 id
     */
    private static Activity edit(String text, String memberId) {
        return new Activity("ri-edit-line", "#9A9DA9", text, now(),
                StringUtils.hasText(memberId) ? memberId : null);
    }

    /** 当下时刻（上海时区，ISO 8601 带偏移），活动时间线按它排序与展示。 */
    private static String now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }

    private TaskDetail parse(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return new TaskDetail(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }
        try {
            return json.readValue(detailJson, TaskDetail.class);
        } catch (JsonProcessingException ex) {
            return new TaskDetail(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
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

    /** 请求里的日期：null 表示不改，其余去掉空白（空串即清空）。 */
    private static String date(String value) {
        return value == null ? null : value.trim();
    }

    /** 本地 ISO 日期时间；空值表示普通截止任务，非法值直接拒绝而不是把坏日程写进详情 JSON。 */
    private static String dateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            LocalDateTime.parse(trimmed);
            return trimmed;
        } catch (RuntimeException exception) {
            throw new BadRequestException("任务时间必须是 yyyy-MM-ddTHH:mm 格式");
        }
    }

    /** 时间段要么同时为空，要么结束严格晚于开始。 */
    private static void validateRange(String startAt, String endAt) {
        if (startAt == null && endAt == null) return;
        if (startAt == null || endAt == null || !LocalDateTime.parse(endAt).isAfter(LocalDateTime.parse(startAt))) {
            throw new BadRequestException("任务时间段必须同时填写，且结束时间晚于开始时间");
        }
    }

    /**
     * 校验客户端写透的 PDCA 轮次，避免产生“已闭环但没有计划 / 复盘 / 行动”的假记录。
     * 未闭环轮次允许逐项自动保存，所以只校验结果枚举；闭环后才要求内容完整。
     */
    private static List<PdcaCycle> validatePdca(List<PdcaCycle> cycles) {
        List<PdcaCycle> safe = nn(cycles);
        Set<String> outcomes = Set.of("达成", "部分达成", "未达成");
        for (PdcaCycle cycle : safe) {
            if (cycle == null || !StringUtils.hasText(cycle.id()) || !StringUtils.hasText(cycle.startedAt())) {
                throw new BadRequestException("PDCA 轮次缺少标识或开始时间");
            }
            if (StringUtils.hasText(cycle.outcome()) && !outcomes.contains(cycle.outcome())) {
                throw new BadRequestException("PDCA 结果只能是达成、部分达成或未达成");
            }
            if (StringUtils.hasText(cycle.reviewedAt())
                    && (!StringUtils.hasText(cycle.plan()) || !StringUtils.hasText(cycle.check())
                    || !StringUtils.hasText(cycle.act()) || !StringUtils.hasText(cycle.outcome()))) {
                throw new BadRequestException("已闭环的 PDCA 必须包含计划、结果、复盘和改进行动");
            }
        }
        return safe;
    }

    /** 库里的日期：null 与空串在这里是同一件事（都表示没设置）。 */
    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
