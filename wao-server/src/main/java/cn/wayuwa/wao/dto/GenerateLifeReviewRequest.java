package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** 生成指定自然周期回顾的请求。 */
public record GenerateLifeReviewRequest(@NotBlank String period, LocalDate anchorDate) { }
