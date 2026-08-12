package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Size;

/** 新建草图；空标题会在服务端落为「未命名草图」。 */
public record CreateDrawingFileRequest(
        @Size(max = 128, message = "草图名称不能超过 128 个字符") String title) {
}
