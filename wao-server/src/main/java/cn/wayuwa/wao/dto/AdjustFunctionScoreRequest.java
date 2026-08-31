package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AdjustFunctionScoreRequest(@Min(-1) @Max(1) int delta) {}
