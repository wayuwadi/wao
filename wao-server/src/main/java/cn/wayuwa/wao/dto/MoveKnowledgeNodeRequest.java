package cn.wayuwa.wao.dto;
/** 单节点关系变更，expectedParent 防止同一节点的并发移动互相覆盖。 */
public record MoveKnowledgeNodeRequest(String node, String parent, String expectedParent) {}
