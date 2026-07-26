package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户给已阻塞 AI 会话的补充输入。 */
public record ContinueAiRunRequest(@NotBlank @Size(max = 4000) String input) { }
