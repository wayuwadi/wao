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

    public FeishuCommentNotifier(FeishuWebhookNotifier notifier) {
        this.notifier = notifier;
    }

    /** 发送一张含项目、任务、评论人、评论内容与跳转按钮的卡片通知。 */
    public void notifyComment(TaskDto task, CommentDto comment, String memberName, String projectName) {
        notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "任务新评论",
                "blue",
                List.of(
                        List.of("项目", blank(projectName, task.project())),
                        List.of("任务", task.title()),
                        List.of("评论人", blank(memberName, comment.who()))),
                comment.text(),
                "打开任务",
                "https://wao.wayuwa.cn/projects/" + task.project() + "?task=" + task.id()));
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
