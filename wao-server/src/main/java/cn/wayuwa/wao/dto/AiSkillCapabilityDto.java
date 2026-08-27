package cn.wayuwa.wao.dto;

import java.util.List;

/** 单次 AI Run 固化的一项审核 Skill 能力。 */
public record AiSkillCapabilityDto(
        String skillId, String versionId, String displayName, String summary, String version,
        String sha256, List<String> compatiblePlatforms, String activationScope) {
    /** 兼容旧运行快照和仅用于市场选择的调用方。 */
    public AiSkillCapabilityDto(String skillId, String versionId, String displayName, String summary, String version,
            String sha256, List<String> compatiblePlatforms) {
        this(skillId, versionId, displayName, summary, version, sha256, compatiblePlatforms, null);
    }
}
