package cn.wayuwa.wao.dto;

import java.util.List;

/** Skill 市场写入请求。作者身份始终从服务端会话推导。 */
public final class SkillRequests {
    private SkillRequests() { }
    public record PublisherProfile(String handle, String displayName) { }
    public record Draft(String skillId, String slug, String displayName, String summary, String category,
            List<String> tags, String version, String changelog, String license,
            List<String> testedPlatforms, boolean rightsConfirmed) { }
    public record DraftPatch(String displayName, String summary, String category, List<String> tags,
            String changelog, String license, List<String> testedPlatforms, boolean rightsConfirmed) { }
    public record Reject(String note) { }
}
