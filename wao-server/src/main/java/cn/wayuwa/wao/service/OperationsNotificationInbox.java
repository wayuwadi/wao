package cn.wayuwa.wao.service;

import cn.wayuwa.wao.mapper.InternalNotificationMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 从运维账号专用目录接收告警；没有公网写入接口，默认关闭。 */
@Component
public class OperationsNotificationInbox {
    private static final Logger log = LoggerFactory.getLogger(OperationsNotificationInbox.class);
    private final Path inbox;
    private final String recipient;
    private final ObjectMapper json;
    private final InternalNotificationMapper notifications;
    private final MemberMapper members;

    public OperationsNotificationInbox(
            @Value("${wao.operations.inbox:}") String directory,
            @Value("${wao.operations.recipient:}") String recipient,
            ObjectMapper json, InternalNotificationMapper notifications, MemberMapper members) {
        this.inbox = directory.isBlank() ? null : Path.of(directory);
        this.recipient = recipient;
        this.json = json;
        this.notifications = notifications;
        this.members = members;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public synchronized void tick() {
        if (inbox == null || recipient.isBlank() || !Files.isDirectory(inbox)) return;
        try {
            if (members.selectById(recipient) == null) {
                log.warn("Operations notification recipient does not exist; inbox retained");
                return;
            }
            try (var entries = Files.list(inbox)) {
                for (Path file : entries.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted().limit(100).toList()) {
                    deliver(file);
                }
            }
        } catch (Exception e) {
            log.warn("Operations inbox scan failed: {}", e.getClass().getSimpleName());
        }
    }

    private void deliver(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16_384) return;
            var event = json.readTree(Files.readString(file).replaceFirst("^\\uFEFF", ""));
            String id = event.path("id").asText();
            if (!UUID.fromString(id).toString().equals(id) || !file.getFileName().toString().equals(id + ".json")) return;
            String title = event.path("title").asText();
            String body = event.path("body").asText();
            String status = event.path("status").asText();
            OffsetDateTime.parse(event.path("occurredAt").asText());
            if (title.isBlank() || title.length() > 200 || body.isBlank() || body.length() > 4000
                    || !(status.equals("FAILURE") || status.equals("RECOVERY") || status.equals("TEST"))) return;
            // UUID 主键使数据库成功但文件删除失败时的重试保持幂等。
            notifications.insertOperation("ops:" + id, recipient, title,
                    "事件时间：" + event.path("occurredAt").asText() + "\n" + body);
            Files.delete(file);
        } catch (Exception e) {
            log.warn("Operations notification retained for retry: {}", e.getClass().getSimpleName());
        }
    }
}
