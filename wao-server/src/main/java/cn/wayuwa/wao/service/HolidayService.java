package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.HolidayEventDto;
import com.nlf.calendar.Holiday;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import com.nlf.calendar.util.HolidayUtil;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 依据已收录国务院安排与稳定历法规则生成节日事实，不推测未知调休。 */
@Service
public class HolidayService {

    public static final String FAMILY = "FAMILY";
    public static final String WESTERN_POPULAR = "WESTERN_POPULAR";
    public static final String CHRISTIAN = "CHRISTIAN";
    public static final String NORTH_AMERICA = "NORTH_AMERICA";
    public static final Set<String> INTERNATIONAL_CATEGORIES = Set.of(
            FAMILY, WESTERN_POPULAR, CHRISTIAN, NORTH_AMERICA);

    private static final String CHINA = "CHINA";
    private static final String INTERNATIONAL = "INTERNATIONAL";
    private static final String FESTIVAL = "FESTIVAL";
    private static final String DAY_OFF = "DAY_OFF";
    private static final String ADJUSTED_WORKDAY = "ADJUSTED_WORKDAY";
    private static final String LUNAR_SOURCE = "lunar 1.7.7 历法数据";
    private static final String LUNAR_SOURCE_URL = "https://github.com/6tail/lunar-java";
    private static final String INTERNATIONAL_SOURCE = "WAO 稳定公历规则";
    private static final String OFFICIAL_2026_URL =
            "https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm";

    private static final Map<String, Definition> CHINESE = Map.ofEntries(
            china("元旦", "china.new-year"),
            china("春节", "china.spring-festival"),
            china("元宵节", "china.lantern-festival"),
            china("清明节", "china.qingming"),
            china("劳动节", "china.labor-day"),
            china("端午节", "china.dragon-boat"),
            china("七夕节", "china.qixi"),
            china("中秋节", "china.mid-autumn"),
            china("重阳节", "china.double-ninth"),
            china("国庆节", "china.national-day"));
    private static final Map<String, Definition> INTERNATIONAL_FESTIVALS = Map.ofEntries(
            international("情人节", "international.valentines-day", WESTERN_POPULAR),
            international("愚人节", "international.april-fools", WESTERN_POPULAR),
            international("母亲节", "international.mothers-day", FAMILY),
            international("父亲节", "international.fathers-day", FAMILY),
            international("万圣节前夜", "international.halloween", WESTERN_POPULAR),
            international("感恩节", "international.thanksgiving", NORTH_AMERICA),
            international("平安夜", "international.christmas-eve", CHRISTIAN),
            international("圣诞节", "international.christmas", CHRISTIAN));
    private static final Definition EASTER = new Definition(
            "international.easter", "复活节", INTERNATIONAL, CHRISTIAN);
    private static final Set<String> SOLAR_TERMS = Set.of(
            "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
            "立秋", "处暑", "白露", "秋分", "寒露", "霜降",
            "立冬", "小雪", "大雪", "冬至", "小寒", "大寒");

    /** 返回日期窗内的中国节日和当前成员主动选择的国际节日。 */
    public List<HolidayEventDto> events(LocalDate from, LocalDate to, Set<String> selectedInternationalCategories) {
        Set<String> selected = selectedInternationalCategories == null ? Set.of() : selectedInternationalCategories;
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            addOfficialSchedule(candidates, date);
            addChineseFestivals(candidates, date);
            addInternationalFestivals(candidates, date, selected);
        }
        return candidates.values().stream()
                .sorted(Comparator.comparing(Candidate::date)
                        .thenComparing(Candidate::category)
                        .thenComparing(Candidate::name))
                .map(this::dto)
                .toList();
    }

    /** 调度提醒时读取全部确定性节日；仅供服务端匹配已开启的提醒。 */
    public List<HolidayEventDto> allEvents(LocalDate from, LocalDate to) {
        return events(from, to, INTERNATIONAL_CATEGORIES);
    }

    /** 判断 lunar 当前版本是否收录了指定年份的国务院放假调休安排。 */
    public boolean officialScheduleAvailable(int year) {
        return !HolidayUtil.getHolidays(year).isEmpty();
    }

    /** 返回国务院来源；2026 年直达原文，其余年份落到带年份的官方政策库检索。 */
    public String officialSourceUrl(int year) {
        if (year == 2026) return OFFICIAL_2026_URL;
        String query = year + "年部分节假日安排的通知";
        return "https://sousuo.www.gov.cn/zcwjk/policyDocumentLibrary?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&t=zhengcelibrary";
    }

    /** 根据稳定代码查询节日定义，拒绝客户端自造名称。 */
    public Definition definition(String code) {
        return definitions().stream().filter(item -> item.code().equals(code)).findFirst().orElse(null);
    }

    private void addOfficialSchedule(Map<String, Candidate> out, LocalDate date) {
        Holiday holiday = HolidayUtil.getHoliday(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        if (holiday == null) return;
        String name = normalizeChineseName(holiday.getName());
        Definition definition = CHINESE.get(name);
        if (definition == null) return;
        LocalDate target = LocalDate.parse(holiday.getTarget());
        String source = date.getYear() + "年国务院办公厅节假日安排";
        if (holiday.isWork()) {
            add(out, new Candidate(date, definition, name + "调休上班", ADJUSTED_WORKDAY,
                    source, officialSourceUrl(date.getYear()), false));
        } else {
            add(out, new Candidate(date, definition, date.equals(target) ? name : name + "假期", DAY_OFF,
                    source, officialSourceUrl(date.getYear()), date.equals(target)));
        }
    }

    private void addChineseFestivals(Map<String, Candidate> out, LocalDate date) {
        Solar solar = Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        for (String festival : solar.getFestivals()) {
            Definition definition = CHINESE.get(normalizeChineseName(festival));
            if (definition != null) addFestivalUnlessScheduled(out, date, definition);
        }
        Lunar lunar = solar.getLunar();
        for (String festival : lunar.getFestivals()) {
            Definition definition = CHINESE.get(festival);
            if (definition != null) addFestivalUnlessScheduled(out, date, definition);
        }
        String solarTerm = lunar.getJieQi();
        if ("清明".equals(solarTerm)) addFestivalUnlessScheduled(out, date, CHINESE.get("清明节"));
        if (SOLAR_TERMS.contains(solarTerm)) {
            Definition definition = new Definition("china.solar-term." + solarTerm, solarTerm, CHINA, null);
            add(out, new Candidate(date, definition, "节气 · " + solarTerm, FESTIVAL,
                    LUNAR_SOURCE, LUNAR_SOURCE_URL, false));
        }
    }

    private void addInternationalFestivals(Map<String, Candidate> out, LocalDate date, Set<String> selected) {
        Solar solar = Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        for (String festival : solar.getFestivals()) {
            Definition definition = INTERNATIONAL_FESTIVALS.get(festival);
            if (definition != null && selected.contains(definition.internationalCategory())) {
                add(out, new Candidate(date, definition, displayInternationalName(festival), FESTIVAL,
                        INTERNATIONAL_SOURCE, null, true));
            }
        }
        if (selected.contains(CHRISTIAN) && date.equals(westernEaster(date.getYear()))) {
            add(out, new Candidate(date, EASTER, EASTER.name(), FESTIVAL, INTERNATIONAL_SOURCE, null, true));
        }
    }

    private void addFestivalUnlessScheduled(Map<String, Candidate> out, LocalDate date, Definition definition) {
        boolean scheduled = out.values().stream().anyMatch(candidate -> candidate.date().equals(date)
                && candidate.definition().code().equals(definition.code()));
        if (!scheduled) add(out, new Candidate(date, definition, definition.name(), FESTIVAL,
                LUNAR_SOURCE, LUNAR_SOURCE_URL, true));
    }

    private void add(Map<String, Candidate> out, Candidate candidate) {
        out.putIfAbsent(candidate.date() + "|" + candidate.definition().code() + "|" + candidate.name(), candidate);
    }

    private HolidayEventDto dto(Candidate candidate) {
        String key = candidate.definition().code() + '|' + candidate.date() + '|' + candidate.name();
        return new HolidayEventDto(
                "holiday-" + candidate.date() + '-' + Integer.toUnsignedString(key.hashCode(), 36),
                candidate.definition().code(), candidate.date().toString(), candidate.date().getYear(),
                candidate.name(), candidate.category(), candidate.dayType(),
                candidate.definition().internationalCategory(), candidate.sourceName(), candidate.sourceUrl(),
                candidate.remindable(), false, null);
    }

    private static String normalizeChineseName(String name) {
        return switch (name) {
            case "元旦节" -> "元旦";
            case "国庆" -> "国庆节";
            default -> name;
        };
    }

    private static String displayInternationalName(String name) {
        return "万圣节前夜".equals(name) ? "万圣节" : name;
    }

    /** Meeus/Jones/Butcher 公历算法，得到西方教会复活节日期。 */
    private static LocalDate westernEaster(int year) {
        int a = year % 19, b = year / 100, c = year % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        return LocalDate.of(year, month, (h + l - 7 * m + 114) % 31 + 1);
    }

    private static Map.Entry<String, Definition> china(String name, String code) {
        return Map.entry(name, new Definition(code, name, CHINA, null));
    }

    private static Map.Entry<String, Definition> international(String sourceName, String code, String category) {
        return Map.entry(sourceName, new Definition(code, displayInternationalName(sourceName), INTERNATIONAL, category));
    }

    private static List<Definition> definitions() {
        return java.util.stream.Stream.concat(CHINESE.values().stream(),
                        java.util.stream.Stream.concat(INTERNATIONAL_FESTIVALS.values().stream(),
                                java.util.stream.Stream.of(EASTER)))
                .distinct().toList();
    }

    /** 稳定节日定义，代码跨年份不变，用于个人重复提醒。 */
    public record Definition(String code, String name, String category, String internationalCategory) { }

    private record Candidate(LocalDate date, Definition definition, String name, String dayType,
            String sourceName, String sourceUrl, boolean remindable) {
        private String category() { return definition.category(); }
    }
}
