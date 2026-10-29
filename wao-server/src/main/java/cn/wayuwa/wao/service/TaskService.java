package cn.wayuwa.wao.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
    private final WorkItemNumberService numbers;
    @org.springframework.beans.factory.annotation.Autowired
    private TodoHistoryService todoHistory;

    public TaskService(TaskMapper taskMapper, ObjectMapper json, FeishuCommentNotifier commentNotifier,
            FeishuTaskNotifier taskNotifier, MemberService members, ProjectService projects, WorkItemNumberService numbers) {
        this.taskMapper = taskMapper;
        this.json = json;
        this.commentNotifier = commentNotifier;
        this.taskNotifier = taskNotifier;
        this.members = members;
        this.projects = projects;
        this.numbers = numbers;
    }

    /**
     * 列出当前成员有权进入的项目中的任务（跨项目）。
     *
     * @param memberId 当前登录成员 id
     * @return 可见任务列表
     */
    public List<TaskDto> list(String memberId) {
        Set<String> visibleProjectIds = projects.visibleProjectIds(memberId);
        var scope = new QueryWrapper<TaskEntity>();
        scope.and(q -> {
            if (!visibleProjectIds.isEmpty()) q.in("project_id", visibleProjectIds).or();
            q.nested(inbox -> inbox.eq("project_id", "").eq("owner_id", memberId));
        });
        return numbers.attach(taskMapper.selectList(scope).stream()
                .map(this::rawDto)
                .toList());
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

    /** 在工作区工具的事务内锁住任务归属，避免限定授权检查后被并发移到另一项目。 */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public TaskDto lockWorkspaceTask(String id, String memberId) {
        TaskEntity entity = taskMapper.lockById(id);
        if (entity == null || !visibleTo(entity, memberId)) throw new NotFoundException("任务不存在：" + id);
        return toDto(entity);
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
    @org.springframework.transaction.annotation.Transactional
    public TaskDto create(CreateTaskRequest req, String createdBy) {
        requireProjectVisible(req.project(), createdBy);
        TaskEntity e = new TaskEntity();
        e.setId("t" + System.currentTimeMillis());
        e.setTitle(req.title().trim());
        e.setProjectId(req.project() == null ? "" : req.project());
        e.setStatus("待办");
        e.setPr(StringUtils.hasText(req.pr()) ? req.pr() : "P2");
        e.setOwnerId(!StringUtils.hasText(req.project()) && StringUtils.hasText(createdBy) ? createdBy : StringUtils.hasText(req.owner()) ? req.owner() : "wg");
        e.setCreatedBy(createdBy);
        e.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        // 没选截止日期就不排期，不再默认落在今天。
        e.setDueDate(StringUtils.hasText(req.due()) ? req.due() : null);
        e.setDescription(req.desc() == null ? "" : req.desc());
        String startAt = dateTime(req.startAt());
        String endAt = dateTime(req.endAt());
        validateRange(startAt, endAt);
        List<PdcaCycle> pdcaCycles = validatePdca(req.pdcaCycles());
        TaskDetail detail = new TaskDetail(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Activity("ri-add-line", "#9A9DA9", "创建了任务", now(), createdBy)),
                nn(req.images()), StringUtils.hasText(req.groupId()) ? req.groupId() : null,
                startAt, endAt, pdcaCycles).withWorkItemType(workItemType(req.workItemType(), null));
        e.setDetailJson(write(detail));
        taskMapper.insert(e);
        recordTodo(null, e, createdBy, "CREATED", null);
        announceCreated(e, createdBy);
        return toDto(e);
    }

    /** 仅修改指定子任务状态；行锁与读取时的子任务版本共同防止覆盖并发编辑。 */
    @org.springframework.transaction.annotation.Transactional
    public TaskDto updateSubtaskStatus(String id, String subId, String status, String expectedVersion, String memberId) {
        if (status == null || !Set.of("未开始", "进行中", "待验证", "已完成").contains(status))
            throw new BadRequestException("子任务状态无效");
        if (!StringUtils.hasText(expectedVersion)) throw new BadRequestException("需要 expected_subtask_version");
        requireVisible(id, memberId);
        TaskEntity entity = taskMapper.lockById(id);
        if (entity == null) throw new NotFoundException("任务不存在");
        if (!visibleTo(entity, memberId)) throw new NotFoundException("任务不存在");
        if (!expectedVersion.equals(SubtaskTree.version(parse(entity.getDetailJson()).subs())))
            throw new cn.wayuwa.wao.common.ConflictException("子任务已有更新，请重新读取后重试");
        TaskEntity before = historyCopy(entity);
        try {
            var detail = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(entity.getDetailJson());
            var subs = detail.path("subs");
            for (int i = 0; i < subs.size(); i++) {
                var sub = (com.fasterxml.jackson.databind.node.ObjectNode) subs.get(i);
                String key = sub.path("id").asText("");
                if (key.isBlank()) key = id + "#" + i;
                if (!key.equals(subId)) continue;
                sub.put("done", DONE.equals(status));
                if ("进行中".equals(status) || "待验证".equals(status)) sub.put("status", status);
                else sub.remove("status");
                List<Activity> activity = new ArrayList<>(nn(parse(entity.getDetailJson()).activity()));
                activity.add(0, edit("将子任务「" + sub.path("text").asText() + "」改为" + status, memberId));
                detail.set("activity", json.valueToTree(activity));
                String stored = json.writeValueAsString(detail);
                taskMapper.updateDetail(id, stored);
                entity.setDetailJson(stored);
                recordTodo(before, entity, memberId, "STEP_CHANGED", null);
                return toDto(entity);
            }
            throw new NotFoundException("子任务不存在");
        } catch (JsonProcessingException e) {
            throw new BadRequestException("任务详情无法读取或保存，未修改子任务");
        }
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
    @org.springframework.transaction.annotation.Transactional
    public TaskDto patch(String id, PatchTaskRequest req, String memberId) {
        requireVisible(id, memberId);
        TaskEntity e = taskMapper.lockById(id);
        if (e == null || !visibleTo(e, memberId)) throw new NotFoundException("任务不存在");
        TaskEntity before = historyCopy(e);
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
                d.startAt(), d.endAt(), d.pdcaCycles(), d.hostedPages(), d.workItemType(),
                StringUtils.hasText(e.getStartDate()) && !StringUtils.hasText(d.startAt()) ? d.schedulePeriod() : null);
        e.setDetailJson(write(updated));
        taskMapper.updateById(e);
        recordTodo(before, e, memberId, "TASK_CHANGED", null);
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
    @org.springframework.transaction.annotation.Transactional
    public TaskDto replace(String id, TaskDto dto, String memberId) {
        TaskEntity previous = taskMapper.lockById(id);
        if (previous == null && (dto.editVersion() != null || dto.subtaskVersion() != null
                || dto.number() != null || numbers.wasTaskAllocated(id))) {
            throw new NotFoundException("任务已删除或不存在，旧草稿不能重新创建任务");
        }
        if (dto.subtaskVersion() != null || dto.editVersion() != null) {
            if (previous != null && dto.editVersion() != null && !dto.editVersion().equals(editVersion(previous)))
                throw new cn.wayuwa.wao.common.ConflictException("任务内容已被其他窗口更新，请保留本机内容并查看服务器版本");
            if (previous != null && dto.subtaskVersion() != null && !dto.subtaskVersion().equals(SubtaskTree.version(parse(previous.getDetailJson()).subs())))
                throw new cn.wayuwa.wao.common.ConflictException("子任务已被其他窗口更新，请保留草稿并刷新后重试");
        }
        if (previous != null) {
            requireVisible(id, memberId);
        } else {
            requireProjectVisible(dto.project(), memberId);
        }
        String previousStatus = previous == null ? null : previous.getStatus();
        TaskEntity e = new TaskEntity();
        e.setId(id);
        if (previous != null && !java.util.Objects.equals(previous.getProjectId(), dto.project())) {
            requireProjectVisible(dto.project(), memberId);
            if (previous.getParentTaskId() != null || taskMapper.countChildTasks(id) > 0)
                throw new cn.wayuwa.wao.common.BadRequestException("请先解除父子任务关系，再移动到其他项目");
        }
        e.setParentTaskId(previous == null ? null : previous.getParentTaskId());
        e.setTitle(dto.title());
        e.setProjectId(dto.project() == null ? "" : dto.project());
        e.setStatus(dto.status());
        e.setPr(dto.pr());
        e.setOwnerId(!StringUtils.hasText(dto.project()) && previous == null ? memberId : dto.owner());
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
        SubtaskTree.validate(dto.subs());
        TaskDetail detail = new TaskDetail(dto.parts(), dto.steps(), dto.subs(), dto.files(),
                dto.docs(), dto.linkTasks(), dto.comments(), dto.custom(), dto.activity(), dto.images(),
                dto.groupId(), startAt, endAt, pdcaCycles, dto.hostedPages(), workItemType(dto.workItemType(), previous),
                compatiblePeriod(dto, previous, startAt));
        e.setDetailJson(write(detail));
        if (previous == null) {
            taskMapper.insert(e);
            recordTodo(null, e, memberId, "CREATED", null);
            announceCreated(e, memberId);
        } else {
            taskMapper.updateById(e);
            recordTodo(previous, e, memberId, "TASK_CHANGED", null);
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
        afterCommit(() -> {
        try {
            taskNotifier.notifyCollecting(entity.getId(), entity.getProjectId(), entity.getTitle(),
                    projectName(entity.getProjectId()), entity.getDescription(), taskNotifier.feishuOpenId(memberId));
        } catch (RuntimeException ex) {
            // 通知失败不该让状态改不动：状态已经落库，群里没收到最多是少一次提醒
            LOG.warn("任务进入收集中的飞书通知发送失败：{}", ex.getMessage());
        }
        });
    }

    /** 新建任务后同步到飞书群；通知失败只记日志，不影响创建结果。 */
    private void announceCreated(TaskEntity entity, String creatorId) {
        afterCommit(() -> {
        try {
            taskNotifier.notifyTaskCreated(entity.getId(), entity.getProjectId(), entity.getTitle(),
                    projectName(entity.getProjectId()), memberName(creatorId), entity.getDescription());
        } catch (RuntimeException ex) {
            LOG.warn("新建任务的飞书通知发送失败：{}", ex.getMessage());
        }
        });
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
        afterCommit(() -> {
        try {
            taskNotifier.notifyStatusChanged(entity.getId(), entity.getProjectId(), entity.getTitle(),
                    projectName(entity.getProjectId()), previousStatus, entity.getStatus(), memberName(memberId));
        } catch (RuntimeException ex) {
            LOG.warn("任务状态变化的飞书通知发送失败：{}", ex.getMessage());
        }
        });
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
        afterCommit(() -> {
        try {
            taskNotifier.notifySubtasksAdded(entity.getId(), entity.getProjectId(), entity.getTitle(),
                    projectName(entity.getProjectId()), added, memberName(memberId),
                    memberName(entity.getCreatedBy()), stakeholderOpenIds(entity, memberId));
        } catch (RuntimeException ex) {
            LOG.warn("新增子任务的飞书通知发送失败：{}", ex.getMessage());
        }
        });
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

    /**
     * 群聊快速收集：把飞书群里的一句话直接变成未排期任务。
     *
     * <p>标题取第一行、描述留全文，并打上「群聊快速收集」标签 —— 事后一眼能认出这条是
     * 随口冒出来的想法，而不是正式排期的需求。这条入口已经在原飞书消息上回表情，
     * 因此不再触发通用“新建任务” Webhook，避免同一操作再打扰群聊。
     *
     * @param projectId 落点项目 id
     * @param title     任务标题
     * @param desc      任务描述（原始消息全文）
     * @param ownerId   已绑定的发言人成员 id
     * @param source    标签值，例如「群聊快速收集」
     * @return 创建出来的任务
     */
    @org.springframework.transaction.annotation.Transactional
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
        recordTodo(null, e, ownerId, "CREATED", null);
        return toDto(e);
    }

    /**
     * 群聊快速收集：飞书原消息被编辑后，只覆盖由该消息维护的标题与描述。
     *
     * <p>状态、负责人、日期、子任务、评论等后续在 WAO 内补充的内容必须原样保留；只有源消息
     * 实际改变时才窄更新这两个字段，避免轮询覆盖并发保存的结构化详情。
     *
     * @param id      首次收集创建的任务 id
     * @param title   编辑后消息推导出的标题
     * @param desc    编辑后完整正文（含已转存的图片 Markdown）
     * @param ownerId 原飞书发言人对应的成员 id，用于校验任务可见性
     * @return 更新后的任务
     */
    @org.springframework.transaction.annotation.Transactional
    public TaskDto updateFromChat(String id, String title, String desc, String ownerId) {
        TaskEntity e = requireVisible(id, ownerId);
        TaskEntity before = historyCopy(e);
        String nextTitle = title == null ? "" : title.trim();
        String nextDescription = desc == null ? "" : desc;
        if (nextTitle.equals(e.getTitle()) && nextDescription.equals(nz(e.getDescription()))) {
            return toDto(e);
        }
        taskMapper.updateChatSourceFields(id, nextTitle, nextDescription);
        TaskEntity updated = require(id);
        recordTodo(before, updated, ownerId, "TASK_CHANGED", null);
        return toDto(updated);
    }

    /**
     * 当前成员显式把一条自己可见的任务发到通知群；自动成功通知关闭时仍保留这个人工入口。
     *
     * @param id 任务 id
     * @param memberId 当前登录成员 id
     * @return 是否真实发出了飞书卡片
     */
    public boolean notifyManually(String id, String memberId) {
        TaskEntity entity = requireVisible(id, memberId);
        return taskNotifier.notifyManualTask(entity.getId(), entity.getProjectId(), entity.getTitle(),
                projectName(entity.getProjectId()), memberName(memberId), entity.getDescription());
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
    @org.springframework.transaction.annotation.Transactional
    public void delete(String id, String memberId) {
        TaskEntity previous = requireVisible(id, memberId);
        taskMapper.deleteById(id);
        recordTodo(previous, null, memberId, "DELETED", null);
    }

    /**
     * 删除由飞书原消息创建的任务；调用方必须已经用持久消息关系确认来源。
     *
     * <p>任务可能已被成员手动删除，因此此入口保持幂等，不把“不存在”当成撤回失败。
     *
     * @param id 快速收集事件记录的任务 id
     * @return 本次是否实际删除了一条任务
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean deleteFromChat(String id) {
        if (!StringUtils.hasText(id)) return false;
        TaskEntity previous = taskMapper.selectById(id);
        if (taskMapper.deleteById(id) == 0) return false;
        if (previous != null) recordTodo(previous, null, previous.getOwnerId(), "DELETED", null);
        return true;
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
                detail.startAt(), detail.endAt(), detail.pdcaCycles(), detail.hostedPages(), detail.workItemType(), detail.schedulePeriod())));
        taskMapper.updateById(entity);
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private TaskEntity historyCopy(TaskEntity entity) {
        return todoHistory == null ? null : todoHistory.copy(entity);
    }

    private void recordTodo(TaskEntity before, TaskEntity after, String actor, String type, String operationId) {
        if (todoHistory != null) todoHistory.record(before, after, actor, type, operationId);
    }

    private static void afterCommit(Runnable callback) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            callback.run();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { callback.run(); }
                });
    }

    private String compatiblePeriod(TaskDto incoming, TaskEntity previous, String startAt) {
        // A legacy full save that explicitly switches to exact time or clears the date wins over stale period data.
        if (!StringUtils.hasText(incoming.start()) || StringUtils.hasText(startAt)) return null;
        String period = incoming.schedulePeriod();
        if (period == null && previous != null) {
            period = parse(previous.getDetailJson()).schedulePeriod();
        }
        if (period != null && !Set.of("MORNING", "AFTERNOON", "EVENING").contains(period))
            throw new BadRequestException("执行时段无效");
        return period;
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
        if (!StringUtils.hasText(entity.getProjectId())) return java.util.Objects.equals(entity.getOwnerId(), memberId);
        try {
            projects.get(entity.getProjectId(), memberId);
            return true;
        } catch (NotFoundException ex) {
            return false;
        }
    }

    private void requireProjectVisible(String projectId, String memberId) {
        if (StringUtils.hasText(memberId) && StringUtils.hasText(projectId)) {
            projects.get(projectId, memberId);
        }
    }

    /** 对持久化编辑字段取稳定摘要；不含读取时分配的展示编号，无需修改历史数据。 */
    private String editVersion(TaskEntity e) {
        try {
            byte[] snapshot = json.writeValueAsBytes(java.util.Arrays.asList(
                    e.getTitle(), e.getProjectId(), e.getStatus(), e.getPr(), e.getOwnerId(),
                    e.getStartDate(), e.getDueDate(), e.getWaiting(), e.getDescription(), parse(e.getDetailJson())));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(snapshot));
        } catch (Exception ex) { throw new IllegalStateException("任务编辑版本计算失败", ex); }
    }

    private TaskDto toDto(TaskEntity e) {
        return numbers.attach(List.of(rawDto(e))).get(0);
    }

    private String workItemType(String requested, TaskEntity previous) {
        String type = requested != null ? requested : previous == null ? "task" : parse(previous.getDetailJson()).workItemType();
        if (type == null) return "task";
        if (!java.util.Set.of("task", "requirement", "bug").contains(type)) {
            throw new cn.wayuwa.wao.common.BadRequestException("工作项类型必须是需求、任务或缺陷");
        }
        return type;
    }

    private TaskDto rawDto(TaskEntity e) {
        TaskDetail d = parse(e.getDetailJson());
        return new TaskDto(
                e.getId(), e.getTitle(), e.getProjectId(), e.getStatus(), e.getPr(), e.getOwnerId(),
                nn(d.parts()), e.getStartDate(), e.getDueDate(), e.getWaiting(),
                e.getDescription() == null ? "" : e.getDescription(),
                nn(d.steps()), nn(d.subs()), nn(d.files()), nn(d.docs()),
                nn(d.linkTasks()), nn(d.comments()), nn(d.custom()), nn(d.activity()), nn(d.images()),
                e.getCreatedBy(), e.getCompletedAt() == null ? null : e.getCompletedAt().toString(),
                d.groupId(), d.startAt(), d.endAt(), nn(d.pdcaCycles()),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString(), nn(d.hostedPages())).withSubtaskVersion(SubtaskTree.version(d.subs())).withEditVersion(editVersion(e)).withParentTaskId(e.getParentTaskId()).withWorkItemType(d.workItemType() == null ? "task" : d.workItemType()).withSchedule(e.getStartDate(), d.startAt(), d.endAt(), d.schedulePeriod());
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
