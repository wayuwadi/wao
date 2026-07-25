package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 新建小记请求：不指定知识库，一律落到小记知识库。
 *
 * @param title  标题，留空时由正文首行推导
 * @param blocks 正文块
 */
public record CreateNoteRequest(String title, List<DocBlockDto> blocks) {
}
