package cn.wayuwa.wao.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 一次有界批量上报，减少监控自身产生的网络请求。 */
public record RequestMetricBatchRequest(
        @NotNull @Size(min = 1, max = 100) List<@Valid RequestMetricInput> metrics) {
}
