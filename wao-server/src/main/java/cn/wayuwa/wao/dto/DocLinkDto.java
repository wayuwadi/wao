package cn.wayuwa.wao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 文档出链（指向项目 / 任务 / 其他文档）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocLinkDto(String type, String id) {
}
