package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新建草图；空标题会在服务端落为「未命名草图」。 */
public record CreateDrawingFileRequest(
        @Size(max = 128, message = "草图名称不能超过 128 个字符") String title,
        @Size(max = 32) String groupId,
        @Size(max = 1_000_000) String sceneJson,
        @Pattern(regexp = "[a-f0-9]{32}") String requestId) {
    public CreateDrawingFileRequest(String title) { this(title, null); }
    public CreateDrawingFileRequest(String title, String groupId) { this(title, groupId, null, null); }
}
