package cn.wayuwa.wao.dto;

/** Runner 在本机生成的一篇有界记忆报告；scopeId 由服务端按真实节点能力收窄。 */
public record RunnerMemoryReportItem(
        String scopeType,
        String scopeId,
        String category,
        String title,
        String content) { }

