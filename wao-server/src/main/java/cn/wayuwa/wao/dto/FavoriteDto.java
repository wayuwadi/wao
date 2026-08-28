package cn.wayuwa.wao.dto;

/**
 * 一条收藏。label 为实时解析的展示名；parentId 是跳转所需的父级 id
 * （任务→项目 id，文档→知识库 id，模块和项目无父级为 null）。
 */
public record FavoriteDto(String type, String targetId, String label, String parentId) {
}
