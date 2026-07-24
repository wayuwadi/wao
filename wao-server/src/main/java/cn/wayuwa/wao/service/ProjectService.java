package cn.wayuwa.wao.service;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.CreateProjectRequest;
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
     * 新建项目（默认负责人 wg、规划中、进度 0）。
     *
     * @param req 新建请求
     * @return 创建后的项目
     */
    public ProjectDto create(CreateProjectRequest req) {
        ProjectEntity e = new ProjectEntity();
        e.setId("p" + System.currentTimeMillis());
        e.setName(req.name().trim());
        e.setArea(req.area() == null || req.area().isBlank() ? "career" : req.area());
        e.setStatus("规划中");
        e.setHealth("良好");
        e.setProgress(0);
        e.setOwnerId("wg");
        e.setStartDate(TODAY);
        e.setDueDate("2026-09-30");
        e.setVisibility(req.visAll() ? "组织内 2 人 · 可查看" : "仅自己可见");
        e.setUpdatedLabel("刚刚");
        e.setGoal(req.goal() == null || req.goal().isBlank()
                ? "尚未填写目标——打开项目后可补充。" : req.goal().trim());
        ProjectDetail detail = new ProjectDetail(
                req.visAll() ? List.of("wg", "wd") : List.of("wg"),
                List.of(), List.of(), List.of(0, 0), List.of(), List.of(), List.of(),
                List.of(new Activity("ri-add-line", "#9A9DA9", "你创建了项目", "刚刚")));
        e.setDetailJson(write(detail));
        projectMapper.insert(e);
        return toDto(e);
    }

    private ProjectDto toDto(ProjectEntity e) {
        ProjectDetail d = parse(e.getDetailJson());
        return new ProjectDto(
                e.getId(), e.getName(), e.getArea(), e.getStatus(), e.getHealth(),
                e.getProgress() == null ? 0 : e.getProgress(), e.getOwnerId(),
                nn(d.members()), e.getStartDate(), e.getDueDate(), e.getVisibility(),
                e.getUpdatedLabel(), e.getGoal(),
                nn(d.milestones()), nn(d.msDates()), nn(d.trend()), nn(d.blockers()),
                nn(d.docs()), nn(d.files()), nn(d.activity()));
    }

    private ProjectDetail parse(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return new ProjectDetail(null, null, null, null, null, null, null, null);
        }
        try {
            return json.readValue(detailJson, ProjectDetail.class);
        } catch (JsonProcessingException ex) {
            return new ProjectDetail(null, null, null, null, null, null, null, null);
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
