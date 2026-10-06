package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.FamilyImportantDateDeliveryEntity;
import cn.wayuwa.wao.domain.FamilyImportantDateEntity;
import cn.wayuwa.wao.dto.CalendarDateConversionDto;
import cn.wayuwa.wao.dto.FamilyImportantDateCalendarDto;
import cn.wayuwa.wao.dto.FamilyImportantDateDto;
import cn.wayuwa.wao.dto.FamilyImportantDateEventDto;
import cn.wayuwa.wao.dto.SaveFamilyImportantDateRequest;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FamilyImportantDateDeliveryMapper;
import cn.wayuwa.wao.mapper.FamilyImportantDateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
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

/** 日历提醒事项的成员级维护、阴阳历年度展开与真实飞书投递。 */
@Service
public class FamilyImportantDateService {
    @org.springframework.beans.factory.annotation.Autowired
    private InternalNotificationService internalNotifications;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final FamilyImportantDateMapper dates;
    private final FamilyImportantDateDeliveryMapper deliveries;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final NotificationSettingsService notificationSettings;

    public FamilyImportantDateService(FamilyImportantDateMapper dates,
            FamilyImportantDateDeliveryMapper deliveries, AccountMapper accounts,
            AccountIdentityMapper identities, SettingService settings, FeishuOpenApiClient feishu,
            NotificationSettingsService notificationSettings) {
        this.dates = dates;
        this.deliveries = deliveries;
        this.accounts = accounts;
        this.identities = identities;
        this.settings = settings;
        this.feishu = feishu;
        this.notificationSettings = notificationSettings;
    }

    /** 返回当前成员的全部维护项和指定日期窗内按公历或农历展开的事件。 */
    public FamilyImportantDateCalendarDto calendar(String memberId, LocalDate from, LocalDate to) {
        List<FamilyImportantDateEntity> entities = dates.selectList(
                new LambdaQueryWrapper<FamilyImportantDateEntity>()
                        .eq(FamilyImportantDateEntity::getMemberId, memberId)
                        .orderByAsc(FamilyImportantDateEntity::getEventDate)
                        .orderByAsc(FamilyImportantDateEntity::getName));
        List<FamilyImportantDateEventDto> events = new ArrayList<>();
        for (FamilyImportantDateEntity entity : entities) {
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (matchesOccurrence(entity, day)) events.add(eventDto(entity, day));
            }
        }
        events.sort(Comparator.comparing(FamilyImportantDateEventDto::date)
                .thenComparing(FamilyImportantDateEventDto::name));
        Channel channel = directChannel(memberId);
        Channel group = groupChannel(memberId);
        return new FamilyImportantDateCalendarDto(entities.stream().map(FamilyImportantDateService::dto).toList(),
                events, channel.available(), channel.hint(), group.available(), group.hint());
    }

    /** 把一个公历日期转换为同一天的农历信息。 */
    public CalendarDateConversionDto fromSolar(LocalDate date) {
        if (date == null) throw new BadRequestException("请选择公历日期");
        Lunar lunar = Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth()).getLunar();
        return conversion(date, lunar);
    }

    /** 把一个农历日期转换为同一天的公历信息；闰月不存在或日期越界时明确拒绝。 */
    public CalendarDateConversionDto fromLunar(int year, int month, int day, boolean leapMonth) {
        if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 30) {
            throw new BadRequestException("农历日期超出支持范围（1900 至 2100 年）");
        }
        try {
            Lunar lunar = Lunar.fromYmd(year, leapMonth ? -month : month, day);
            Solar solar = lunar.getSolar();
            if (lunar.getYear() != year || Math.abs(lunar.getMonth()) != month || lunar.getDay() != day
                    || (lunar.getMonth() < 0) != leapMonth) {
                throw new BadRequestException("所选农历日期在该年不存在");
            }
            return conversion(LocalDate.of(solar.getYear(), solar.getMonth(), solar.getDay()), lunar);
        } catch (BadRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BadRequestException("所选农历日期在该年不存在");
        }
    }

    /** 新建当前成员的日历提醒事项；通道不可用时仍允许关闭提醒后保存。 */
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

    /** 修改当前成员拥有的日历提醒事项，拒绝跨成员访问。 */
    public FamilyImportantDateDto update(String memberId, String id, SaveFamilyImportantDateRequest request) {
        FamilyImportantDateEntity entity = owned(memberId, id);
        validate(memberId, request);
        apply(entity, request);
        entity.setUpdatedAt(LocalDateTime.now(ZONE));
        dates.updateById(entity);
        return dto(entity);
    }

    /** 删除当前成员拥有的日历提醒事项及其历史投递记录。 */
    public void delete(String memberId, String id) {
        FamilyImportantDateEntity entity = owned(memberId, id);
        dates.deleteById(entity.getId());
    }

    /** 每分钟扫描到点的日历提醒；失败五分钟后重试，最多六次。 */
    public void sendDueReminders(LocalDateTime now) {
        LocalDate reminderDate = now.toLocalDate();
        dates.selectList(new LambdaQueryWrapper<FamilyImportantDateEntity>()
                        .eq(FamilyImportantDateEntity::getReminderEnabled, true)
                        .le(FamilyImportantDateEntity::getReminderTime, now.toLocalTime()))
                .forEach(entity -> {
                    LocalDate occurrence = reminderDate.plusDays(entity.getReminderDaysBefore());
                    if (matchesOccurrence(entity, occurrence)
                            && deliveryReady(entity, occurrence, now)) {
                        deliver(entity, occurrence, now);
                    }
                });
    }

    private void validate(String memberId, SaveFamilyImportantDateRequest request) {
        if (request.eventKind() != null && !List.of("IMPORTANT", "BIRTHDAY", "ANNIVERSARY").contains(request.eventKind()))
            throw new BadRequestException("请选择有效的事项类型");
        if (request.birthdaySubject() != null && !List.of("SELF", "FAMILY", "OTHER").contains(request.birthdaySubject()))
            throw new BadRequestException("请选择有效的生日对象");
        String calendarType = calendarType(request.calendarType());
        if ("LUNAR".equals(calendarType)) {
            if (request.lunarMonth() == null || request.lunarDay() == null) {
                throw new BadRequestException("农历提醒事项需要填写农历月和日");
            }
            CalendarDateConversionDto converted = fromLunar(request.eventDate().getYear(),
                    request.lunarMonth(), request.lunarDay(), request.lunarLeapMonth());
            if (!converted.solarDate().equals(request.eventDate().toString())) {
                Lunar source = Solar.fromYmd(request.eventDate().getYear(), request.eventDate().getMonthValue(),
                        request.eventDate().getDayOfMonth()).getLunar();
                if (Math.abs(source.getMonth()) != request.lunarMonth() || source.getDay() != request.lunarDay()
                        || (source.getMonth() < 0) != request.lunarLeapMonth()) {
                    throw new BadRequestException("农历和公历日期不对应，请重新转换后保存");
                }
            }
        }
        if (request.reminderEnabled() && request.reminderTime() == null) {
            throw new BadRequestException("开启提醒后请选择提醒时间");
        }
        String notificationType = notificationType(request.notificationType());
        Channel reminderChannel = request.reminderEnabled()
                ? "GROUP".equals(notificationType) ? groupChannel(memberId) : directChannel(memberId) : null;
        if (reminderChannel != null && !reminderChannel.available()) {
            throw new BadRequestException(reminderChannel.hint());
        }
    }

    private FamilyImportantDateEntity owned(String memberId, String id) {
        FamilyImportantDateEntity entity = dates.findOwned(id, memberId);
        if (entity == null) throw new NotFoundException("日历提醒事项不存在或已删除");
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
            if (("SENT".equals(delivery.getStatus()) || "SKIPPED".equals(delivery.getStatus()))
                    || delivery.getAttempts() >= 6
                    || !delivery.getAttemptedAt().isBefore(now.minusMinutes(5))) return;
            int nextAttempts = delivery.getAttempts() + 1;
            if (deliveries.claimRetry(delivery.getId(), delivery.getAttemptedAt(), now, nextAttempts) == 0) return;
            delivery.setAttempts(nextAttempts);
            delivery.setAttemptedAt(now);
            delivery.setStatus("PROCESSING");
        }
        try {
            if ("GROUP".equals(notificationType(entity.getNotificationType()))
                    && !notificationSettings.allowsReminders()) {
                delivery.setStatus("SKIPPED");
                delivery.setErrorMessage(notificationSettings.blockedReason());
                deliveries.updateById(delivery);
                return;
            }
            String when = entity.getReminderDaysBefore() == 0 ? "今天" : entity.getReminderDaysBefore() + " 天后";
            String note = StringUtils.hasText(entity.getNote()) ? "\n\n备注：" + entity.getNote() : "";
            String body = when + "是 " + occurrence + "（" + entity.getName() + "）。" + note;
            if ("GROUP".equals(notificationType(entity.getNotificationType()))) {
                internalNotifications.publishToMember(entity.getMemberId(), new FeishuWebhookNotifier.Card(
                        "日历提醒 · " + entity.getName(), "orange", List.of(), body,
                        "打开 WAO 日历", FeishuAppLinks.SITE + "/calendar"));
            } else {
                var account = accounts.findByMemberId(entity.getMemberId());
                var identity = account == null ? null : identities.findByAccountProvider(account.getId(), "feishu");
                String appId = appId();
                String secret = appSecret();
                if (identity == null || !StringUtils.hasText(appId) || !StringUtils.hasText(secret)) {
                    throw new IllegalStateException(directChannel(entity.getMemberId()).hint());
                }
                feishu.sendDirectCard(feishu.tenantAccessToken(appId, secret), identity.getProviderSubject(),
                        "日历提醒 · " + entity.getName(), body,
                        FeishuAppLinks.SITE + "/calendar", "打开 WAO 日历");
            }
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
        if ("SENT".equals(delivery.getStatus()) || "SKIPPED".equals(delivery.getStatus())) return false;
        return delivery.getAttempts() < 6 && delivery.getAttemptedAt().isBefore(now.minusMinutes(5));
    }

    private FamilyImportantDateDeliveryEntity findDelivery(String id, LocalDate occurrence) {
        return deliveries.selectOne(new LambdaQueryWrapper<FamilyImportantDateDeliveryEntity>()
                .eq(FamilyImportantDateDeliveryEntity::getImportantDateId, id)
                .eq(FamilyImportantDateDeliveryEntity::getOccurrenceDate, occurrence).last("LIMIT 1"));
    }

    private Channel directChannel(String memberId) {
        var account = accounts.findByMemberId(memberId);
        if (account == null || identities.findByAccountProvider(account.getId(), "feishu") == null) {
            return new Channel(false, "当前账号尚未绑定飞书；事项仍可保存到日历，但不能开启飞书提醒");
        }
        if (!StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_ID, ""))
                || !StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_SECRET, ""))) {
            return new Channel(false, "飞书自建应用尚未配置；事项仍可保存到日历，但不能开启飞书提醒");
        }
        return new Channel(true, "将按上海时区私聊当前账号绑定的飞书身份");
    }

    private Channel groupChannel(String memberId) {
        return new Channel(true, "提醒将保存到 WAO 通知中心，仅自己可见");
    }

    private static void apply(FamilyImportantDateEntity entity, SaveFamilyImportantDateRequest request) {
        entity.setName(request.name().trim());
        entity.setEventDate(request.eventDate());
        entity.setCalendarType(calendarType(request.calendarType()));
        entity.setLunarMonth("LUNAR".equals(entity.getCalendarType()) ? request.lunarMonth() : null);
        entity.setLunarDay("LUNAR".equals(entity.getCalendarType()) ? request.lunarDay() : null);
        entity.setLunarLeapMonth("LUNAR".equals(entity.getCalendarType()) && request.lunarLeapMonth());
        entity.setNote(StringUtils.hasText(request.note()) ? request.note().trim() : null);
        entity.setEventKind(request.eventKind() != null ? request.eventKind()
                : entity.getEventKind() != null ? entity.getEventKind() : "IMPORTANT");
        entity.setBirthdaySubject("BIRTHDAY".equals(entity.getEventKind())
                ? request.birthdaySubject() != null ? request.birthdaySubject()
                    : entity.getBirthdaySubject() != null ? entity.getBirthdaySubject() : "OTHER"
                : "OTHER");
        entity.setHomeReminderEnabled(request.homeReminderEnabled() != null ? request.homeReminderEnabled()
                : entity.getHomeReminderEnabled() == null || entity.getHomeReminderEnabled());
        entity.setReminderEnabled(request.reminderEnabled());
        entity.setReminderDaysBefore(request.reminderDaysBefore());
        entity.setReminderTime(request.reminderEnabled() ? request.reminderTime() : null);
        entity.setNotificationType(notificationType(request.notificationType()));
        entity.setChatId(null);
        entity.setChatName("GROUP".equals(entity.getNotificationType()) ? "WAO 通知中心" : null);
    }

    static boolean matchesOccurrence(FamilyImportantDateEntity entity, LocalDate day) {
        if (day.isBefore(entity.getEventDate())) return false;
        if (!"LUNAR".equals(calendarType(entity.getCalendarType()))) {
            return day.getMonthValue() == entity.getEventDate().getMonthValue()
                    && day.getDayOfMonth() == entity.getEventDate().getDayOfMonth();
        }
        if (entity.getLunarMonth() == null || entity.getLunarDay() == null) return false;
        Lunar lunar = Solar.fromYmd(day.getYear(), day.getMonthValue(), day.getDayOfMonth()).getLunar();
        return Math.abs(lunar.getMonth()) == entity.getLunarMonth()
                && lunar.getDay() == entity.getLunarDay()
                && (lunar.getMonth() < 0) == Boolean.TRUE.equals(entity.getLunarLeapMonth());
    }

    private static FamilyImportantDateDto dto(FamilyImportantDateEntity entity) {
        return new FamilyImportantDateDto(entity.getId(), entity.getName(), entity.getEventDate().toString(),
                entity.getNote(), Boolean.TRUE.equals(entity.getReminderEnabled()), entity.getReminderDaysBefore(),
                minute(entity.getReminderTime()), calendarType(entity.getCalendarType()), entity.getLunarMonth(),
                entity.getLunarDay(), Boolean.TRUE.equals(entity.getLunarLeapMonth()),
                entity.getEventDate().toString(), lunarLabel(entity.getEventDate()),
                notificationType(entity.getNotificationType()), entity.getChatId(), entity.getChatName(),
                entity.getEventKind() == null ? "IMPORTANT" : entity.getEventKind(),
                entity.getBirthdaySubject() == null ? "OTHER" : entity.getBirthdaySubject(),
                !Boolean.FALSE.equals(entity.getHomeReminderEnabled()));
    }

    private static FamilyImportantDateEventDto eventDto(FamilyImportantDateEntity entity, LocalDate occurrence) {
        return new FamilyImportantDateEventDto("family-date-" + entity.getId() + '-' + occurrence,
                entity.getId(), occurrence.toString(), entity.getName(), entity.getNote(),
                Boolean.TRUE.equals(entity.getReminderEnabled()), entity.getReminderDaysBefore(),
                minute(entity.getReminderTime()), calendarType(entity.getCalendarType()), occurrence.toString(),
                lunarLabel(occurrence), notificationType(entity.getNotificationType()), entity.getChatName());
    }

    private static CalendarDateConversionDto conversion(LocalDate solarDate, Lunar lunar) {
        return new CalendarDateConversionDto(solarDate.toString(), lunar.getYear(), Math.abs(lunar.getMonth()),
                lunar.getDay(), lunar.getMonth() < 0, solarDate.toString(), lunarLabel(lunar));
    }

    private static String lunarLabel(LocalDate solarDate) {
        return lunarLabel(Solar.fromYmd(solarDate.getYear(), solarDate.getMonthValue(),
                solarDate.getDayOfMonth()).getLunar());
    }

    private static String lunarLabel(Lunar lunar) {
        return "农历" + lunar.getYearInChinese() + "年" + (lunar.getMonth() < 0 ? "闰" : "")
                + lunar.getMonthInChinese() + "月" + lunar.getDayInChinese();
    }

    private String appId() { return settings.get(SettingService.CHAT_SYNC_APP_ID, ""); }

    private String appSecret() { return settings.get(SettingService.CHAT_SYNC_APP_SECRET, ""); }

    private static String calendarType(String value) {
        return "LUNAR".equalsIgnoreCase(value) ? "LUNAR" : "SOLAR";
    }

    private static String notificationType(String value) {
        return "GROUP".equalsIgnoreCase(value) ? "GROUP" : "DIRECT";
    }

    private static String minute(LocalTime time) {
        return time == null ? null : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private record Channel(boolean available, String hint) { }
}
