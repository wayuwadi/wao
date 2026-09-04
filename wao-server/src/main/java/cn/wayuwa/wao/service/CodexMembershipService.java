package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.SystemSettingEntity;
import cn.wayuwa.wao.domain.QuotaRefreshNotificationStateEntity;
import cn.wayuwa.wao.dto.CodexQuotaAccountDto;
import cn.wayuwa.wao.dto.NotificationSettingsDto.SystemPolicy;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.SystemSettingMapper;
import cn.wayuwa.wao.mapper.QuotaRefreshNotificationStateMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以真实 Codex 账号为目标保存人工补充日期，与 Runner 自动额度快照分开维护。 */
@Service
public class CodexMembershipService {
    private static final String PREFIX = "codex-membership-v1:";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final SystemSettingMapper settings;
    private final QuotaRefreshNotificationStateMapper notifications;
    private final CodexQuotaService quotas;
    private final NotificationSettingsService policy;
    private final FeishuWebhookNotifier notifier;
    private final ObjectMapper json;
    private final String homeUrl;

    public record Membership(String accountKey, String purchasedAt, String proExpiresAt, String revision) { }
    public record AccountMembership(CodexQuotaAccountDto account, Membership membership) { }

    public CodexMembershipService(SystemSettingMapper settings, QuotaRefreshNotificationStateMapper notifications,
            CodexQuotaService quotas, NotificationSettingsService policy, FeishuWebhookNotifier notifier,
            ObjectMapper json, @Value("${wao.share.base-url:}") String baseUrl) {
        this.settings = settings; this.notifications = notifications; this.quotas = quotas;
        this.policy = policy; this.notifier = notifier; this.json = json;
        this.homeUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceFirst("/+$", "") + "/dashboard";
    }

    /** 列出真实采样账号，日期未补充时返回空值，不推算账单周期。 */
    public List<AccountMembership> list() {
        return quotas.listAccounts().stream().map(account -> {
            SystemSettingEntity stored = settings.selectById(storageKey(account.key()));
            return new AccountMembership(account, stored == null
                    ? new Membership(account.key(), null, null, "") : read(stored.getCfgValue()));
        }).toList();
    }

    /** 按修订号保存人工日期；多人修改冲突时保留服务端最新值并要求用户重新读取。 */
    @Transactional
    public Membership save(Membership request) {
        if (request == null || request.accountKey() == null || request.accountKey().length() > 255
                || quotas.listAccounts().stream().noneMatch(account -> account.key().equals(request.accountKey())))
            throw new BadRequestException("请选择已同步的 Codex 账号");
        String purchased = date(request.purchasedAt()), expires = date(request.proExpiresAt());
        if (purchased != null && LocalDate.parse(purchased).isAfter(LocalDate.now(ZONE)))
            throw new BadRequestException("购买日期不能晚于今天");
        String key = storageKey(request.accountKey());
        SystemSettingEntity existing = settings.selectById(key);
        String revision = existing == null ? "" : read(existing.getCfgValue()).revision();
        if (!revision.equals(request.revision() == null ? "" : request.revision())) throw conflict();
        Membership saved = new Membership(request.accountKey(), purchased, expires, UUID.randomUUID().toString());
        SystemSettingEntity entity = new SystemSettingEntity();
        entity.setCfgKey(key); entity.setCfgValue(write(saved)); entity.setUpdatedAt(LocalDateTime.now(ZONE));
        if (existing == null) {
            try { settings.insert(entity); } catch (DuplicateKeyException error) { throw conflict(); }
        } else if (settings.update(entity, new UpdateWrapper<SystemSettingEntity>().eq("cfg_key", key)
                .eq("cfg_value", existing.getCfgValue())) != 1) throw conflict();
        return saved;
    }

    /** 到期前三天至到期当天发送一次群提醒；失败保留待重试状态，账号离线不影响人工日期提醒。 */
    @Transactional
    public void sendDueReminders(LocalDate today) {
        if (!policy.allows(SystemPolicy::automation)) return;
        for (SystemSettingEntity entity : settings.selectList(new QueryWrapper<SystemSettingEntity>().likeRight("cfg_key", PREFIX))) {
            Membership membership = read(entity.getCfgValue());
            if (membership.proExpiresAt() == null) continue;
            LocalDate expiry = LocalDate.parse(membership.proExpiresAt());
            long days = ChronoUnit.DAYS.between(today, expiry);
            if (days < 0 || days > 3) continue;
            String key = "codex-expiry:" + digest(membership.accountKey()) + ":" + expiry;
            QuotaRefreshNotificationStateEntity state = notifications.selectForUpdate(key);
            if (state != null && "sent".equals(state.getSnapshotJson())) continue;
            LocalDateTime now = LocalDateTime.now(ZONE);
            if (state == null) {
                state = new QuotaRefreshNotificationStateEntity(); state.setSourceKey(key);
                state.setAccountLabel(membership.accountKey()); state.setSnapshotJson("pending");
                state.setCreatedAt(now); state.setUpdatedAt(now); notifications.insert(state);
            }
            var card = new FeishuWebhookNotifier.Card("Codex Pro 即将到期", "orange",
                    List.of(List.of("账号", membership.accountKey()), List.of("到期日期", expiry.toString())),
                    (days == 0 ? "今天到期。" : "距离到期还有 " + days + " 天。")
                            + "\n\n依据 WAO 中手动填写的 Pro 到期日提醒，请核对实际会员状态。",
                    homeUrl == null ? null : "查看会员信息", homeUrl);
            if (notifier.notifyCard(card)) {
                state.setSnapshotJson("sent"); state.setUpdatedAt(now); notifications.updateById(state);
            }
        }
    }

    private static ConflictException conflict() { return new ConflictException("日期已被其他人更新，请重新读取后再保存；当前输入仍保留。"); }
    private static String date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 2000 || parsed.getYear() > 2100) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (RuntimeException error) { throw new BadRequestException("请输入 2000 至 2100 年之间的有效日期"); }
    }
    private static String storageKey(String account) { return PREFIX + digest(account); }
    private static String digest(String account) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(account.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("账号标识生成失败", error); }
    }
    private String write(Membership value) {
        try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("会员日期保存失败", error); }
    }
    private Membership read(String value) {
        try { return json.readValue(value, Membership.class); } catch (Exception error) { throw new IllegalStateException("会员日期读取失败", error); }
    }
}
