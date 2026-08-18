package cn.wayuwa.wao.dto;

import java.util.List;

/** Runner 从本机 Codex app-server 读取的脱敏额度快照。 */
public record RunnerCodexQuotaReport(
        String accountEmail,
        String plan,
        CodexQuotaCreditsDto credits,
        List<CodexQuotaLimitDto> limits) {
}
