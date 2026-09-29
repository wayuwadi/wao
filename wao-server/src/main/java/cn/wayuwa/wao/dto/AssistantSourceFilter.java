package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** AI 助手检索 WAO 资产时使用的结构化来源筛选。 */
public record AssistantSourceFilter(
        /** 资产类型；空列表表示全部。 */
        @Size(max = 4) List<@Pattern(regexp = "chat|knowledge|project|task") String> assetTypes,
        /** 飞书群聊 id；当前只会返回已配置同步的群，但契约支持后续多群扩展。 */
        @Size(max = 20) List<@Size(max = 128) String> chatIds,
        /** 显示名称精确匹配的发言人。 */
        @Size(max = 30) List<@Size(max = 80) String> senders,
        /** 群聊归档日期下界（含）。 */
        LocalDate dateFrom,
        /** 群聊归档日期上界（含）。 */
        LocalDate dateTo,
        /** 项目与任务所属项目筛选。 */
        @Size(max = 30) List<@Size(max = 32) String> projectIds) {
}
