package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.PersonalWorkspaceEntity;
import cn.wayuwa.wao.dto.CreateWorkspaceRequest;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.SaveWorkspaceRequest;
import cn.wayuwa.wao.dto.WorkspaceDetailDto;
import cn.wayuwa.wao.dto.WorkspaceGroupDto;
import cn.wayuwa.wao.dto.WorkspaceLinkDto;
import cn.wayuwa.wao.dto.WorkspaceSummaryDto;
import cn.wayuwa.wao.mapper.PersonalWorkspaceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理成员隔离的项目工作空间及其中的真实导航链接。 */
@Service
public class PersonalWorkspaceService {

    static final int SNAPSHOT_VERSION = 1;
    static final int MAX_GROUPS = 12;
    static final int MAX_LINKS = 240;
    private static final String DEFAULT_ICON = "ri-folder-3-line";
    private static final String DEFAULT_LINK_ICON = "ri-links-line";

    private final PersonalWorkspaceMapper mapper;
    private final ProjectService projects;
    private final ObjectMapper json;

    public PersonalWorkspaceService(PersonalWorkspaceMapper mapper, ProjectService projects, ObjectMapper json) {
        this.mapper = mapper;
        this.projects = projects;
        this.json = json;
    }

    /** 按最近更新时间列出当前成员自己的全部空间。 */
    public List<WorkspaceSummaryDto> list(String memberId) {
        return mapper.listOwned(memberId).stream().map(entity -> summary(entity, memberId)).toList();
    }

    /** 读取当前成员的空间完整快照；其他成员的空间按不存在处理。 */
    public WorkspaceDetailDto get(String id, String memberId) {
        return detail(required(id, memberId), memberId);
    }

    /** 创建一个空工作空间，并放入一个可直接添加链接的默认分组。 */
    @Transactional
    public WorkspaceDetailDto create(CreateWorkspaceRequest request, String memberId) {
        String name = requiredText(request.name(), 60, "空间名称");
        String description = optionalText(request.description(), 400, "空间说明");
        String projectId = normalizeProject(request.projectId(), memberId);
        LocalDateTime now = LocalDateTime.now();
        WorkspaceGroupDto initial = new WorkspaceGroupDto(newId("wg"), "常用", DEFAULT_ICON, 0, List.of());
        Snapshot snapshot = new Snapshot(SNAPSHOT_VERSION, List.of(initial));

        PersonalWorkspaceEntity entity = new PersonalWorkspaceEntity();
        entity.setId(newId("ws"));
        entity.setOwnerMemberId(memberId);
        entity.setProjectId(projectId);
        entity.setName(name);
        entity.setDescriptionText(description);
        entity.setContentJson(write(snapshot));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return detail(entity, memberId);
    }

    /** 原子替换一个空间的名称、项目关联、分组与链接，并返回服务端规整后的快照。 */
    @Transactional
    public WorkspaceDetailDto save(String id, SaveWorkspaceRequest request, String memberId) {
        required(id, memberId);
        String name = requiredText(request.name(), 60, "空间名称");
        String description = optionalText(request.description(), 400, "空间说明");
        String projectId = normalizeProject(request.projectId(), memberId);
        Snapshot snapshot = normalize(request.groups());
        int updated = mapper.updateOwned(id, memberId, projectId, name, description,
                write(snapshot), LocalDateTime.now());
        if (updated == 0) throw notFound();
        return detail(required(id, memberId), memberId);
    }

    /** 删除当前成员的一整个空间；不会访问链接指向的外部系统。 */
    @Transactional
    public void delete(String id, String memberId) {
        if (mapper.deleteOwned(id, memberId) == 0) throw notFound();
    }

    private Snapshot normalize(List<WorkspaceGroupDto> rawGroups) {
        List<WorkspaceGroupDto> groups = rawGroups == null ? List.of() : rawGroups;
        if (groups.size() > MAX_GROUPS) throw new BadRequestException("每个空间最多 12 个分组");
        List<WorkspaceGroupDto> normalized = new ArrayList<>();
        Set<String> groupIds = new HashSet<>();
        Set<String> linkIds = new HashSet<>();
        int linkCount = 0;
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            WorkspaceGroupDto group = groups.get(groupIndex);
            if (group == null) throw new BadRequestException("分组内容不能为空");
            String groupId = stableId(group.id(), "wg", groupIds);
            String groupName = requiredText(group.name(), 40, "分组名称");
            String groupIcon = normalizeIcon(group.icon(), DEFAULT_ICON);
            List<WorkspaceLinkDto> links = group.links() == null ? List.of() : group.links();
            linkCount += links.size();
            if (linkCount > MAX_LINKS) throw new BadRequestException("每个空间最多 240 个链接");
            List<WorkspaceLinkDto> normalizedLinks = new ArrayList<>();
            for (int linkIndex = 0; linkIndex < links.size(); linkIndex++) {
                WorkspaceLinkDto link = links.get(linkIndex);
                if (link == null) throw new BadRequestException("链接内容不能为空");
                normalizedLinks.add(new WorkspaceLinkDto(
                        stableId(link.id(), "wl", linkIds),
                        requiredText(link.title(), 100, "链接标题"),
                        normalizeUrl(link.url()),
                        optionalText(link.description(), 300, "链接备注"),
                        optionalText(link.environment(), 30, "环境标识"),
                        normalizeIcon(link.icon(), DEFAULT_LINK_ICON),
                        linkIndex,
                        link.favorite()));
            }
            normalized.add(new WorkspaceGroupDto(groupId, groupName, groupIcon, groupIndex,
                    List.copyOf(normalizedLinks)));
        }
        return new Snapshot(SNAPSHOT_VERSION, List.copyOf(normalized));
    }

    private PersonalWorkspaceEntity required(String id, String memberId) {
        PersonalWorkspaceEntity entity = mapper.findOwned(id, memberId);
        if (entity == null) throw notFound();
        return entity;
    }

    private WorkspaceDetailDto detail(PersonalWorkspaceEntity entity, String memberId) {
        Snapshot snapshot = read(entity.getContentJson());
        ProjectDto project = project(entity.getProjectId(), memberId);
        return new WorkspaceDetailDto(entity.getId(), entity.getName(), entity.getDescriptionText(),
                project == null ? null : project.id(), project == null ? "" : project.name(),
                snapshot.version(), snapshot.groups(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private WorkspaceSummaryDto summary(PersonalWorkspaceEntity entity, String memberId) {
        Snapshot snapshot = read(entity.getContentJson());
        ProjectDto project = project(entity.getProjectId(), memberId);
        int links = snapshot.groups().stream().mapToInt(group -> group.links().size()).sum();
        return new WorkspaceSummaryDto(entity.getId(), entity.getName(), entity.getDescriptionText(),
                project == null ? null : project.id(), project == null ? "" : project.name(),
                snapshot.groups().size(), links, entity.getUpdatedAt());
    }

    private ProjectDto project(String projectId, String memberId) {
        if (projectId == null || projectId.isBlank()) return null;
        try {
            return projects.get(projectId, memberId);
        } catch (NotFoundException ignored) {
            // 项目被删除或成员失去可见性时，不泄露旧项目信息；下次保存会清掉无效关联。
            return null;
        }
    }

    private String normalizeProject(String raw, String memberId) {
        String projectId = raw == null ? "" : raw.trim();
        if (projectId.isEmpty()) return null;
        projects.get(projectId, memberId);
        return projectId;
    }

    private Snapshot read(String raw) {
        if (raw == null || raw.isBlank()) return new Snapshot(SNAPSHOT_VERSION, List.of());
        try {
            Snapshot parsed = json.readValue(raw, Snapshot.class);
            return normalize(parsed.groups());
        } catch (JsonProcessingException | RuntimeException ex) {
            return new Snapshot(SNAPSHOT_VERSION, List.of());
        }
    }

    private String write(Snapshot snapshot) {
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("工作空间快照无法序列化", ex);
        }
    }

    private String normalizeUrl(String raw) {
        String value = requiredText(raw, 2048, "链接地址");
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("链接地址必须是有效的 http:// 或 https:// 地址，且不能包含账号密码");
        }
    }

    private String stableId(String raw, String prefix, Set<String> used) {
        String candidate = raw == null ? "" : raw.trim();
        if (!candidate.matches("[A-Za-z0-9_-]{1,32}") || used.contains(candidate)) {
            candidate = newId(prefix);
        }
        used.add(candidate);
        return candidate;
    }

    private String normalizeIcon(String raw, String fallback) {
        String icon = raw == null ? "" : raw.trim();
        return icon.matches("ri-[a-z0-9-]{1,60}") ? icon : fallback;
    }

    private String requiredText(String raw, int max, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > max) {
            throw new BadRequestException(field + "须为 1～" + max + " 个字符");
        }
        return value;
    }

    private String optionalText(String raw, int max, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > max) throw new BadRequestException(field + "不能超过 " + max + " 个字符");
        return value;
    }

    private String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
    }

    private NotFoundException notFound() {
        return new NotFoundException("工作空间不存在");
    }

    private record Snapshot(int version, List<WorkspaceGroupDto> groups) {
    }
}
