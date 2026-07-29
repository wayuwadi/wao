package cn.wayuwa.wao.dto;

/** 自媒体 Agent 在 WAO 一级导航中的全局展示配置。 */
public record MediaAgentMenuConfigDto(String label, String group, int order, boolean visible) {
}
