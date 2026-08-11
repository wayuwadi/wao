package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 请求一次选题推荐。
 *
 * @param count 期望条数，1-10，模型可以少给：没有合适的热点时宁可少选
 */
public record RecommendRequest(@NotBlank(message = "请选择平台") String platform, Integer count) {
}
