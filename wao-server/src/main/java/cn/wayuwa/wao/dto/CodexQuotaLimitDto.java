package cn.wayuwa.wao.dto;

import java.util.List;

/** Codex 通用或模型专属额度及其真实窗口。 */
public record CodexQuotaLimitDto(
        String id,
        String label,
        List<CodexQuotaWindowDto> windows) {
}
