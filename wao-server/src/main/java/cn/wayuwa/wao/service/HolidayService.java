package cn.wayuwa.wao.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nlf.calendar.Holiday;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import com.nlf.calendar.util.HolidayUtil;
import cn.wayuwa.wao.dto.HolidayEventDto;
import org.springframework.stereotype.Service;

/** 生成无需用户配置的中国节假日与常用国际节日日历。 */
@Service
public class HolidayService {

    private static final String CHINA = "CHINA";
    private static final String INTERNATIONAL = "INTERNATIONAL";
    private static final String FESTIVAL = "FESTIVAL";
    private static final String DAY_OFF = "DAY_OFF";
    private static final String ADJUSTED_WORKDAY = "ADJUSTED_WORKDAY";

    private static final Set<String> CHINESE_LUNAR_FESTIVALS = Set.of(
            "除夕", "春节", "元宵节", "端午节", "七夕节", "中秋节", "重阳节");
    private static final Map<String, String> CHINESE_SOLAR_FESTIVALS = Map.of(
            "元旦节", "元旦",
            "劳动节", "劳动节",
            "国庆节", "国庆节");
    private static final Map<String, String> INTERNATIONAL_SOLAR_FESTIVALS = Map.ofEntries(
            Map.entry("情人节", "情人节"),
            Map.entry("愚人节", "愚人节"),
            Map.entry("母亲节", "母亲节"),
            Map.entry("父亲节", "父亲节"),
            Map.entry("万圣节前夜", "万圣节"),
            Map.entry("感恩节", "感恩节"),
            Map.entry("平安夜", "平安夜"),
            Map.entry("圣诞节", "圣诞节"));

    /**
     * 返回日期窗内的内置节假日事件。
     *
     * <p>法定放假和调休只展示数据源已收录的国务院年度安排；农历节日和国际节日按历法规则计算，
     * 不会把尚未公布的未来调休伪造成官方安排。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（含）
     * @return 按日期、类别和名称排序的节假日事件
     */
    public List<HolidayEventDto> events(LocalDate from, LocalDate to) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            addOfficialSchedule(candidates, date);
            addChineseFestivals(candidates, date);
            addInternationalFestivals(candidates, date);
        }
        return candidates.values().stream()
                .sorted(Comparator.comparing(Candidate::date)
                        .thenComparing(Candidate::category)
                        .thenComparing(Candidate::name))
                .map(candidate -> new HolidayEventDto(
                        eventId(candidate), candidate.date().toString(), candidate.name(),
                        candidate.category(), candidate.dayType()))
                .toList();
    }

    private void addOfficialSchedule(Map<String, Candidate> out, LocalDate date) {
        Holiday holiday = HolidayUtil.getHoliday(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        if (holiday == null) return;
        String name = normalizeChineseName(holiday.getName());
        LocalDate target = LocalDate.parse(holiday.getTarget());
        if (holiday.isWork()) {
            add(out, new Candidate(date, name + "调休上班", CHINA, ADJUSTED_WORKDAY));
        } else if (date.equals(target)) {
            add(out, new Candidate(date, name, CHINA, DAY_OFF));
        } else {
            add(out, new Candidate(date, name + "假期", CHINA, DAY_OFF));
        }
    }

    private void addChineseFestivals(Map<String, Candidate> out, LocalDate date) {
        Solar solar = Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        for (String festival : solar.getFestivals()) {
            String name = CHINESE_SOLAR_FESTIVALS.get(festival);
            if (name != null) addFestivalUnlessScheduled(out, date, name, CHINA);
        }
        Lunar lunar = solar.getLunar();
        for (String festival : lunar.getFestivals()) {
            if (CHINESE_LUNAR_FESTIVALS.contains(festival)) {
                addFestivalUnlessScheduled(out, date, festival, CHINA);
            }
        }
        if ("清明".equals(lunar.getJieQi())) addFestivalUnlessScheduled(out, date, "清明节", CHINA);
    }

    private void addInternationalFestivals(Map<String, Candidate> out, LocalDate date) {
        Solar solar = Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        for (String festival : solar.getFestivals()) {
            String name = INTERNATIONAL_SOLAR_FESTIVALS.get(festival);
            if (name != null) add(out, new Candidate(date, name, INTERNATIONAL, FESTIVAL));
        }
        if (date.equals(westernEaster(date.getYear()))) {
            add(out, new Candidate(date, "复活节", INTERNATIONAL, FESTIVAL));
        }
    }

    private void addFestivalUnlessScheduled(Map<String, Candidate> out, LocalDate date, String name, String category) {
        boolean scheduled = out.values().stream().anyMatch(candidate -> candidate.date().equals(date)
                && candidate.category().equals(category)
                && candidate.name().equals(name));
        if (!scheduled) add(out, new Candidate(date, name, category, FESTIVAL));
    }

    private void add(Map<String, Candidate> out, Candidate candidate) {
        out.putIfAbsent(candidate.date() + "|" + candidate.category() + "|" + candidate.name(), candidate);
    }

    private String normalizeChineseName(String name) {
        return "元旦节".equals(name) ? "元旦" : name;
    }

    /** Meeus/Jones/Butcher 公历算法，得到西方教会复活节日期。 */
    private LocalDate westernEaster(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }

    private String eventId(Candidate candidate) {
        String key = candidate.category() + '|' + candidate.date() + '|' + candidate.name();
        return "holiday-" + candidate.date() + '-' + Integer.toUnsignedString(key.hashCode(), 36);
    }

    private record Candidate(LocalDate date, String name, String category, String dayType) {
    }
}
