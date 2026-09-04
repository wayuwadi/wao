package cn.wayuwa.wao.agent.dto;

/** 一次内容生成实际使用的 WAO 来源快照。 */
public record ContentSourceDto(String type, String title, String url, String excerpt) {
}
