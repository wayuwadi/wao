package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.FeishuBotInboxEntity;
import cn.wayuwa.wao.mapper.FeishuBotInboxMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 回调只认领安全内容；业务处理异步重试，绝不将校验 Token 写入收件箱。 */
@Service
public class FeishuBotInboxService {
    private final FeishuBotInboxMapper mapper;
    private final VaultCryptoService crypto;
    private final ObjectMapper json;
    private final FeishuWebhookConfigService bots;
    private final SettingService settings;
    public FeishuBotInboxService(FeishuBotInboxMapper mapper, VaultCryptoService crypto, ObjectMapper json,
            FeishuWebhookConfigService bots, SettingService settings) {
        this.mapper=mapper; this.crypto=crypto; this.json=json; this.bots=bots; this.settings=settings;
    }

    /** 持久收下已认证目标群的消息；插入失败直接返回失败，让飞书重新投递。 */
    public void enqueue(JsonNode event, String eventId, String appId) {
        JsonNode message = event.path("message");
        String chat = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        if (chat == null || chat.isBlank() || !chat.equals(message.path("chat_id").asText())
                || !"user".equals(event.path("sender").path("sender_type").asText())) return;
        var route = bots.captureRoute(message.path("mentions"));
        if (route == null) return;
        String messageId = message.path("message_id").asText("");
        if (messageId.isBlank()) throw new BadRequestException("飞书消息缺少稳定身份");
        String payload = event.toString();
        if (payload.length() > 262144) throw new BadRequestException("飞书消息过长");
        FeishuBotInboxEntity row = new FeishuBotInboxEntity();
        row.setId(digest(appId + "\n" + messageId + "\n" + payload));
        row.setEventId(eventId); row.setMessageId(messageId); row.setAppId(appId);
        row.setWebhookId(route.webhookId()); row.setCaptureKind(route.kind());
        var encrypted = crypto.encrypt(payload);
        row.setPayloadCiphertext(encrypted.ciphertext()); row.setPayloadNonce(encrypted.nonce());
        row.setStatus("PENDING"); row.setAttempts(0);
        row.setAvailableAt(LocalDateTime.now()); row.setUpdatedAt(row.getAvailableAt());
        try { mapper.insert(row); } catch (DuplicateKeyException ignored) { /* 同一事件已经持久收下 */ }
    }

    /** 仅工作线程解密已认证内容；不返回到浏览器。 */
    public JsonNode event(FeishuBotInboxEntity row) {
        try { return json.readTree(crypto.decrypt(row.getPayloadCiphertext(), row.getPayloadNonce())); }
        catch (Exception ex) { throw new BadRequestException("飞书收件箱无法解密"); }
    }
    /** 同业务创建事务一起完成收件箱，清除事件正文。 */
    public void complete(FeishuBotInboxEntity row) {
        if (mapper.complete(row.getId(), row.getLeaseToken()) != 1)
            throw new ConflictException("收件箱租约已改变，本次处理未提交");
    }
    /** 处理事务开始时锁定租约并核对 fencing token，旧工作线程不得覆盖新认领。 */
    public void requireLease(FeishuBotInboxEntity row) {
        if (row.getLeaseToken() == null || !row.getLeaseToken().equals(mapper.lockLease(row.getId())))
            throw new ConflictException("收件箱租约已改变，请由当前工作线程处理");
    }
    private static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("无法生成收件箱身份"); }
    }
}
