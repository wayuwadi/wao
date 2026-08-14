package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.HolidayReminderDeliveryEntity;
import cn.wayuwa.wao.domain.HolidayReminderEntity;
import cn.wayuwa.wao.dto.HolidayCalendarDto;
import cn.wayuwa.wao.dto.HolidayEventDto;
import cn.wayuwa.wao.dto.HolidayReminderDto;
import cn.wayuwa.wao.dto.HolidaySettingsDto;
import cn.wayuwa.wao.dto.HolidayYearSourceDto;
import cn.wayuwa.wao.dto.InternationalHolidayCategoryDto;
import cn.wayuwa.wao.dto.SaveHolidayReminderRequest;
import cn.wayuwa.wao.dto.SaveHolidaySettingsRequest;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.HolidayReminderDeliveryMapper;
import cn.wayuwa.wao.mapper.HolidayReminderMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 成员级国际节日选择、节日详情和真实飞书提醒投递。 */
@Service
public class HolidayCalendarService {
    private static final String PREFERENCE_KEY = "holiday-international-categories";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final List<InternationalHolidayCategoryDto> AVAILABLE_CATEGORIES = List.of(
            new InternationalHolidayCategoryDto(HolidayService.FAMILY, "亲情关怀", "母亲节、父亲节"),
            new InternationalHolidayCategoryDto(HolidayService.WESTERN_POPULAR, "国际流行", "情人节、愚人节、万圣节"),
            new InternationalHolidayCategoryDto(HolidayService.CHRISTIAN, "基督教文化", "复活节、平安夜、圣诞节"),
            new InternationalHolidayCategoryDto(HolidayService.NORTH_AMERICA, "北美节日", "感恩节"));

    private final HolidayService holidays;
    private final MemberPreferenceMapper preferences;
    private final HolidayReminderMapper reminders;
    private final HolidayReminderDeliveryMapper deliveries;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final ObjectMapper json;

    public HolidayCalendarService(HolidayService holidays, MemberPreferenceMapper preferences,
            HolidayReminderMapper reminders, HolidayReminderDeliveryMapper deliveries,
            AccountMapper accounts, AccountIdentityMapper identities, SettingService settings,
            FeishuOpenApiClient feishu, ObjectMapper json) {
        this.holidays = holidays;
        this.preferences = preferences;
        this.reminders = reminders;
        this.deliveries = deliveries;
        this.accounts = accounts;
        this.identities = identities;
        this.settings = settings;
        this.feishu = feishu;
        this.json = json;
    }

    /** 返回当前成员真实选择范围内的事件、来源和提醒状态。 */
    public HolidayCalendarDto calendar(String memberId, LocalDate from, LocalDate to) {
        Set<String> selected = selectedCategories(memberId);
        Map<String, HolidayReminderEntity> byCode = new HashMap<>();
        reminders.selectList(new LambdaQueryWrapper<HolidayReminderEntity>()
                        .eq(HolidayReminderEntity::getMemberId, memberId))
                .forEach(reminder -> byCode.put(reminder.getHolidayCode(), reminder));
        List<HolidayEventDto> events = holidays.events(from, to, selected).stream().map(event -> {
            HolidayReminderEntity reminder = byCode.get(event.code());
            return reminder == null ? event : event.withReminder(Boolean.TRUE.equals(reminder.getEnabled()),
                    reminder.getReminderTime() == null ? null : minute(reminder.getReminderTime()));
        }).toList();
        List<HolidayYearSourceDto> years = java.util.stream.IntStream.rangeClosed(from.getYear(), to.getYear())
                .mapToObj(this::yearSource).toList();
        return new HolidayCalendarDto(events, years);
    }

    /** 读取当前成员的国际范围和飞书提醒通道状态；新成员默认不订阅任何国际类别。 */
    public HolidaySettingsDto settings(String memberId) {
        Channel channel = channel(memberId);
        return new HolidaySettingsDto(List.copyOf(selectedCategories(memberId)), AVAILABLE_CATEGORIES,
                channel.available(), channel.hint());
    }

    /** 保存主动选择范围；取消类别时同步关闭其中提醒，避免隐藏事件仍在发送。 */
    public HolidaySettingsDto saveSettings(String memberId, SaveHolidaySettingsRequest request) {
        LinkedHashSet<String> selected = new LinkedHashSet<>(request.selectedInternationalCategories());
        if (!HolidayService.INTERNATIONAL_CATEGORIES.containsAll(selected)) {
            throw new BadRequestException("包含不支持的国际节日类别");
        }
        try {
            preferences.upsert(memberId, PREFERENCE_KEY, json.writeValueAsString(selected));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存节日类别失败", exception);
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        reminders.selectList(new LambdaQueryWrapper<HolidayReminderEntity>()
                        .eq(HolidayReminderEntity::getMemberId, memberId)
                        .eq(HolidayReminderEntity::getEnabled, true))
                .forEach(reminder -> {
                    HolidayService.Definition definition = holidays.definition(reminder.getHolidayCode());
                    if (definition != null && definition.internationalCategory() != null
                            && !selected.contains(definition.internationalCategory())) {
                        reminder.setEnabled(false);
                        reminder.setUpdatedAt(now);
                        reminders.updateById(reminder);
                    }
                });
        return settings(memberId);
    }

    /** 创建、修改或关闭当前成员的年度节日提醒。 */
    public HolidayReminderDto saveReminder(String memberId, String holidayCode, SaveHolidayReminderRequest request) {
        HolidayService.Definition definition = holidays.definition(holidayCode);
        if (definition == null) throw new BadRequestException("无法识别该节日");
        if (request.enabled() && request.reminderTime() == null) {
            throw new BadRequestException("开启提醒后请选择提醒时间");
        }
        if (request.enabled() && definition.internationalCategory() != null
                && !selectedCategories(memberId).contains(definition.internationalCategory())) {
            throw new BadRequestException("请先在节日设置中订阅该国际节日类别");
        }
        Channel channel = channel(memberId);
        if (request.enabled() && !channel.available()) throw new BadRequestException(channel.hint());

        LocalDateTime now = LocalDateTime.now(ZONE);
        HolidayReminderEntity reminder = reminders.find(memberId, holidayCode);
        boolean creating = reminder == null;
        if (creating) {
            reminder = new HolidayReminderEntity();
            reminder.setId(id("hr"));
            reminder.setMemberId(memberId);
            reminder.setHolidayCode(holidayCode);
            reminder.setHolidayName(definition.name());
            reminder.setCreatedAt(now);
        }
        reminder.setReminderTime(request.enabled() ? request.reminderTime() : reminder.getReminderTime());
        reminder.setEnabled(request.enabled());
        reminder.setUpdatedAt(now);
        if (creating) reminders.insert(reminder);
        else reminders.updateById(reminder);
        return reminderDto(reminder);
    }

    /** 每分钟扫描到点提醒；同一节日同一自然日只会成功投递一次。 */
    public void sendDueReminders(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        Map<String, HolidayEventDto> today = new HashMap<>();
        holidays.allEvents(date, date).stream().filter(HolidayEventDto::remindable)
                .forEach(event -> today.put(event.code(), event));
        reminders.selectList(new LambdaQueryWrapper<HolidayReminderEntity>()
                        .eq(HolidayReminderEntity::getEnabled, true)
                        .le(HolidayReminderEntity::getReminderTime, now.toLocalTime()))
                .forEach(reminder -> {
                    HolidayEventDto event = today.get(reminder.getHolidayCode());
                    if (event != null && reminderReady(reminder, date, now)) deliver(reminder, event, now);
                });
    }

    private void deliver(HolidayReminderEntity reminder, HolidayEventDto event, LocalDateTime now) {
        HolidayReminderDeliveryEntity delivery = findDelivery(reminder.getId(), now.toLocalDate());
        if (delivery == null) {
            delivery = new HolidayReminderDeliveryEntity();
            delivery.setId(id("hd"));
            delivery.setReminderId(reminder.getId());
            delivery.setEventDate(now.toLocalDate());
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
            var account = accounts.findByMemberId(reminder.getMemberId());
            var identity = account == null ? null : identities.findByAccountProvider(account.getId(), "feishu");
            String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
            if (identity == null || !StringUtils.hasText(appId) || !StringUtils.hasText(secret)) {
                throw new IllegalStateException(channel(reminder.getMemberId()).hint());
            }
            feishu.sendDirectCard(feishu.tenantAccessToken(appId, secret), identity.getProviderSubject(),
                    "节日提醒 · " + event.name(),
                    "今天是 " + event.date() + "（" + event.name() + "）。你曾在 WAO 为这个节日开启提醒。",
                    FeishuAppLinks.SITE + "/calendar");
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

    private boolean reminderReady(HolidayReminderEntity reminder, LocalDate date, LocalDateTime now) {
        HolidayReminderDeliveryEntity row = findDelivery(reminder.getId(), date);
        if (row == null) return true;
        if ("SENT".equals(row.getStatus())) return false;
        return row.getAttempts() < 6 && row.getAttemptedAt().isBefore(now.minusMinutes(5));
    }

    private HolidayReminderDeliveryEntity findDelivery(String reminderId, LocalDate date) {
        return deliveries.selectOne(new LambdaQueryWrapper<HolidayReminderDeliveryEntity>()
                .eq(HolidayReminderDeliveryEntity::getReminderId, reminderId)
                .eq(HolidayReminderDeliveryEntity::getEventDate, date).last("LIMIT 1"));
    }

    private Set<String> selectedCategories(String memberId) {
        String raw = preferences.find(memberId, PREFERENCE_KEY);
        if (!StringUtils.hasText(raw)) return Set.of();
        try {
            LinkedHashSet<String> selected = new LinkedHashSet<>(json.readValue(raw, STRING_LIST));
            selected.retainAll(HolidayService.INTERNATIONAL_CATEGORIES);
            return selected;
        } catch (JsonProcessingException exception) {
            return Set.of();
        }
    }

    private Channel channel(String memberId) {
        var account = accounts.findByMemberId(memberId);
        if (account == null || identities.findByAccountProvider(account.getId(), "feishu") == null) {
            return new Channel(false, "当前账号尚未绑定飞书，无法开启个人节日提醒");
        }
        if (!StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_ID, ""))
                || !StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_SECRET, ""))) {
            return new Channel(false, "飞书自建应用尚未配置，无法开启个人节日提醒");
        }
        return new Channel(true, "提醒将按上海时区私聊当前账号绑定的飞书身份");
    }

    private HolidayYearSourceDto yearSource(int year) {
        boolean available = holidays.officialScheduleAvailable(year);
        return new HolidayYearSourceDto(year, available,
                available ? year + "年国务院办公厅节假日安排" : "lunar 1.7.7 历法数据",
                available ? holidays.officialSourceUrl(year) : "https://github.com/6tail/lunar-java",
                available ? "该年度法定放假与调休安排已收录"
                        : "该年度法定放假与调休安排尚未收录，仅显示可由历法确定的节日本日");
    }

    private static HolidayReminderDto reminderDto(HolidayReminderEntity reminder) {
        return new HolidayReminderDto(reminder.getHolidayCode(), Boolean.TRUE.equals(reminder.getEnabled()),
                reminder.getReminderTime() == null ? null : minute(reminder.getReminderTime()));
    }

    private static String minute(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private record Channel(boolean available, String hint) { }
}
