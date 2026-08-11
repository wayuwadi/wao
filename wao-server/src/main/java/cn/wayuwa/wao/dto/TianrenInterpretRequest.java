package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Size;

/** 请求 AI 解读今日卦象时可选的个人关注点。 */
public record TianrenInterpretRequest(
        @Size(max = 500, message = "关注点不能超过 500 个字符") String focus) {
}
