package cn.wayuwa.wao.service;

import java.util.Map;
import cn.wayuwa.wao.dto.CommentDto;
import cn.wayuwa.wao.dto.TaskDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 把真实任务评论同步到配置的飞书群机器人；通知失败不影响评论持久化。 */
@Service
public class FeishuCommentNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuCommentNotifier.class);
    private final RestClient client;
    private final String webhookUrl;
    public FeishuCommentNotifier(RestClient.Builder builder,
            @Value("${feishu.comment-webhook-url:}") String webhookUrl) {
        this.client = builder.build();
        this.webhookUrl = webhookUrl;
    }

    /** 发送一条包含项目、任务、评论人、评论内容和原任务链接的文本通知。 */
    public void notifyComment(TaskDto task, CommentDto comment, String memberName, String projectName) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        String text = "【WAO 任务新评论】\n项目：" + blank(projectName, task.project())
                + "\n任务：" + task.title() + "\n评论人：" + blank(memberName, comment.who())
                + "\n评论：" + comment.text() + "\n查看：https://wao.wayuwa.cn/projects/" + task.project()
                + "?task=" + task.id();
        try {
            client.post().uri(webhookUrl).body(Map.of("msg_type", "text", "content", Map.of("text", text)))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            LOG.warn("飞书任务评论通知失败：{}", ex.getMessage());
        }
    }
    private static String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
