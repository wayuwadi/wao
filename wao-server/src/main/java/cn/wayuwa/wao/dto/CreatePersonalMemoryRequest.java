package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 手工创建文字记忆。成员与采集来源由服务端填写。 */
public record CreatePersonalMemoryRequest(
        @NotBlank(message = "记忆标题不能为空") @Size(max = 120, message = "记忆标题不能超过 120 个字符") String title,
        @NotBlank(message = "记忆正文不能为空") @Size(max = 20_000, message = "记忆正文不能超过 20000 个字符") String content,
        @NotBlank(message = "请选择记忆分类") String category) { }
