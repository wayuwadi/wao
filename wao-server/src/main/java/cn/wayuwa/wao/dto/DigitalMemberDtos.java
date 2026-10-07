package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;
import jakarta.validation.constraints.Size;

/** 私有数字成员图库契约；身份由当前服务端会话确定。 */
public final class DigitalMemberDtos {
    private DigitalMemberDtos() { }
    public record Profile(String gender, long revision) { }
    public record Binding(String subjectId, String versionId, long revision) { }
    public record Capability(boolean referenceImage, String message) { }
    public record Library(List<Subject> subjects, Binding binding, Profile profile, Capability capability) { }
    public record Asset(String id, String name, String url, int width, int height, String role, LocalDateTime createdAt) { }
    public record Version(String id, int number, String state, List<String> assetIds, String portraitAssetId, LocalDateTime createdAt) { }
    public record Look(String id, String name, String style, long revision, List<Version> versions) { }
    public record Use(String runId, String versionId, String mode, String purpose, String status, LocalDateTime createdAt) { }
    public record Subject(String id, String name, String kind, String relation, String gender, String traits,
            String mainAssetId, long revision, String state, List<Asset> assets, List<Look> looks, List<Use> history) { }
    public record Create(String name, String kind, String relation, String gender, String traits) { }
    public record Update(String name, String relation, String gender, String traits, String state,
            String mainAssetId, Long expectedRevision) { }
    public record ProfileUpdate(String gender, Long expectedRevision) { }
    public record Bind(String subjectId, String versionId, Long expectedRevision) { }
    public record CreateLook(String name, String style, List<String> assetIds, String portraitAssetId) { }
    public record CreateVersion(List<String> assetIds, String portraitAssetId, Long expectedRevision) { }
    public record Generate(String name, String style, List<String> assetIds, String requestId) { }
    public record Generation(String runId, String status, String message, String lookId, String versionId) { }
    public record IdentitySelection(@Size(max=12) String mode, @Size(max=32) String subjectId, @Size(max=32) String versionId) { }
}
