package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Skill 市场对外数据契约。 */
public final class SkillDtos {
    private SkillDtos() { }
    public record Category(String key, String name) { }
    public record Publisher(String handle, String displayName) { }
    public record Card(String id, String slug, String displayName, String summary, String category, List<String> tags,
            String status, long downloads, long favorites, LocalDateTime updatedAt, Publisher publisher,
            String version, List<String> compatiblePlatforms) { }
    public record Version(String id, String version, String displayName, String summary, String state, String changelog, String license,
            List<String> testedPlatforms, List<String> compatiblePlatforms, long packageSize, long unpackedSize,
            int fileCount, String sha256, List<Map<String, Object>> files, List<String> findings, boolean scanOverrideConfirmed,
            String skillMd, String reviewNote, LocalDateTime submittedAt, LocalDateTime reviewedAt) { }
    public record Detail(Card skill, Version publishedVersion, List<Version> versions, boolean favorite) { }
    public record FilePreview(String version, String path, long size, boolean previewable, String content, String message) { }
    public record Page(List<Card> items, long total, int page, int pageSize) { }
    public record PublisherProfile(String handle, String displayName) { }
    public record PackageInspection(String name, String description, String suggestedSlug, long unpackedSize,
            int fileCount, String sha256, List<String> findings) { }
    public record MySkill(String id, String slug, String displayName, String summary, String category, String status,
            long downloads, long favorites, List<Version> versions) { }
    public record ReviewItem(MySkill skill, Version version, Publisher publisher) { }
}
