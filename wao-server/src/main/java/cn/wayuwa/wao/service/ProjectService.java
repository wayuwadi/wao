package cn.wayuwa.wao.service;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.CreateProjectRequest;
import cn.wayuwa.wao.dto.PatchProjectRequest;
import cn.wayuwa.wao.dto.ProjectDetail;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.mapper.ProjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** 项目读取与创建服务。富嵌套字段在 detail_json 与 DTO 间转换。 */
@Service
public class ProjectService {

    private static final String TODAY = "2026-07-23";

    private final ProjectMapper projectMapper;
    private final ObjectMapper json;

    public ProjectService(ProjectMapper projectMapper, ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.json = json;
    }

    /**
     * 列出全部项目。
     *
     * @return 项目列表
     */
    public List<ProjectDto> list() {
        return projectMapper.selectList(null).stream().map(this::toDto).toList();
    }

    /**
     * 按 id 获取单个项目。
     *
     * @param id 项目 id
     * @return 项目
     * @throws NotFoundException 项目不存在时
     */
    public ProjectDto get(String id) {
        ProjectEntity e = projectMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("项目不存在：" + id);
        }
        return toDto(e);
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
        e.setStartDate(TODAY);
        e.setDueDate("2026-09-30");
        e.setVisibility(req.visAll() ? "组织内 2 人 · 可查看" : "仅自己可见");
        e.setUpdatedLabel("刚刚");
        e.setGoal(req.goal() == null || req.goal().isBlank()
                ? "尚未填写目标——打开项目后可补充。" : req.goal().trim());
        ProjectDetail detail = new ProjectDetail(
                req.visAll() ? List.of("wg", "wd") : List.of(owner),
                List.of(), List.of(), List.of(0, 0), List.of(), List.of(), List.of(),
                List.of(new Activity("ri-add-line", "#9A9DA9", "你创建了项目", "刚刚")),
                "ri-stack-line");
        e.setDetailJson(write(detail));
        projectMapper.insert(e);
        return toDto(e);
    }

    /**
     * 局部更新项目（名称 / 领域 / 目标 / 图标）。空字段不改。
     *
     * @param id  项目 id
     * @param req 局部更新请求
     * @return 更新后的项目
     * @throws NotFoundException 项目不存在时
     */
    public ProjectDto update(String id, PatchProjectRequest req) {
        ProjectEntity e = projectMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("项目不存在：" + id);
        }
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
        }
        if (req.area() != null && !req.area().isBlank()) {
            e.setArea(req.area());
        }
        if (req.goal() != null) {
            e.setGoal(req.goal().isBlank() ? "尚未填写目标——打开项目后可补充。" : req.goal().trim());
        }
        if (req.icon() != null && !req.icon().isBlank()) {
            ProjectDetail d = parse(e.getDetailJson());
            e.setDetailJson(write(new ProjectDetail(d.members(), d.milestones(), d.msDates(), d.trend(),
                    d.blockers(), d.docs(), d.files(), d.activity(), req.icon())));
        }
        e.setUpdatedLabel("刚刚");
        projectMapper.updateById(e);
        return toDto(e);
    }

    /**
     * 删除项目。
     *
     * @param id 项目 id
     * @throws NotFoundException 项目不存在时
     */
    public void delete(String id) {
        if (projectMapper.selectById(id) == null) {
            throw new NotFoundException("项目不存在：" + id);
        }
        projectMapper.deleteById(id);
    }

    private ProjectDto toDto(ProjectEntity e) {
        ProjectDetail d = parse(e.getDetailJson());
        return new ProjectDto(
                e.getId(), e.getName(), e.getArea(), e.getStatus(), e.getHealth(),
                e.getProgress() == null ? 0 : e.getProgress(), e.getOwnerId(),
                nn(d.members()), e.getStartDate(), e.getDueDate(), e.getVisibility(),
                e.getUpdatedLabel(), e.getGoal(),
                nn(d.milestones()), nn(d.msDates()), nn(d.trend()), nn(d.blockers()),
                nn(d.docs()), nn(d.files()), nn(d.activity()),
                d.icon() == null || d.icon().isBlank() ? "ri-stack-line" : d.icon());
    }

    private ProjectDetail parse(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return new ProjectDetail(null, null, null, null, null, null, null, null, null);
        }
        try {
            return json.readValue(detailJson, ProjectDetail.class);
        } catch (JsonProcessingException ex) {
            return new ProjectDetail(null, null, null, null, null, null, null, null, null);
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
