package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.FamilyImportantDateDeliveryEntity;
import cn.wayuwa.wao.domain.FamilyImportantDateEntity;
import cn.wayuwa.wao.dto.FamilyImportantDateCalendarDto;
import cn.wayuwa.wao.dto.FamilyImportantDateDto;
import cn.wayuwa.wao.dto.FamilyImportantDateEventDto;
import cn.wayuwa.wao.dto.SaveFamilyImportantDateRequest;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FamilyImportantDateDeliveryMapper;
import cn.wayuwa.wao.mapper.FamilyImportantDateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 家庭重要日的成员级维护、年度日历展开与真实飞书提醒投递。 */
@Service
public class FamilyImportantDateService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final FamilyImportantDateMapper dates;
    private final FamilyImportantDateDeliveryMapper deliveries;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;

    public FamilyImportantDateService(FamilyImportantDateMapper dates,
            FamilyImportantDateDeliveryMapper deliveries, AccountMapper accounts,
            AccountIdentityMapper identities, SettingService settings, FeishuOpenApiClient feishu) {
        this.dates = dates;
        this.deliveries = deliveries;
        this.accounts = accounts;
        this.identities = identities;
        this.settings = settings;
        this.feishu = feishu;
    }

    /** 返回当前成员的全部维护项和指定日期窗内按年展开的日历事件。 */
    public FamilyImportantDateCalendarDto calendar(String memberId, LocalDate from, LocalDate to) {
        List<FamilyImportantDateEntity> entities = dates.selectList(
                new LambdaQueryWrapper<FamilyImportantDateEntity>()
                        .eq(FamilyImportantDateEntity::getMemberId, memberId)
                        .orderByAsc(FamilyImportantDateEntity::getEventDate)
                        .orderByAsc(FamilyImportantDateEntity::getName));
        List<FamilyImportantDateEventDto> events = new ArrayList<>();
        for (FamilyImportantDateEntity entity : entities) {
            for (int year = Math.max(from.getYear(), entity.getEventDate().getYear());
                    year <= to.getYear(); year++) {
                LocalDate occurrence = occurrence(entity.getEventDate(), year);
                if (occurrence != null && !occurrence.isBefore(from) && !occurrence.isAfter(to)) {
                    events.add(eventDto(entity, occurrence));
                }
            }
        }
        events.sort(Comparator.comparing(FamilyImportantDateEventDto::date)
                .thenComparing(FamilyImportantDateEventDto::name));
        Channel channel = channel(memberId);
        return new FamilyImportantDateCalendarDto(entities.stream().map(FamilyImportantDateService::dto).toList(),
                events, channel.available(), channel.hint());
    }

    /** 新建当前成员的家庭重要日；飞书通道不可用时仍允许关闭提醒后保存到日历。 */
    public FamilyImportantDateDto create(String memberId, SaveFamilyImportantDateRequest request) {
        validate(memberId, request);
        LocalDateTime now = LocalDateTime.now(ZONE);
        FamilyImportantDateEntity entity = new FamilyImportantDateEntity();
        entity.setId(id("fid"));
        entity.setMemberId(memberId);
        apply(entity, request);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        dates.insert(entity);
        return dto(entity);
    }

    /** 修改当前成员拥有的家庭重要日，拒绝跨成员访问。 */
    public FamilyImportantDateDto update(String memberId, String id, SaveFamilyImportantDateRequest request) {
        FamilyImportantDateEntity entity = owned(memberId, id);
        validate(memberId, request);
        apply(entity, request);
        entity.setUpdatedAt(LocalDateTime.now(ZONE));
        dates.updateById(entity);
        return dto(entity);
    }

    /** 删除当前成员拥有的家庭重要日及其历史投递记录。 */
    public void delete(String memberId, String id) {
        FamilyImportantDateEntity entity = owned(memberId, id);
        dates.deleteById(entity.getId());
    }

    /** 每分钟扫描到点的重要日提醒；失败五分钟后重试，最多六次。 */
    public void sendDueReminders(LocalDateTime now) {
        LocalDate reminderDate = now.toLocalDate();
        dates.selectList(new LambdaQueryWrapper<FamilyImportantDateEntity>()
                        .eq(FamilyImportantDateEntity::getReminderEnabled, true)
                        .le(FamilyImportantDateEntity::getReminderTime, now.toLocalTime()))
                .forEach(entity -> {
                    LocalDate occurrence = occurrence(entity.getEventDate(),
                            reminderDate.plusDays(entity.getReminderDaysBefore()).getYear());
                    if (occurrence != null && !occurrence.isBefore(entity.getEventDate())
                            && occurrence.minusDays(entity.getReminderDaysBefore()).equals(reminderDate)
                            && deliveryReady(entity, occurrence, now)) {
                        deliver(entity, occurrence, now);
                    }
                });
    }

    private void validate(String memberId, SaveFamilyImportantDateRequest request) {
        if (request.reminderEnabled() && request.reminderTime() == null) {
            throw new BadRequestException("开启飞书提醒后请选择提醒时间");
        }
        Channel reminderChannel = request.reminderEnabled() ? channel(memberId) : null;
        if (reminderChannel != null && !reminderChannel.available()) {
            throw new BadRequestException(reminderChannel.hint());
        }
    }

    private FamilyImportantDateEntity owned(String memberId, String id) {
        FamilyImportantDateEntity entity = dates.findOwned(id, memberId);
        if (entity == null) throw new NotFoundException("家庭重要日不存在或已删除");
        return entity;
    }

    private void deliver(FamilyImportantDateEntity entity, LocalDate occurrence, LocalDateTime now) {
        FamilyImportantDateDeliveryEntity delivery = findDelivery(entity.getId(), occurrence);
        if (delivery == null) {
            delivery = new FamilyImportantDateDeliveryEntity();
            delivery.setId(id("fid_d"));
            delivery.setImportantDateId(entity.getId());
            delivery.setOccurrenceDate(occurrence);
            delivery.setAttempts(1);
            delivery.setAttemptedAt(now);
            delivery.setStatus("PROCESSING");
            try {
                deliveries.insert(delivery);
            } catch (DuplicateKeyException ignored) {
                return;
            }
        } else {
            if ("SENT".equals(delivery.getStatus()) || delivery.getAttempts() >= 6
                    || !delivery.getAttemptedAt().isBefore(now.minusMinutes(5))) return;
            int nextAttempts = delivery.getAttempts() + 1;
            if (deliveries.claimRetry(delivery.getId(), delivery.getAttemptedAt(), now, nextAttempts) == 0) return;
            delivery.setAttempts(nextAttempts);
            delivery.setAttemptedAt(now);
            delivery.setStatus("PROCESSING");
        }
        try {
            var account = accounts.findByMemberId(entity.getMemberId());
            var identity = account == null ? null : identities.findByAccountProvider(account.getId(), "feishu");
            String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
            if (identity == null || !StringUtils.hasText(appId) || !StringUtils.hasText(secret)) {
                throw new IllegalStateException(channel(entity.getMemberId()).hint());
            }
            String when = entity.getReminderDaysBefore() == 0
                    ? "今天" : entity.getReminderDaysBefore() + " 天后";
            String note = StringUtils.hasText(entity.getNote()) ? "\n\n备注：" + entity.getNote() : "";
            feishu.sendDirectCard(feishu.tenantAccessToken(appId, secret), identity.getProviderSubject(),
                    "家庭重要日 · " + entity.getName(),
                    when + "是 " + occurrence + "（" + entity.getName() + "）。" + note,
                    FeishuAppLinks.SITE + "/calendar", "打开 WAO 日历");
            delivery.setStatus("SENT");
            delivery.setSentAt(now);
            delivery.setErrorMessage(null);
        } catch (RuntimeException exception) {
            delivery.setStatus("FAILED");
            String message = exception.getMessage() == null ? "提醒发送失败" : exception.getMessage();
            delivery.setErrorMessage(message.substring(0, Math.min(message.length(), 512)));
        }
        deliveries.updateById(delivery);
    }

    private boolean deliveryReady(FamilyImportantDateEntity entity, LocalDate occurrence, LocalDateTime now) {
        FamilyImportantDateDeliveryEntity delivery = findDelivery(entity.getId(), occurrence);
        if (delivery == null) return true;
        if ("SENT".equals(delivery.getStatus())) return false;
        return delivery.getAttempts() < 6 && delivery.getAttemptedAt().isBefore(now.minusMinutes(5));
    }

    private FamilyImportantDateDeliveryEntity findDelivery(String id, LocalDate occurrence) {
        return deliveries.selectOne(new LambdaQueryWrapper<FamilyImportantDateDeliveryEntity>()
                .eq(FamilyImportantDateDeliveryEntity::getImportantDateId, id)
                .eq(FamilyImportantDateDeliveryEntity::getOccurrenceDate, occurrence).last("LIMIT 1"));
    }

    private Channel channel(String memberId) {
        var account = accounts.findByMemberId(memberId);
        if (account == null || identities.findByAccountProvider(account.getId(), "feishu") == null) {
            return new Channel(false, "当前账号尚未绑定飞书；重要日仍可保存到日历，但不能开启飞书提醒");
        }
        if (!StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_ID, ""))
                || !StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_SECRET, ""))) {
            return new Channel(false, "飞书自建应用尚未配置；重要日仍可保存到日历，但不能开启飞书提醒");
        }
        return new Channel(true, "将按上海时区私聊当前账号绑定的飞书身份");
    }

    private static void apply(FamilyImportantDateEntity entity, SaveFamilyImportantDateRequest request) {
        entity.setName(request.name().trim());
        entity.setEventDate(request.eventDate());
        entity.setNote(StringUtils.hasText(request.note()) ? request.note().trim() : null);
        entity.setReminderEnabled(request.reminderEnabled());
        entity.setReminderDaysBefore(request.reminderDaysBefore());
        entity.setReminderTime(request.reminderEnabled() ? request.reminderTime() : null);
    }

    private static LocalDate occurrence(LocalDate source, int year) {
        try {
            return LocalDate.of(year, source.getMonthValue(), source.getDayOfMonth());
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static FamilyImportantDateDto dto(FamilyImportantDateEntity entity) {
        return new FamilyImportantDateDto(entity.getId(), entity.getName(), entity.getEventDate().toString(),
                entity.getNote(), Boolean.TRUE.equals(entity.getReminderEnabled()), entity.getReminderDaysBefore(),
                minute(entity.getReminderTime()));
    }

    private static FamilyImportantDateEventDto eventDto(FamilyImportantDateEntity entity, LocalDate occurrence) {
        return new FamilyImportantDateEventDto("family-date-" + entity.getId() + '-' + occurrence.getYear(),
                entity.getId(), occurrence.toString(), entity.getName(), entity.getNote(),
                Boolean.TRUE.equals(entity.getReminderEnabled()), entity.getReminderDaysBefore(),
                minute(entity.getReminderTime()));
    }

    private static String minute(LocalTime time) {
        return time == null ? null : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private record Channel(boolean available, String hint) { }
}
