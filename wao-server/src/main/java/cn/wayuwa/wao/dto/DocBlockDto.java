package cn.wayuwa.wao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 文档正文块。字段互斥，命中哪个就是哪种块（提示 / 标题 / 段落 / 列表 / 引用）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocBlockDto(String call, String h, String p, List<String> ul, List<String> q) {
}
