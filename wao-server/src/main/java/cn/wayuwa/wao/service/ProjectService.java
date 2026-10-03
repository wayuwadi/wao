package cn.wayuwa.wao.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.CreateProjectRequest;
import cn.wayuwa.wao.dto.PatchProjectRequest;
import cn.wayuwa.wao.dto.ProjectDetail;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.TaskGroupDto;
import cn.wayuwa.wao.mapper.ProjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 项目读取与创建服务。富嵌套字段在 detail_json 与 DTO 间转换。 */
@Service
public class ProjectService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProjectService.class);
    private static final Set<String> PROJECT_STATUSES = Set.of("规划中", "进行中", "有风险", "已完成", "已搁置");
    /** 一个项目最多能建的任务分组数。 */
    /** 分组名长度上限，防止一行名字把分组头撑破。 */
    private static final int MAX_GROUP_NAME = 20;

    private final ProjectMapper projectMapper;
    private final ObjectMapper json;
    private final FeishuTaskNotifier taskNotifier;
    private final MemberService members;
    private final ProjectActivityService activities;

    public ProjectService(ProjectMapper projectMapper, ObjectMapper json,
            FeishuTaskNotifier taskNotifier, MemberService members, ProjectActivityService activities) {
        this.projectMapper = projectMapper;
        this.json = json;
        this.taskNotifier = taskNotifier;
        this.members = members;
        this.activities = activities;
    }

    /**
     * 列出对给定成员可见的项目：自己负责的，或成员列表里包含自己的。
     *
     * <p>此前不做过滤，娃哥能看到娃弟的个人项目（反之亦然）。个人项目在创建时
     * members 只有负责人自己，据此隔离。
     *
     * @param memberId 当前登录成员 id；为空时不过滤（内部调用兼容）
     * @return 可见项目列表
     */
    public List<ProjectDto> list(String memberId) {
        List<ProjectEntity> visible = projectMapper.selectList(null).stream()
                .filter(e -> visibleTo(e, memberId)).toList();
        var acts = activities.forProjects(visible);
        return visible.stream().map(e -> toDto(e, acts.get(e.getId()))).toList();
    }

    /**
     * 一次性读取当前成员可见的项目 id，供其它列表接口批量完成权限过滤。
     *
     * <p>这里只解析项目自身的负责人和成员字段，不加载项目动态活动；任务列表据此避免按任务逐条
     * 调用 {@link #get(String, String)} 形成 N+1 查询。
     *
     * @param memberId 当前登录成员 id；为空时返回全部项目 id
     * @return 可见项目 id 集合
     */
    public Set<String> visibleProjectIds(String memberId) {
        return projectMapper.selectList(new QueryWrapper<ProjectEntity>()
                        .select("id", "owner_id", "detail_json"))
                .stream()
                .filter(project -> visibleTo(project, memberId))
                .map(ProjectEntity::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 按 id 获取单个项目（内部调用，不做可见性校验）。
     *
     * @param id 项目 id
     * @return 项目
     * @throws NotFoundException 项目不存在时
     */
    public ProjectDto get(String id) {
        return toDto(require(id));
    }

    /**
     * 按 id 获取单个项目，并校验对给定成员可见。
     *
     * @param id       项目 id
     * @param memberId 当前登录成员 id
     * @return 项目
     * @throws NotFoundException 项目不存在或对该成员不可见时（不泄露存在性）
     */
    public ProjectDto get(String id, String memberId) {
        return toDto(requireVisible(id, memberId));
    }

    /** 只校验项目可见性，不加载任务活动等详情，供范围授权等热路径使用。 */
    public void requireAccess(String id, String memberId) {
        requireVisible(id, memberId);
    }

    /**
     * 新建项目，并将负责人设为当前登录成员。
     *
     * @param req 新建请求
     * @param ownerId 当前登录成员 id；本地未启用认证时使用兼容默认值
     * @return 创建后的项目
     */
    public ProjectDto create(CreateProjectRequest req, String ownerId) {
        ProjectEntity e = new ProjectEntity();
        e.setId("p" + System.currentTimeMillis());
        e.setName(req.name().trim());
        e.setArea(req.area() == null || req.area().isBlank() ? "career" : req.area());
        e.setStatus("规划中");
        e.setHealth("良好");
        e.setProgress(0);
        String owner = ownerId == null || ownerId.isBlank() ? "wg" : ownerId;
        e.setOwnerId(owner);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        e.setStartDate(today.toString());
        e.setDueDate(normalizeDue(req.due()));
        e.setVisibility(req.visAll() ? "组织内 2 人 · 可查看" : "仅自己可见");
        e.setUpdatedLabel("刚刚");
        e.setGoal(req.goal() == null || req.goal().isBlank()
                ? "尚未填写目标——打开项目后可补充。" : req.goal().trim());
        // 新项目默认开着通知：刚建完的项目正是最需要知道进展的时候，嫌吵可以随时在设置里关掉。
        e.setNotifyEnabled(true);
        ProjectDetail detail = new ProjectDetail(
                req.visAll() ? List.of("wg", "wd") : List.of(owner),
                List.of(), List.of(), List.of(0, 0), List.of(), List.of(), List.of(),
                // 主语与时刻都别写死：谁看都是「你创建了项目 / 刚刚」正是前端反馈的那个 bug
                List.of(new Activity("ri-add-line", "#9A9DA9", "创建了项目",
                        OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(), owner)),
                "ri-stack-line", List.of());
        e.setDetailJson(write(detail));
        projectMapper.insert(e);
        if (!Boolean.FALSE.equals(req.announce())) {
            announceCreated(e, owner);
        }
        return toDto(e);
    }

    /**
     * 首次明确开始推进项目：状态进入进行中并且只通知一次；重复调用只返回当前项目。
     *
     * @param id 项目 id
     * @param memberId 当前成员
     * @return 开始推进后的项目
     */
    @Transactional
    public ProjectDto start(String id, String memberId) {
        ProjectEntity e = projectMapper.selectByIdForUpdate(id);
        if (e == null || !visibleTo(e, memberId)) {
            throw new NotFoundException("项目不存在：" + id);
        }
        ProjectDetail d = parse(e.getDetailJson());
        if (StringUtils.hasText(d.startedAt()) || !"规划中".equals(e.getStatus())) {
            return toDto(e);
        }
        String now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
        e.setStatus("进行中");
        e.setUpdatedLabel("刚刚");
        e.setDetailJson(write(new ProjectDetail(d.members(), d.milestones(), d.msDates(), d.trend(),
                d.blockers(), d.docs(), d.files(), d.activity(), d.icon(), d.taskGroups(), now)));
        projectMapper.updateById(e);
        announceStarted(e, memberId);
        return toDto(e);
    }

    /** 新建项目后同步到飞书群；通知失败只记日志，不影响创建结果。 */
    private void announceCreated(ProjectEntity entity, String ownerId) {
        try {
            String ownerName = members.list().stream().filter(m -> m.id().equals(ownerId))
                    .map(m -> m.name()).findFirst().orElse(ownerId);
            taskNotifier.notifyProjectCreated(entity.getId(), entity.getName(), ownerName, entity.getGoal());
        } catch (RuntimeException ex) {
            LOG.warn("新建项目的飞书通知发送失败：{}", ex.getMessage());
        }
    }

    /** 首次开始推进时同步团队；通知失败不回滚项目状态。 */
    private void announceStarted(ProjectEntity entity, String memberId) {
        try {
            String memberName = members.list().stream().filter(m -> m.id().equals(memberId))
                    .map(m -> m.name()).findFirst().orElse(memberId);
            taskNotifier.notifyProjectStarted(entity.getId(), entity.getName(), memberName, entity.getGoal());
        } catch (RuntimeException ex) {
            LOG.warn("项目开始推进通知发送失败：{}", ex.getMessage());
        }
    }

    /**
     * 局部更新项目（含截止日期）。null 字段不改；截止日期空串表示清空。
     *
     * @param id       项目 id
     * @param req      局部更新请求
     * @param memberId 当前登录成员 id，用于可见性校验
     * @return 更新后的项目
     * @throws NotFoundException 项目不存在或对该成员不可见时
     */
    public ProjectDto update(String id, PatchProjectRequest req, String memberId) {
        ProjectEntity e = requireVisible(id, memberId);
        if (req.due() != null) e.setDueDate(normalizeDue(req.due()));
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
        }
        if (req.area() != null && !req.area().isBlank()) {
            e.setArea(req.area());
        }
        if (req.status() != null) {
            if (!PROJECT_STATUSES.contains(req.status())) {
                throw new BadRequestException("不支持的项目状态：" + req.status());
            }
            e.setStatus(req.status());
        }
        if (req.goal() != null) {
            e.setGoal(req.goal().isBlank() ? "尚未填写目标——打开项目后可补充。" : req.goal().trim());
        }
        if (req.notifyEnabled() != null) {
            e.setNotifyEnabled(req.notifyEnabled());
        }
        if ((req.icon() != null && !req.icon().isBlank()) || req.taskGroups() != null) {
            ProjectDetail d = parse(e.getDetailJson());
            String icon = req.icon() != null && !req.icon().isBlank() ? req.icon() : d.icon();
            List<TaskGroupDto> groups = req.taskGroups() == null
                    ? d.taskGroups() : normalizeGroups(req.taskGroups());
            e.setDetailJson(write(new ProjectDetail(d.members(), d.milestones(), d.msDates(), d.trend(),
                    d.blockers(), d.docs(), d.files(), d.activity(), icon, groups, d.startedAt())));
        }
        e.setUpdatedLabel("刚刚");
        projectMapper.updateById(e);
        return toDto(e);
    }

    private String normalizeDue(String due) {
        if (due == null || due.isBlank()) return "";
        try {
            if (!due.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            return LocalDate.parse(due).toString();
        } catch (RuntimeException ex) {
            throw new BadRequestException("截止日期须为有效的 yyyy-MM-dd 日期，清空请传空串");
        }
    }

    /**
     * 删除项目。
     *
     * @param id       项目 id
     * @param memberId 当前登录成员 id，用于可见性校验
     * @throws NotFoundException 项目不存在或对该成员不可见时
     */
    public void delete(String id, String memberId) {
        requireVisible(id, memberId);
        projectMapper.deleteById(id);
    }

    /**
     * 规整前端送来的任务分组：去掉空名、补齐 id、按 id 去重，保留全部有效分组。
     *
     * @param groups 前端送来的完整分组列表
     * @return 规整后的分组列表
     */
    private static List<TaskGroupDto> normalizeGroups(List<TaskGroupDto> groups) {
        List<TaskGroupDto> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TaskGroupDto g : groups) {
            if (g == null || g.name() == null || g.name().isBlank()) {
                continue;
            }
            String id = g.id() == null || g.id().isBlank() ? "tg" + System.nanoTime() : g.id().trim();
            if (!seen.add(id)) {
                continue;
            }
            String name = g.name().trim();
            out.add(new TaskGroupDto(id, name.length() > MAX_GROUP_NAME ? name.substring(0, MAX_GROUP_NAME) : name));
        }
        return out;
    }

    private ProjectEntity require(String id) {
        ProjectEntity e = projectMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("项目不存在：" + id);
        }
        return e;
    }

    /** 不可见与不存在同样报 404，避免向未授权成员泄露项目存在性。 */
    private ProjectEntity requireVisible(String id, String memberId) {
        ProjectEntity e = require(id);
        if (!visibleTo(e, memberId)) {
            throw new NotFoundException("项目不存在：" + id);
        }
        return e;
    }

    private boolean visibleTo(ProjectEntity e, String memberId) {
        if (memberId == null || memberId.isBlank()) {
            return true;
        }
        if (memberId.equals(e.getOwnerId())) {
            return true;
        }
        return nn(parse(e.getDetailJson()).members()).contains(memberId);
    }

    private ProjectDto toDto(ProjectEntity e) {
        return toDto(e, activities.forProject(e));
    }

    private ProjectDto toDto(ProjectEntity e, List<Activity> liveActivity) {
        ProjectDetail d = parse(e.getDetailJson());
        // 现算的动态优先；一条真实事件都凑不出来时（如种子数据 id 无时间戳）回落存量文案
        List<Activity> activity = liveActivity == null || liveActivity.isEmpty()
                ? nn(d.activity()) : liveActivity;
        return new ProjectDto(
                e.getId(), e.getName(), e.getArea(), e.getStatus(), e.getHealth(),
                e.getProgress() == null ? 0 : e.getProgress(), e.getOwnerId(),
                nn(d.members()), e.getStartDate(), e.getDueDate(), e.getVisibility(),
                e.getUpdatedLabel(), e.getGoal(),
                nn(d.milestones()), nn(d.msDates()), nn(d.trend()), nn(d.blockers()),
                nn(d.docs()), nn(d.files()), activity,
                d.icon() == null || d.icon().isBlank() ? "ri-stack-line" : d.icon(),
                !Boolean.FALSE.equals(e.getNotifyEnabled()), nn(d.taskGroups()));
    }

    private ProjectDetail parse(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return new ProjectDetail(null, null, null, null, null, null, null, null, null, null, null);
        }
        try {
            return json.readValue(detailJson, ProjectDetail.class);
        } catch (JsonProcessingException ex) {
            return new ProjectDetail(null, null, null, null, null, null, null, null, null, null, null);
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
