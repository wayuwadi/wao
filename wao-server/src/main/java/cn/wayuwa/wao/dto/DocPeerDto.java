package cn.wayuwa.wao.dto;

/**
 * 正打开某篇文档的协作者。
 *
 * @param id      成员 id
 * @param name    成员名
 * @param initial 头像首字
 * @param color   头像色
 * @param line    光标所在的顶层块序号；-1 表示只在查看
 * @param editing 最近一次心跳前是否在输入
 */
public record DocPeerDto(
        String id,
        String name,
        String initial,
        String color,
        Integer line,
        Boolean editing) {
}
