package cn.wayuwa.wao.dto;

/**
 * 群聊发言人映射：飞书 open_id ↔ 在 WAO 里显示的人名（娃哥 / 娃弟 / 机器人通知）。
 *
 * @param id   飞书 open_id
 * @param name 显示名
 */
public record FeishuSenderDto(String id, String name) {
}
