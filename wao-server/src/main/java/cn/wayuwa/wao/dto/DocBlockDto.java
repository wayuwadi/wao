package cn.wayuwa.wao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 文档正文块。标题层级缺省为 2，以兼容旧数据。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocBlockDto(String call, String h, String p, List<String> ul, List<String> q, Integer hLevel) {
    public DocBlockDto(String call, String h, String p, List<String> ul, List<String> q) {
        this(call, h, p, ul, q, null);
    }
}
