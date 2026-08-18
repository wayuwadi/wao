package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改个人记忆的可编辑元数据；原始附件不可被静默替换。 */
public record UpdatePersonalMemoryRequest(
        @NotBlank(message = "记忆标题不能为空") @Size(max = 120, message = "记忆标题不能超过 120 个字符") String title,
        @Size(max = 20_000, message = "记忆正文不能超过 20000 个字符") String content,
        @NotBlank(message = "请选择记忆分类") String category) { }
