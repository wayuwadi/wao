package cn.wayuwa.wao.service;

import java.util.List;

import cn.wayuwa.wao.dto.CommentDto;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import org.springframework.stereotype.Service;

/**
 * 把真实任务评论同步到飞书群机器人；通知失败不影响评论持久化。
 *
 * <p>地址不再自己读环境变量：全系统共用「设置 → 通知 · 飞书 Webhook」里那一个
 * （{@link FeishuWebhookNotifier}，见 ADR-019），界面上还没配时仍回落到部署环境变量。
 */
@Service
public class FeishuCommentNotifier {

    private final FeishuWebhookNotifier notifier;
    private final ProjectNotifyGate notifyGate;

    public FeishuCommentNotifier(FeishuWebhookNotifier notifier, ProjectNotifyGate notifyGate) {
        this.notifier = notifier;
        this.notifyGate = notifyGate;
    }

    /**
     * 发送一张含项目、任务、评论人、评论内容与跳转按钮的卡片通知。
     *
     * <p>所属项目关掉通知时直接跳过：评论和状态变化一样属于「项目的动静」，
     * 不该因为走的是另一个通知器就绕过开关。
     *
     * @param mentionOpenIds 评论里 @ 到的成员的飞书 open_id（已排掉评论人自己），可为空；
     *                       有值时在卡片正文最前面真实 @ 出来
     */
    public void notifyComment(TaskDto task, CommentDto comment, String memberName, String projectName,
            List<String> mentionOpenIds) {
        if (!notifyGate.enabled(task.project())) {
            return;
        }
        StringBuilder body = new StringBuilder();
        if (mentionOpenIds != null && !mentionOpenIds.isEmpty()) {
            for (String openId : mentionOpenIds) {
                body.append("<at id=").append(openId).append("></at> ");
            }
            body.append('\n');
        }
        body.append(comment.text());
        notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "任务新评论",
                "blue",
                List.of(
                        List.of("项目", blank(projectName, task.project())),
                        List.of("任务", task.title()),
                        List.of("评论人", blank(memberName, comment.who()))),
                body.toString(),
                "打开任务",
                "https://wao.wayuwa.cn/projects/" + task.project() + "?task=" + task.id()));
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
