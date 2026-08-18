package cn.wayuwa.wao.dto;

/** 模板管理中的可编辑元数据；模板正文由“另存为模板”时生成快照。 */
public record PatchDocTemplateRequest(String name, String description) {
}
