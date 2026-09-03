package cn.wayuwa.wao.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.KbMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 项目动态聚合：从项目下真实发生过的事情（建项目、建任务、任务完成、写文档）
 * 现算一条时间线，替代 detail_json 里创建时写死的那条「你创建了项目 / 刚刚」。
 *
 * <p>历史数据没有专门的活动表，但每类事件都有可靠的时间来源：实体 id 里的毫秒
 * 时间戳（{@code p<millis>} / {@code t<millis>}）、任务的 {@code completed_at}、
 * 文档的 {@code created_at}。解析不出时间的事件直接跳过，绝不编造时间。
 */
@Service
public class ProjectActivityService {

    /** 单个项目最多展示的动态条数，避免老项目把时间线拖成长卷。 */
    private static final int LIMIT = 50;
    private static final Pattern ID_MILLIS = Pattern.compile("^[a-z]+(\\d{13})$");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String GREY = "#9A9DA9";
    private static final String GREEN = "#2E7D4F";

    private final TaskMapper taskMapper;
    private final KbMapper kbMapper;
    private final DocMapper docMapper;
    private final MemberService members;

    public ProjectActivityService(TaskMapper taskMapper, KbMapper kbMapper, DocMapper docMapper,
            MemberService members) {
        this.taskMapper = taskMapper;
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.members = members;
    }

    /**
     * 计算单个项目的动态时间线（倒序，最多 {@value LIMIT} 条）。
     *
     * @param project 项目实体
     * @return 动态列表；没有任何可考事件时为空列表（调用方自行兜底）
     */
    public List<Activity> forProject(ProjectEntity project) {
        return forProjects(List.of(project)).getOrDefault(project.getId(), List.of());
    }

    /**
     * 批量计算一组项目的动态时间线，任务 / 知识库 / 文档各查一次库。
     *
     * @param projects 项目实体列表
     * @return 项目 id → 动态列表（倒序）
     */
    public Map<String, List<Activity>> forProjects(Collection<ProjectEntity> projects) {
        if (projects.isEmpty()) {
            return Map.of();
        }
        List<String> ids = projects.stream().map(ProjectEntity::getId).toList();
        Map<String, String> names = members.list().stream()
                .collect(Collectors.toMap(MemberDto::id, MemberDto::name, (a, b) -> a));

        Map<String, List<TaskEntity>> tasksByProject = taskMapper.selectList(
                new QueryWrapper<TaskEntity>()
                        .select("id", "project_id", "title", "owner_id", "created_by", "completed_at")
                        .in("project_id", ids))
                .stream().collect(Collectors.groupingBy(TaskEntity::getProjectId));
        List<KbEntity> kbs = kbMapper.selectList(new QueryWrapper<KbEntity>()
                .select("id", "project_id")
                .in("project_id", ids).isNull("deleted_at"));
        Map<String, String> kbToProject = kbs.stream()
                .collect(Collectors.toMap(KbEntity::getId, KbEntity::getProjectId, (a, b) -> a));
        Map<String, List<DocEntity>> docsByProject = kbs.isEmpty() ? Map.of()
                : docMapper.selectList(new QueryWrapper<DocEntity>()
                        .select("id", "kb_id", "title", "author_id", "created_at")
                        .in("kb_id", kbToProject.keySet()).isNull("deleted_at").isNull("archived_at"))
                        .stream().collect(Collectors.groupingBy(d -> kbToProject.get(d.getKbId())));

        Map<String, List<Activity>> out = new HashMap<>();
        for (ProjectEntity p : projects) {
            out.put(p.getId(), assemble(p,
                    tasksByProject.getOrDefault(p.getId(), List.of()),
                    docsByProject.getOrDefault(p.getId(), List.of()),
                    names));
        }
        return out;
    }

    private List<Activity> assemble(ProjectEntity project, List<TaskEntity> tasks,
            List<DocEntity> docs, Map<String, String> names) {
        Function<String, String> name = id -> {
            if (id == null || id.isBlank()) {
                return null;
            }
            if ("auto".equals(id)) {
                return "自动化";
            }
            return names.getOrDefault(id, id);
        };
        List<Event> events = new ArrayList<>();

        Long createdMs = millisOf(project.getId());
        if (createdMs != null) {
            String owner = name.apply(project.getOwnerId());
            events.add(new Event(createdMs, new Activity("ri-add-line", GREY,
                    (owner == null ? "" : owner + " ") + "创建了项目", iso(createdMs))));
        }
        for (TaskEntity t : tasks) {
            Long ms = millisOf(t.getId());
            if (ms != null) {
                String by = name.apply(t.getCreatedBy() == null ? t.getOwnerId() : t.getCreatedBy());
                events.add(new Event(ms, new Activity("ri-checkbox-circle-line", GREY,
                        (by == null ? "" : by + " ") + "创建了任务「" + t.getTitle() + "」", iso(ms))));
            }
            if (t.getCompletedAt() != null) {
                long done = toMillis(t.getCompletedAt());
                events.add(new Event(done, new Activity("ri-check-double-line", GREEN,
                        "任务「" + t.getTitle() + "」已完成", iso(done))));
            }
        }
        for (DocEntity d : docs) {
            Long ms = d.getCreatedAt() != null ? toMillis(d.getCreatedAt()) : millisOf(d.getId());
            if (ms != null) {
                String by = name.apply(d.getAuthorId());
                String title = d.getTitle() == null || d.getTitle().isBlank() ? "未命名文档" : d.getTitle();
                events.add(new Event(ms, new Activity("ri-file-text-line", GREY,
                        (by == null ? "" : by + " ") + "创建了文档「" + title + "」", iso(ms))));
            }
        }
        return events.stream()
                .sorted(Comparator.comparingLong(Event::at).reversed())
                .limit(LIMIT)
                .map(Event::activity)
                .toList();
    }

    private static Long millisOf(String id) {
        if (id == null) {
            return null;
        }
        Matcher m = ID_MILLIS.matcher(id);
        return m.matches() ? Long.parseLong(m.group(1)) : null;
    }

    private static long toMillis(LocalDateTime at) {
        return at.atZone(SHANGHAI).toInstant().toEpochMilli();
    }

    private static String iso(long millis) {
        return Instant.ofEpochMilli(millis).atZone(SHANGHAI)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private record Event(long at, Activity activity) {
    }
}
