package cn.wayuwa.wao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一轮任务 PDCA 记录。
 *
 * @param id         稳定标识，前端新增时生成
 * @param plan       P：本轮希望达成的结果与判定标准
 * @param check      C：结果、偏差与原因复盘
 * @param act        A：下一轮要保留、修正或尝试的行动
 * @param outcome    本轮结果：达成、部分达成或未达成；复盘前为空
 * @param startedAt  本轮开始时间（ISO 8601）
 * @param reviewedAt 本轮完成复盘的时间（ISO 8601）；未闭环时为空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PdcaCycle(
        String id,
        String plan,
        String check,
        String act,
        String outcome,
        String startedAt,
        String reviewedAt) {
}
