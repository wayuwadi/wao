package cn.wayuwa.wao.service;

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

    /** 发送一条包含项目、任务、评论人、评论内容和原任务链接的文本通知。 */
    public void notifyComment(TaskDto task, CommentDto comment, String memberName, String projectName) {
        notifier.notifyText("【WAO 任务新评论】\n项目：" + blank(projectName, task.project())
                + "\n任务：" + task.title() + "\n评论人：" + blank(memberName, comment.who())
                + "\n评论：" + comment.text() + "\n查看：https://wao.wayuwa.cn/projects/" + task.project()
                + "?task=" + task.id());
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
