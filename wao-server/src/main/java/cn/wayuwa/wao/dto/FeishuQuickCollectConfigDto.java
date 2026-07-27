package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 群聊快速收集配置（对外只回脱敏形态）。
 *
 * @param enabled            是否接收 @机器人 建任务
 * @param projectId          落点项目 id；留空表示自动找「Wa All in One」
 * @param verificationToken  Verification Token 脱敏值
 * @param hasVerificationToken 是否已配置 Verification Token
 * @param encryptKey         Encrypt Key 脱敏值
 * @param hasEncryptKey      是否已配置 Encrypt Key
 * @param mentionOpenIds     任务进入「收集中」时要 @ 的成员 open_id
 */
public record FeishuQuickCollectConfigDto(
        boolean enabled,
        String projectId,
        String verificationToken,
        boolean hasVerificationToken,
        String encryptKey,
        boolean hasEncryptKey,
        List<String> mentionOpenIds) {
}
