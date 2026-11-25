package cn.wayuwa.wao.dto;

import java.util.List;

/** Agy 额度模型分组（如 Gemini Models、Claude and GPT models）。 */
public record AgyQuotaGroupDto(String name, List<AgyQuotaBucketDto> buckets) {
}
