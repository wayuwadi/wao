package cn.wayuwa.wao.dto;

/** Runner 对一项固化 Skill 的实际装配结果。 */
public record AiSkillAuditDto(
        String skillId, String versionId, String displayName, String version,
        String status, String message) { }
