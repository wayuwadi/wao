package cn.wayuwa.wao.dto;

/** 已保存小记的一份润色对照；正文改变后由调用方按原文快照识别失效结果。 */
public record NotePolishVersionDto(String docId, String sourceMarkdown, String polishedMarkdown) { }
