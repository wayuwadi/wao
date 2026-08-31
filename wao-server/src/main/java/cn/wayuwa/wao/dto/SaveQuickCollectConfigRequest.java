package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 保存群聊快速收集配置；密文字段传 null 表示保持原值。
 *
 * @param enabled            是否接收 @机器人 建任务
 * @param projectId          落点项目 id
 * @param verificationToken  Verification Token 明文，null 表示不改
 * @param encryptKey         Encrypt Key 明文，null 表示不改
 * @param mentionOpenIds     任务进入「收集中」时要 @ 的成员 open_id；null 表示不改
 * @param receivedEmoji      任务已保存回执及成员手工派工表情；null 表示不改
 * @param completedEmoji     AI Run 完成待验收表情；null 表示不改
 */
public record SaveQuickCollectConfigRequest(
        Boolean enabled,
        String projectId,
        String verificationToken,
        String encryptKey,
        List<String> mentionOpenIds,
        String receivedEmoji,
        String completedEmoji) {
}
