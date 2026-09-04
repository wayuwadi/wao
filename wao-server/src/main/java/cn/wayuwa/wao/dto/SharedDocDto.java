package cn.wayuwa.wao.dto;

/**
 * 文档 / 小记的公开分享视图（免登录只读）。
 *
 * <p>只外泄标题、正文与更新时间；作者、关联、反向链接、协同状态一律不带 ——
 * 拿到链接的陌生人只该看到内容本身。{@code kbId} 用于「在 WAO 中打开」回链，
 * 回链目标本身仍需登录。
 *
 * @param id      文档 id
 * @param title   标题（可能为空，前端自行兜底「未命名文档」）
 * @param note    是否是小记（存放在小记专用知识库里的文档）
 * @param kbId    所属知识库 id
 * @param updated 最近更新时间（ISO-8601，老数据可能是现成文案）
 * @param md      正文 Markdown（站内图片地址保持相对路径，由 .md 端点再绝对化）
 */
public record SharedDocDto(String id, String title, boolean note, String kbId, String updated, String md,
        RichDocContentDto content) {
    public SharedDocDto(String id, String title, boolean note, String kbId, String updated, String md) {
        this(id, title, note, kbId, updated, md, null);
    }
}
