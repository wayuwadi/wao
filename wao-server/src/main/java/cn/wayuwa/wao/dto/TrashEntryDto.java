package cn.wayuwa.wao.dto;

/**
 * 回收站条目。
 *
 * @param type      条目类型：kb / folder / doc
 * @param id        条目 id
 * @param name      展示名（文档用标题，未命名时给占位）
 * @param parent    归属描述，如「知识库 · 个人成长 / 学习笔记」，方便判断该不该恢复
 * @param deletedAt 进入回收站的时间（ISO-8601）
 * @param deletedBy 删除人 member id，未知时为空
 * @param expiresAt 自动彻底删除的时间（ISO-8601）
 */
public record TrashEntryDto(
        String type,
        String id,
        String name,
        String parent,
        String deletedAt,
        String deletedBy,
        String expiresAt) {
}
