package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AccountResourceEntity;
import cn.wayuwa.wao.domain.VerificationMessageEntity;
import cn.wayuwa.wao.dto.AccountResourceDto;
import cn.wayuwa.wao.dto.AccountResourceIntegrationDto;
import cn.wayuwa.wao.dto.InboundVerificationMessageRequest;
import cn.wayuwa.wao.dto.LatestVerificationCodeDto;
import cn.wayuwa.wao.dto.RotatedIntegrationTokenDto;
import cn.wayuwa.wao.dto.SaveAccountResourceRequest;
import cn.wayuwa.wao.dto.SaveAccountResourceIntegrationRequest;
import cn.wayuwa.wao.dto.VerificationIngestResultDto;
import cn.wayuwa.wao.dto.VerificationMessageDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountResourceMapper;
import cn.wayuwa.wao.mapper.VerificationMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 组织账号资源、短信入站、飞书转发和爬虫按手机号取码的完整服务。
 *
 * <p>账号密码、短信正文和验证码均使用 {@link VaultCryptoService} 加密；入站与只读令牌只保存
 * SHA-256 摘要。两个机器接口使用不同令牌，手机转发器不能读取账号或验证码历史，爬虫也不能伪造短信。
 */
@Service
public class AccountResourceService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_MAX_AGE_SECONDS = 300;
    private static final int MAX_MAX_AGE_SECONDS = 600;
    private static final Pattern CODE_AFTER_LABEL = Pattern.compile(
            "(?i)(?:验证码|校验码|动态码|短信码|verification\\s*code|one[- ]?time\\s*(?:password|code)|\\botp\\b|\\bcode\\b)\\D{0,16}(\\d{4,8})");
    private static final Pattern CODE_BEFORE_LABEL = Pattern.compile(
            "(?i)(\\d{4,8})\\D{0,16}(?:验证码|校验码|动态码|短信码|verification\\s*code|otp)");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountResourceMapper accounts;
    private final VerificationMessageMapper messages;
    private final VaultCryptoService crypto;
    private final SettingService settings;
    private final FeishuWebhookConfigService webhooks;
    private final FeishuWebhookNotifier notifier;

    public AccountResourceService(AccountResourceMapper accounts, VerificationMessageMapper messages,
            VaultCryptoService crypto, SettingService settings, FeishuWebhookConfigService webhooks,
            FeishuWebhookNotifier notifier) {
        this.accounts = accounts;
        this.messages = messages;
        this.crypto = crypto;
        this.settings = settings;
        this.webhooks = webhooks;
        this.notifier = notifier;
    }

    /** 按最近更新时间列出组织共享账号，列表不含密码明文。 */
    public List<AccountResourceDto> listAccounts() {
        return accounts.selectList(new QueryWrapper<AccountResourceEntity>().orderByDesc("updated_at"))
                .stream().map(this::toDto).toList();
    }

    /** 新建组织共享账号；创建人从服务端会话确定。 */
    public AccountResourceDto createAccount(SaveAccountResourceRequest request, String memberId) {
        LocalDateTime now = now();
        AccountResourceEntity entity = new AccountResourceEntity();
        entity.setId(id("ar"));
        applyAccount(entity, request, false);
        entity.setRevision(1);
        entity.setCreatedBy(memberId);
        entity.setUpdatedBy(memberId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        accounts.insert(entity);
        return toDto(entity);
    }

    /** 按 revision 更新共享账号，避免多人编辑时静默覆盖；密码留空会保留旧密文。 */
    public AccountResourceDto updateAccount(String id, SaveAccountResourceRequest request, String memberId) {
        AccountResourceEntity current = requireAccount(id);
        if (request.revision() == null || !request.revision().equals(current.getRevision())) {
            throw new ConflictException("该账号已被其他成员更新，请刷新后再编辑");
        }
        AccountResourceEntity next = new AccountResourceEntity();
        applyAccount(next, request, true);
        UpdateWrapper<AccountResourceEntity> update = new UpdateWrapper<AccountResourceEntity>()
                .eq("id", id).eq("revision", request.revision())
                .set("platform", next.getPlatform())
                .set("account_label", next.getAccountLabel())
                .set("login_identifier", next.getLoginIdentifier())
                .set("phone_number", next.getPhoneNumber())
                .set("notes", next.getNotes())
                .set("enabled", next.getEnabled())
                .set("revision", request.revision() + 1)
                .set("updated_by", memberId)
                .set("updated_at", now());
        if (StringUtils.hasText(request.secret())) {
            VaultCryptoService.Encrypted encrypted = crypto.encrypt(request.secret());
            update.set("secret_ciphertext", encrypted.ciphertext()).set("secret_nonce", encrypted.nonce());
        }
        if (accounts.update(null, update) == 0) {
            throw new ConflictException("该账号已被其他成员更新，请刷新后再编辑");
        }
        return toDto(requireAccount(id));
    }

    /** 只有版本未变化时删除共享账号；验证码历史保留作为独立审计记录。 */
    public void deleteAccount(String id, int revision) {
        requireAccount(id);
        int deleted = accounts.delete(new QueryWrapper<AccountResourceEntity>()
                .eq("id", id).eq("revision", revision));
        if (deleted == 0) throw new ConflictException("该账号已被其他成员更新，请刷新后再删除");
    }

    /** 显式解密并返回一次账号密码；未设置时返回 null。 */
    public String revealSecret(String id) {
        AccountResourceEntity entity = requireAccount(id);
        return entity.getSecretCiphertext() == null ? null
                : crypto.decrypt(entity.getSecretCiphertext(), entity.getSecretNonce());
    }

    /** 返回集成状态与稳定路径，不返回任何令牌明文。 */
    public AccountResourceIntegrationDto integration() {
        return new AccountResourceIntegrationDto(
                StringUtils.hasText(settings.get(SettingService.ACCOUNT_RESOURCE_INGEST_TOKEN_HASH)),
                StringUtils.hasText(settings.get(SettingService.ACCOUNT_RESOURCE_CRAWLER_TOKEN_HASH)),
                blank(settings.get(SettingService.ACCOUNT_RESOURCE_NOTIFICATION_WEBHOOK_ID)),
                "/api/public/account-resources/messages",
                "/api/public/account-resources/codes/latest?phone={手机号}");
    }

    /** 保存验证码飞书转发目标；空值表示沿用通用注册表的系统默认机器人。 */
    public AccountResourceIntegrationDto saveIntegration(SaveAccountResourceIntegrationRequest request) {
        if (StringUtils.hasText(request.notificationWebhookId())) {
            webhooks.enabledTarget(request.notificationWebhookId().trim());
        }
        settings.put(SettingService.ACCOUNT_RESOURCE_NOTIFICATION_WEBHOOK_ID,
                blank(request.notificationWebhookId()));
        return integration();
    }

    /** 轮换手机入站或爬虫只读令牌；明文只在本次响应中返回。 */
    public RotatedIntegrationTokenDto rotateToken(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String key = switch (normalized) {
            case "ingest" -> SettingService.ACCOUNT_RESOURCE_INGEST_TOKEN_HASH;
            case "crawler" -> SettingService.ACCOUNT_RESOURCE_CRAWLER_TOKEN_HASH;
            default -> throw new BadRequestException("令牌类型只支持 ingest 或 crawler");
        };
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        settings.put(key, sha256(token));
        return new RotatedIntegrationTokenDto(normalized, token);
    }

    /**
     * 接收手机转发器短信、识别验证码并投递飞书。相同 externalId 或相同消息指纹的重试只返回原结果。
     */
    public VerificationIngestResultDto ingest(String authorization, InboundVerificationMessageRequest request) {
        verifyToken(authorization, SettingService.ACCOUNT_RESOURCE_INGEST_TOKEN_HASH, "短信入站");
        String phone = canonicalPhone(request.phoneNumber());
        LocalDateTime receivedAt = request.receivedAt() == null ? now() : request.receivedAt();
        validateReceivedAt(receivedAt);
        messages.delete(new QueryWrapper<VerificationMessageEntity>().lt("created_at", now().minusDays(30)));
        String sender = trim(request.sender(), 128);
        String source = trim(request.source(), 128);
        String fingerprint = fingerprint(request.externalId(), phone, sender, source, request.message(), receivedAt);
        VerificationMessageEntity existing = messages.selectOne(
                new QueryWrapper<VerificationMessageEntity>().eq("fingerprint", fingerprint));
        if (existing != null) return ingestResult(existing, true);

        String code = extractCode(request.message());
        VaultCryptoService.Encrypted encryptedMessage = crypto.encrypt(request.message());
        VerificationMessageEntity entity = new VerificationMessageEntity();
        entity.setId(id("vm"));
        entity.setFingerprint(fingerprint);
        entity.setPhoneNumber(phone);
        entity.setSender(sender);
        entity.setSource(source);
        entity.setMessageCiphertext(encryptedMessage.ciphertext());
        entity.setMessageNonce(encryptedMessage.nonce());
        if (code != null) {
            VaultCryptoService.Encrypted encryptedCode = crypto.encrypt(code);
            entity.setCodeCiphertext(encryptedCode.ciphertext());
            entity.setCodeNonce(encryptedCode.nonce());
        }
        entity.setReceivedAt(receivedAt);
        entity.setForwardStatus("PENDING");
        entity.setForwardError("");
        entity.setCreatedAt(now());
        try {
            messages.insert(entity);
        } catch (DuplicateKeyException duplicate) {
            VerificationMessageEntity winner = messages.selectOne(
                    new QueryWrapper<VerificationMessageEntity>().eq("fingerprint", fingerprint));
            if (winner != null) return ingestResult(winner, true);
            throw duplicate;
        }
        forward(entity);
        return ingestResult(entity, false);
    }

    /** 按收到时间倒序展示最近 100 条真实短信及解析结果。 */
    public List<VerificationMessageDto> recentMessages() {
        return messages.selectList(new QueryWrapper<VerificationMessageEntity>()
                        .orderByDesc("received_at").last("LIMIT 100"))
                .stream().map(this::toMessageDto).toList();
    }

    /** 对尚未成功的消息重试飞书转发；已成功的消息不会重复发送。 */
    public VerificationMessageDto retryForward(String id) {
        VerificationMessageEntity entity = requireMessage(id);
        if (!"SENT".equals(entity.getForwardStatus())) forward(entity);
        return toMessageDto(entity);
    }

    /** 爬虫使用只读令牌按手机号取得最近验证码，默认只接受五分钟内消息且最多放宽到十分钟。 */
    public LatestVerificationCodeDto latestCode(String authorization, String phoneNumber, Integer maxAgeSeconds) {
        verifyToken(authorization, SettingService.ACCOUNT_RESOURCE_CRAWLER_TOKEN_HASH, "爬虫取码");
        String phone = canonicalPhone(phoneNumber);
        int age = maxAgeSeconds == null ? DEFAULT_MAX_AGE_SECONDS : maxAgeSeconds;
        if (age < 1 || age > MAX_MAX_AGE_SECONDS) {
            throw new BadRequestException("maxAgeSeconds 必须在 1 到 600 之间");
        }
        VerificationMessageEntity entity = messages.selectOne(new QueryWrapper<VerificationMessageEntity>()
                .eq("phone_number", phone)
                .isNotNull("code_ciphertext")
                .ge("received_at", now().minusSeconds(age))
                .orderByDesc("received_at")
                .last("LIMIT 1"));
        if (entity == null) throw new NotFoundException("该手机号在指定时间内没有可用验证码");
        return new LatestVerificationCodeDto(phone, decryptCode(entity), entity.getSender(), entity.getSource(),
                entity.getReceivedAt(), accountLabels(phone));
    }

    private void applyAccount(AccountResourceEntity entity, SaveAccountResourceRequest request, boolean updating) {
        entity.setPlatform(request.platform().trim());
        entity.setAccountLabel(request.accountLabel().trim());
        entity.setLoginIdentifier(trim(request.loginIdentifier(), 256));
        entity.setPhoneNumber(StringUtils.hasText(request.phoneNumber()) ? canonicalPhone(request.phoneNumber()) : "");
        entity.setNotes(trim(request.notes(), 1024));
        entity.setEnabled(request.enabled());
        if (!updating && StringUtils.hasText(request.secret())) {
            VaultCryptoService.Encrypted encrypted = crypto.encrypt(request.secret());
            entity.setSecretCiphertext(encrypted.ciphertext());
            entity.setSecretNonce(encrypted.nonce());
        }
    }

    private void forward(VerificationMessageEntity entity) {
        String code = decryptCode(entity);
        String body = decryptMessage(entity);
        List<String> labels = accountLabels(entity.getPhoneNumber());
        String accountText = labels.isEmpty() ? "未匹配账号资源" : String.join("、", labels);
        FeishuWebhookNotifier.Card card = new FeishuWebhookNotifier.Card(
                "验证码接收", code == null ? "orange" : "blue",
                List.of(
                        List.of("手机号", entity.getPhoneNumber()),
                        List.of("验证码", code == null ? "未识别，请查看短信正文" : code),
                        List.of("账号", accountText),
                        List.of("发送方", blank(entity.getSender())),
                        List.of("来源", blank(entity.getSource())),
                        List.of("收到时间", entity.getReceivedAt().toString().replace('T', ' '))),
                body, null, null);
        String webhookId = blank(settings.get(SettingService.ACCOUNT_RESOURCE_NOTIFICATION_WEBHOOK_ID));
        boolean sent = sendVerificationCard(webhookId, card);
        entity.setForwardStatus(sent ? "SENT" : "FAILED");
        entity.setForwardError(sent ? "" : "飞书机器人未配置、已停用或发送失败，可在账号资源库重试");
        messages.updateById(entity);
    }

    /** 验证码是五分钟级时效消息，不受普通系统通知静默时段抑制；目标本身停用时仍失败关闭。 */
    private boolean sendVerificationCard(String webhookId, FeishuWebhookNotifier.Card card) {
        try {
            if (StringUtils.hasText(webhookId)) {
                FeishuWebhookConfigService.NotificationTarget target = webhooks.enabledTarget(webhookId);
                notifier.send(target.url(), card);
                return true;
            }
            if (!notifier.enabled()) return false;
            notifier.send(notifier.resolvedUrl(), card);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private VerificationIngestResultDto ingestResult(VerificationMessageEntity entity, boolean duplicate) {
        boolean sent = "SENT".equals(entity.getForwardStatus());
        boolean recognized = entity.getCodeCiphertext() != null;
        String result = duplicate ? "重复消息已忽略，未再次转发"
                : sent ? "短信已保存并转发" : "短信已保存，飞书转发失败，可在 WAO 重试";
        return new VerificationIngestResultDto(entity.getId(), duplicate, recognized, sent, result);
    }

    private VerificationMessageDto toMessageDto(VerificationMessageEntity entity) {
        return new VerificationMessageDto(entity.getId(), entity.getPhoneNumber(), entity.getSender(),
                entity.getSource(), decryptMessage(entity), decryptCode(entity), entity.getReceivedAt(),
                entity.getForwardStatus(), entity.getForwardError(), accountLabels(entity.getPhoneNumber()));
    }

    private List<String> accountLabels(String phone) {
        if (!StringUtils.hasText(phone)) return List.of();
        return accounts.selectList(new QueryWrapper<AccountResourceEntity>()
                        .eq("phone_number", phone).eq("enabled", true).orderByAsc("account_label"))
                .stream().map(AccountResourceEntity::getAccountLabel).toList();
    }

    private String decryptMessage(VerificationMessageEntity entity) {
        return crypto.decrypt(entity.getMessageCiphertext(), entity.getMessageNonce());
    }

    private String decryptCode(VerificationMessageEntity entity) {
        return entity.getCodeCiphertext() == null ? null
                : crypto.decrypt(entity.getCodeCiphertext(), entity.getCodeNonce());
    }

    private AccountResourceDto toDto(AccountResourceEntity entity) {
        return new AccountResourceDto(entity.getId(), entity.getPlatform(), entity.getAccountLabel(),
                entity.getLoginIdentifier(), entity.getPhoneNumber(), entity.getNotes(),
                Boolean.TRUE.equals(entity.getEnabled()), entity.getSecretCiphertext() != null,
                entity.getRevision(), entity.getCreatedBy(), entity.getUpdatedBy(), entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private AccountResourceEntity requireAccount(String id) {
        AccountResourceEntity entity = accounts.selectById(id);
        if (entity == null) throw new NotFoundException("账号资源不存在：" + id);
        return entity;
    }

    private VerificationMessageEntity requireMessage(String id) {
        VerificationMessageEntity entity = messages.selectById(id);
        if (entity == null) throw new NotFoundException("验证码消息不存在：" + id);
        return entity;
    }

    private void verifyToken(String authorization, String settingKey, String label) {
        String expected = settings.get(settingKey);
        if (!StringUtils.hasText(expected)) throw new UnauthorizedException(label + "令牌尚未配置");
        String supplied = bearer(authorization);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                sha256(supplied).getBytes(StandardCharsets.US_ASCII))) {
            throw new UnauthorizedException(label + "令牌无效");
        }
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("请使用 Authorization: Bearer <token>");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) throw new UnauthorizedException("Bearer token 不能为空");
        return token;
    }

    static String extractCode(String message) {
        Matcher after = CODE_AFTER_LABEL.matcher(message);
        if (after.find()) return after.group(1);
        Matcher before = CODE_BEFORE_LABEL.matcher(message);
        return before.find() ? before.group(1) : null;
    }

    static String canonicalPhone(String input) {
        String digits = input == null ? "" : input.replaceAll("\\D", "");
        if (digits.startsWith("0086") && digits.length() == 15) digits = digits.substring(4);
        else if (digits.startsWith("86") && digits.length() == 13) digits = digits.substring(2);
        if (digits.length() < 6 || digits.length() > 20) throw new BadRequestException("手机号格式不正确");
        return digits;
    }

    private static String fingerprint(String externalId, String phone, String sender, String source,
            String message, LocalDateTime receivedAt) {
        String basis = StringUtils.hasText(externalId) ? "external|" + source + "|" + externalId.trim()
                : "content|" + phone + "|" + sender + "|" + source + "|" + message + "|" + receivedAt;
        return sha256(basis);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少 SHA-256", impossible);
        }
    }

    private static void validateReceivedAt(LocalDateTime value) {
        LocalDateTime now = now();
        if (value.isAfter(now.plusMinutes(5)) || value.isBefore(now.minusDays(7))) {
            throw new BadRequestException("receivedAt 必须在当前时间前七天到后五分钟之间");
        }
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    private static String trim(String value, int max) {
        String result = blank(value);
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }
}
