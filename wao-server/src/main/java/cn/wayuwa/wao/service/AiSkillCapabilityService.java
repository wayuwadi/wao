package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.ProjectSkillBindingEntity;
import cn.wayuwa.wao.domain.SkillEntity;
import cn.wayuwa.wao.domain.SkillVersionEntity;
import cn.wayuwa.wao.domain.WaoApplicationSkillBindingEntity;
import cn.wayuwa.wao.dto.AiSkillAuditDto;
import cn.wayuwa.wao.dto.AiSkillCapabilityDto;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.ProjectSkillBindingMapper;
import cn.wayuwa.wao.mapper.SkillMapper;
import cn.wayuwa.wao.mapper.SkillVersionMapper;
import cn.wayuwa.wao.mapper.WaoApplicationSkillBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** WAO 应用与项目 Skill 固定版本绑定、运行快照和包读取服务。 */
@Service
public class AiSkillCapabilityService {
    private static final String WAO_APPLICATION = "wao";
    private static final String WAO_APPLICATION_SCOPE = "WAO_APPLICATION";
    private static final String PROJECT_SCOPE = "PROJECT";
    private final ProjectSkillBindingMapper bindings; private final ProjectMapper projects;
    private final WaoApplicationSkillBindingMapper applicationBindings; private final MemberMapper members;
    private final SkillMapper skills; private final SkillVersionMapper versions; private final ObjectMapper json;

    public AiSkillCapabilityService(ProjectSkillBindingMapper bindings, ProjectMapper projects,
            WaoApplicationSkillBindingMapper applicationBindings, MemberMapper members,
            SkillMapper skills, SkillVersionMapper versions, ObjectMapper json) {
        this.bindings=bindings; this.projects=projects; this.skills=skills; this.versions=versions; this.json=json;
        this.applicationBindings=applicationBindings; this.members=members;
    }

    /** 返回市场当前可启用的审核版本。 */
    public List<AiSkillCapabilityDto> available() {
        return skills.market("", "", "codex", "newest", 60, 0).stream().map(row ->
                new AiSkillCapabilityDto(row.getId(), row.getVersionId(), row.getDisplayName(), row.getSummary(),
                        row.getVersion(), row.getSha256(), readStrings(row.getCompatiblePlatformsJson()))).toList();
    }

    /** 返回项目当前绑定，版本下架后仍保留可见以便管理员主动调整。 */
    public List<AiSkillCapabilityDto> projectSkills(String projectId) {
        requireProject(projectId);
        return bindings.selectList(new LambdaQueryWrapper<ProjectSkillBindingEntity>()
                .eq(ProjectSkillBindingEntity::getProjectId, projectId)
                .orderByAsc(ProjectSkillBindingEntity::getCreatedAt)).stream()
                .map(binding -> capability(binding.getSkillId(), binding.getSkillVersionId(), PROJECT_SCOPE)).toList();
    }

    /** 返回 WAO 应用默认启用的固定审核版本。 */
    public List<AiSkillCapabilityDto> waoApplicationSkills() {
        return applicationBindings.selectList(new LambdaQueryWrapper<WaoApplicationSkillBindingEntity>()
                .eq(WaoApplicationSkillBindingEntity::getApplicationId, WAO_APPLICATION)
                .orderByAsc(WaoApplicationSkillBindingEntity::getCreatedAt)).stream()
                .map(binding -> capability(binding.getSkillId(), binding.getSkillVersionId(), WAO_APPLICATION_SCOPE)).toList();
    }

    /** 整体替换项目绑定；只接受当前公开的审核版本，最多十项。 */
    @Transactional
    public List<AiSkillCapabilityDto> saveProjectSkills(String projectId, List<String> rawVersionIds, String memberId) {
        requireProject(projectId);
        var existingBindings = bindings.selectList(new LambdaQueryWrapper<ProjectSkillBindingEntity>()
                .eq(ProjectSkillBindingEntity::getProjectId, projectId));
        var retainedVersionIds = existingBindings.stream().map(ProjectSkillBindingEntity::getSkillVersionId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> versionIds = new ArrayList<>(new LinkedHashSet<>(rawVersionIds == null ? List.of() : rawVersionIds));
        if (versionIds.size() > 10) throw new BadRequestException("一个项目最多启用 10 个 Skill");
        List<AiSkillCapabilityDto> selected = versionIds.stream().map(versionId -> {
            SkillVersionEntity version = versions.findMeta(versionId);
            if (version == null || !"PUBLISHED".equals(version.getState())) throw new BadRequestException("Skill 版本不存在或尚未通过审核：" + versionId);
            SkillEntity skill = skills.selectById(version.getSkillId());
            if (skill == null || ((!"PUBLISHED".equals(skill.getStatus())
                    || !versionId.equals(skill.getPublishedVersionId())) && !retainedVersionIds.contains(versionId))) {
                throw new BadRequestException("只能新启用市场当前公开版本：" + versionId);
            }
            return capability(skill.getId(), versionId, PROJECT_SCOPE);
        }).toList();
        if (selected.stream().map(AiSkillCapabilityDto::skillId).distinct().count() != selected.size()) {
            throw new BadRequestException("同一个 Skill 只能绑定一个固定版本");
        }
        bindings.deleteByProject(projectId);
        LocalDateTime now = LocalDateTime.now();
        for (AiSkillCapabilityDto item : selected) {
            ProjectSkillBindingEntity binding = new ProjectSkillBindingEntity();
            binding.setId("psb-" + UUID.randomUUID()); binding.setProjectId(projectId); binding.setSkillId(item.skillId());
            binding.setSkillVersionId(item.versionId()); binding.setCreatedBy(memberId); binding.setCreatedAt(now); binding.setUpdatedAt(now);
            bindings.insert(binding);
        }
        return selected;
    }

    /** 管理员整体替换 WAO 应用默认 Skill；只接受当前公开的审核版本，最多十项。 */
    @Transactional
    public List<AiSkillCapabilityDto> saveWaoApplicationSkills(List<String> rawVersionIds, String memberId) {
        requireAdmin(memberId);
        var existingBindings = applicationBindings.selectList(new LambdaQueryWrapper<WaoApplicationSkillBindingEntity>()
                .eq(WaoApplicationSkillBindingEntity::getApplicationId, WAO_APPLICATION));
        Set<String> retainedVersionIds = existingBindings.stream()
                .map(WaoApplicationSkillBindingEntity::getSkillVersionId).collect(java.util.stream.Collectors.toSet());
        List<String> versionIds = new ArrayList<>(new LinkedHashSet<>(rawVersionIds == null ? List.of() : rawVersionIds));
        if (versionIds.size() > 10) throw new BadRequestException("WAO 应用最多默认启用 10 个 Skill");
        List<AiSkillCapabilityDto> selected = versionIds.stream().map(versionId -> {
            SkillVersionEntity version = versions.findMeta(versionId);
            if (version == null || !"PUBLISHED".equals(version.getState())) {
                throw new BadRequestException("Skill 版本不存在或尚未通过审核：" + versionId);
            }
            SkillEntity skill = skills.selectById(version.getSkillId());
            if (skill == null || ((!"PUBLISHED".equals(skill.getStatus())
                    || !versionId.equals(skill.getPublishedVersionId())) && !retainedVersionIds.contains(versionId))) {
                throw new BadRequestException("只能新启用市场当前公开版本：" + versionId);
            }
            return capability(skill.getId(), versionId, WAO_APPLICATION_SCOPE);
        }).toList();
        if (selected.stream().map(AiSkillCapabilityDto::skillId).distinct().count() != selected.size()) {
            throw new BadRequestException("同一个 Skill 只能绑定一个固定版本");
        }
        applicationBindings.deleteByApplication(WAO_APPLICATION);
        LocalDateTime now = LocalDateTime.now();
        for (AiSkillCapabilityDto item : selected) {
            WaoApplicationSkillBindingEntity binding = new WaoApplicationSkillBindingEntity();
            binding.setId("wasb-" + UUID.randomUUID()); binding.setApplicationId(WAO_APPLICATION);
            binding.setSkillId(item.skillId()); binding.setSkillVersionId(item.versionId()); binding.setCreatedBy(memberId);
            binding.setCreatedAt(now); binding.setUpdatedAt(now); applicationBindings.insert(binding);
        }
        return selected;
    }

    /** 入队时合并 WAO 默认与项目固定版本；同一 Skill 的项目版本优先。 */
    public String snapshot(String projectId) {
        LinkedHashMap<String, AiSkillCapabilityDto> effective = new LinkedHashMap<>();
        waoApplicationSkills().forEach(item -> effective.put(item.skillId(), item));
        projectSkills(projectId).forEach(item -> effective.put(item.skillId(), item));
        try { return json.writeValueAsString(effective.values()); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法固化 Skill 能力清单", ex); }
    }

    /** 读取历史运行快照；旧运行或异常快照安全回落为空。 */
    public List<AiSkillCapabilityDto> manifest(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, new TypeReference<List<AiSkillCapabilityDto>>(){}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("AI Run 的 Skill 能力清单损坏", ex); }
    }

    /** 读取审核时保存的不可变包，调用方必须先校验该版本属于运行快照。 */
    public SkillPackage packageContent(String versionId) {
        SkillVersionEntity version = versions.selectById(versionId);
        if (version == null || version.getPackageContent() == null) throw new NotFoundException("Skill 包不存在");
        return new SkillPackage(version.getPackageName(), version.getSha256(), version.getPackageContent());
    }

    /** 规范 Runner 回传，确保不能伪造清单之外的 Skill 审计。 */
    public List<AiSkillAuditDto> normalizeAudit(String manifestJson, List<AiSkillAuditDto> audit) {
        List<AiSkillCapabilityDto> manifest = manifest(manifestJson);
        if (manifest.isEmpty()) return List.of();
        List<AiSkillAuditDto> raw = audit == null ? List.of() : audit;
        return manifest.stream().map(item -> raw.stream().filter(entry -> item.versionId().equals(entry.versionId())).findFirst()
                .map(entry -> new AiSkillAuditDto(item.skillId(), item.versionId(), item.displayName(), item.version(),
                        allowedStatus(entry.status()), limit(entry.message(), 500)))
                .orElseGet(() -> new AiSkillAuditDto(item.skillId(), item.versionId(), item.displayName(), item.version(),
                        "not_reported", "执行节点未回传装配结果"))).toList();
    }

    public String writeAudit(List<AiSkillAuditDto> audit) {
        try { return json.writeValueAsString(audit); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法保存 Skill 装配审计", ex); }
    }

    public List<AiSkillAuditDto> readAudit(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, new TypeReference<List<AiSkillAuditDto>>(){}); }
        catch (JsonProcessingException ex) { return List.of(); }
    }

    private AiSkillCapabilityDto capability(String skillId, String versionId, String activationScope) {
        SkillEntity skill = skills.selectById(skillId); SkillVersionEntity version = versions.findMeta(versionId);
        if (skill == null || version == null) throw new NotFoundException("绑定的 Skill 版本已不存在");
        return new AiSkillCapabilityDto(skillId, versionId, version.getDisplayName(), version.getSummary(),
                version.getVersion(), version.getSha256(), readStrings(version.getCompatiblePlatformsJson()), activationScope);
    }
    private void requireProject(String id){if(projects.selectById(id)==null)throw new NotFoundException("项目不存在："+id);}
    private void requireAdmin(String memberId){MemberEntity member=members.selectById(memberId);if(member==null||!Set.of("管理员","超级管理员").contains(member.getRole()))throw new UnauthorizedException("只有管理员可以设置 WAO 默认 Skill");}
    private List<String> readStrings(String value){try{return value==null?List.of():json.readValue(value,new TypeReference<List<String>>(){});}catch(JsonProcessingException ex){return List.of();}}
    private String allowedStatus(String value){return List.of("loaded","failed","incompatible").contains(value)?value:"failed";}
    private String limit(String value,int max){if(value==null)return null;String v=value.strip();return v.length()<=max?v:v.substring(0,max);}
    public record SkillPackage(String filename, String sha256, byte[] content) { }
}
