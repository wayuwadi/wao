package cn.wayuwa.wao.service;

import cn.wayuwa.wao.feishu.FeishuWebhookNotifier.Card;
import cn.wayuwa.wao.mapper.InternalNotificationMapper;
import java.net.URI;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 将原群通知保存到 WAO 通知中心，目标只接受站内页面。 */
@Service
public class InternalNotificationService {
    private final InternalNotificationMapper notifications;

    public InternalNotificationService(InternalNotificationMapper notifications) {
        this.notifications = notifications;
    }

    /** 持久化后返回成功；存储失败向调用方抛错，不能冒充已送达。 */
    public boolean publish(Card card) { return publishToMember(null, card); }

    /** 个人提醒仅当前成员可见；空成员表示既有组织共享系统通知。 */
    public boolean publishToMember(String memberId, Card card) {
        StringBuilder body = new StringBuilder();
        if (card.fields() != null) card.fields().stream().filter(field -> field.size() >= 2)
                .forEach(field -> body.append(field.get(0)).append("：").append(field.get(1)).append("\n"));
        if (card.body() != null) body.append(card.body());
        String title = card.title() == null ? "WAO 通知" : card.title();
        notifications.insert(UUID.randomUUID().toString(), memberId, title.substring(0, Math.min(title.length(), 255)),
                body.toString(), localTarget(card.linkUrl()));
        return true;
    }

    private static String localTarget(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() && !("https".equals(uri.getScheme()) && "wao.wayuwa.cn".equals(uri.getHost()))) return null;
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) return null;
            return path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException ignored) { return null; }
    }
}
