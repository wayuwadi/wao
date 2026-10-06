package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.FamilyImportantDateEntity;
import cn.wayuwa.wao.mapper.FamilyImportantDateMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 上海日期驱动的成员私有首页提醒；短租约避免多设备重复展示，确认不推进通知中心水位。 */
@Service
public class HomeDateReminderService {
    public static final String SETTINGS_KEY = "home-date-settings";
    public static final String DELIVERY_PREFIX = "home-date:";
    private final FamilyImportantDateMapper dates;
    private final MemberPreferenceMapper preferences;
    private final HolidayService holidays;
    private final ObjectMapper json;
    private final Clock clock;

    public record Settings(boolean enabled, boolean solarTerms, long revision) { }
    public record Notice(String key, String kind, String subject, String name, String date,
            int daysBefore, String title, String detail, String targetPath, int priority) { }
    public record Queue(String date, List<Notice> notices) { }
    public record Reservation(String date, String token, List<Notice> notices) { }

    @org.springframework.beans.factory.annotation.Autowired
    public HomeDateReminderService(FamilyImportantDateMapper dates, MemberPreferenceMapper preferences,
            HolidayService holidays, ObjectMapper json) {
        this(dates, preferences, holidays, json, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    HomeDateReminderService(FamilyImportantDateMapper dates, MemberPreferenceMapper preferences,
            HolidayService holidays, ObjectMapper json, Clock clock) {
        this.dates = dates;
        this.preferences = preferences;
        this.holidays = holidays;
        this.json = json;
        this.clock = clock;
    }

    /** 读取当前成员的独立首页渠道配置；未设置时开启轻提醒和节气。 */
    public Settings settings(String member) {
        requireMember(member);
        return decode(preferences.find(member, SETTINGS_KEY));
    }

    /** 条件保存账号配置，拒绝覆盖另一设备的新版本。 */
    public Settings saveSettings(String member, Settings request) {
        requireMember(member);
        String raw = preferences.find(member, SETTINGS_KEY);
        Settings current = decode(raw);
        if (current.enabled() == request.enabled() && current.solarTerms() == request.solarTerms()) return current;
        if (request.revision() != current.revision()) throw conflict();
        Settings next = new Settings(request.enabled(), request.solarTerms(), current.revision() + 1);
        String value;
        try { value = json.writeValueAsString(next); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
        int changed = raw == null ? preferences.insertIfAbsent(member, SETTINGS_KEY, value)
                : preferences.compareAndSet(member, SETTINGS_KEY, raw, value);
        if (changed != 1) throw conflict();
        return next;
    }

    /** 只返回今天到达提醒阶段且尚未展示的真实日期事件，读取不记已读。 */
    public Queue queue(String member) {
        LocalDate today = LocalDate.now(clock);
        return new Queue(today.toString(), candidates(member, today).stream()
                .filter(notice -> available(preferences.find(member, storageKey(today, notice.key())))).toList());
    }

    /** 在即将展示时逐项争取二十秒租约；源已修改、跨日或其他设备已展示时不取得。 */
    public Reservation reserve(String member, String date, List<String> keys) {
        LocalDate today = currentDate(date);
        String token = UUID.randomUUID().toString();
        String reserved = "RESERVED:" + token + ':' + (clock.millis() + 20_000);
        List<Notice> claimed = new ArrayList<>();
        for (Notice notice : candidates(member, today)) {
            if (!keys.contains(notice.key())) continue;
            String key = storageKey(today, notice.key());
            String current = preferences.find(member, key);
            if (!available(current)) continue;
            int result = current == null ? preferences.insertIfAbsent(member, key, reserved)
                    : preferences.compareAndSet(member, key, current, reserved);
            if (result == 1) claimed.add(notice);
        }
        return new Reservation(date, token, claimed);
    }

    /** 实际可见后记录展示；明确确认独立保存，不完成日历事项，也不清空其他通知。 */
    @org.springframework.transaction.annotation.Transactional
    public void record(String member, String date, List<String> keys, String token, String state) {
        LocalDate today = currentDate(date);
        if (!List.of("SHOWN", "CONFIRMED", "RELEASED").contains(state))
            throw new BadRequestException("无效的提醒展示状态");
        List<String> valid = candidates(member, today).stream().map(Notice::key).toList();
        for (String noticeKey : keys) {
            String key = storageKey(today, noticeKey);
            String current = preferences.find(member, key);
            boolean reserved = current != null && current.startsWith("RESERVED:" + token + ':');
            boolean shown = ("SHOWN:" + token).equals(current);
            boolean confirmed = ("CONFIRMED:" + token).equals(current);
            if (confirmed || (shown && "SHOWN".equals(state))) continue;
            if ("RELEASED".equals(state)) {
                if (reserved) preferences.compareAndSet(member, key, current, "");
                continue;
            }
            if (!valid.contains(noticeKey) || (!reserved && !shown) || (reserved && available(current)))
                throw new ConflictException("提醒来源或展示租约已变化，请重新读取");
            if (preferences.compareAndSet(member, key, current, state + ':' + token) != 1)
                throw new ConflictException("提醒状态已由另一设备更新");
        }
    }

    private List<Notice> candidates(String member, LocalDate today) {
        Settings settings = settings(member);
        if (!settings.enabled()) return List.of();
        List<Notice> notices = new ArrayList<>();
        List<FamilyImportantDateEntity> items = dates.selectList(new LambdaQueryWrapper<FamilyImportantDateEntity>()
                .eq(FamilyImportantDateEntity::getMemberId, member));
        for (FamilyImportantDateEntity item : items) {
            if (!member.equals(item.getMemberId()) || Boolean.FALSE.equals(item.getHomeReminderEnabled())) continue;
            int advance = item.getReminderDaysBefore() == null ? 0 : item.getReminderDaysBefore();
            if (advance > 0 && advance <= 30 && FamilyImportantDateService.matchesOccurrence(item, today.plusDays(advance)))
                notices.add(personal(item, today.plusDays(advance), advance));
            if (FamilyImportantDateService.matchesOccurrence(item, today)) notices.add(personal(item, today, 0));
        }
        if (settings.solarTerms()) {
            holidays.allEvents(today, today).stream().filter(event -> event.code().startsWith("china.solar-term."))
                    .forEach(event -> {
                        String name = event.code().substring("china.solar-term.".length());
                        notices.add(new Notice(event.code() + '|' + today + "|0", "SOLAR_TERM", "OTHER", name,
                                today.toString(), 0, "今天是" + name + "。", termDetail(name),
                                "/calendar?date=" + today + "&holidayCode=" + encode(event.code()), 9));
                    });
        }
        return notices.stream().sorted(Comparator.comparingInt(Notice::priority).thenComparing(Notice::key)).toList();
    }

    private Notice personal(FamilyImportantDateEntity item, LocalDate occurrence, int advance) {
        String kind = item.getEventKind() == null ? "IMPORTANT" : item.getEventKind();
        String subject = item.getBirthdaySubject() == null ? "OTHER" : item.getBirthdaySubject();
        String title = advance > 0 ? advance + " 天后是「" + item.getName() + "」。"
                : "BIRTHDAY".equals(kind) && "SELF".equals(subject) ? "生日快乐，今天对自己好一点。"
                : "BIRTHDAY".equals(kind) ? "今天是「" + item.getName() + "」，送一句祝福吧。"
                : "ANNIVERSARY".equals(kind) ? "今天是「" + item.getName() + "」，值得纪念。"
                : "今天记得「" + item.getName() + "」。";
        String detail = advance > 0 ? "留一点准备时间，不必临时匆忙。"
                : "BIRTHDAY".equals(kind) ? "愿这一岁，有想做的事，也有安心的留白。"
                : "这是你提前留给今天的提醒。";
        int priority = advance > 0 ? 0 : "IMPORTANT".equals(kind) ? 1 : "BIRTHDAY".equals(kind) ? 2 : 3;
        return new Notice(item.getId() + '|' + occurrence + '|' + advance, kind, subject, item.getName(),
                occurrence.toString(), advance, title, detail,
                "/calendar?date=" + occurrence + "&importantDateId=" + encode(item.getId()), priority);
    }

    private static String termDetail(String name) {
        if (List.of("寒露", "霜降", "立冬", "小雪", "大雪", "冬至", "小寒", "大寒").contains(name))
            return "时节渐凉，照顾好自己，留一点暖意。";
        if (List.of("立夏", "小满", "芒种", "夏至", "小暑", "大暑").contains(name))
            return "夏日时节，给忙碌留一点清凉。";
        return "四季又走过一小步，停一下，感受今天。";
    }

    private Settings decode(String value) {
        if (value == null || value.isBlank()) return new Settings(true, true, 0);
        try { return json.readValue(value, Settings.class); }
        catch (JsonProcessingException exception) { throw new BadRequestException("首页提醒设置读取失败，请重新保存"); }
    }

    private boolean available(String value) {
        if (value == null || value.isBlank()) return true;
        if (!value.startsWith("RESERVED:")) return false;
        try { return Long.parseLong(value.substring(value.lastIndexOf(':') + 1)) <= clock.millis(); }
        catch (NumberFormatException exception) { return false; }
    }

    private LocalDate currentDate(String date) {
        LocalDate today = LocalDate.now(clock);
        if (!today.toString().equals(date)) throw new ConflictException("日期已变化，请重新读取今天的提醒");
        return today;
    }

    private static String storageKey(LocalDate day, String key) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
            return DELIVERY_PREFIX + day + ':' + digest;
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static void requireMember(String member) {
        if (member == null || member.isBlank()) throw new cn.wayuwa.wao.common.UnauthorizedException("请先登录");
    }
    private static ConflictException conflict() { return new ConflictException("首页提醒设置已在其他设备更新，请重新读取；本机草稿仍保留"); }
}
