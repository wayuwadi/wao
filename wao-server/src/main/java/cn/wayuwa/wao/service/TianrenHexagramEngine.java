package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.TianrenHexagramDto;
import cn.wayuwa.wao.dto.TianrenTrigramDto;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 三枚硬币起卦与文王卦序映射；不包含占断文本或医疗结论。 */
@Component
public class TianrenHexagramEngine {

    private static final List<Trigram> TRIGRAMS = List.of(
            new Trigram(7, "乾", "天", "☰"),
            new Trigram(3, "兑", "泽", "☱"),
            new Trigram(5, "离", "火", "☲"),
            new Trigram(1, "震", "雷", "☳"),
            new Trigram(6, "巽", "风", "☴"),
            new Trigram(2, "坎", "水", "☵"),
            new Trigram(4, "艮", "山", "☶"),
            new Trigram(0, "坤", "地", "☷"));

    /** 行是下卦、列是上卦，顺序均为乾兑离震巽坎艮坤。 */
    private static final int[][] KING_WEN = {
            {1, 43, 14, 34, 9, 5, 26, 11},
            {10, 58, 38, 54, 61, 60, 41, 19},
            {13, 49, 30, 55, 37, 63, 22, 36},
            {25, 17, 21, 51, 42, 3, 27, 24},
            {44, 28, 50, 32, 57, 48, 18, 46},
            {6, 47, 64, 40, 59, 29, 4, 7},
            {33, 31, 56, 62, 53, 39, 52, 15},
            {12, 45, 35, 16, 20, 8, 23, 2}
    };

    private static final String[] NAMES = {
            "", "乾", "坤", "屯", "蒙", "需", "讼", "师", "比", "小畜", "履", "泰", "否", "同人", "大有", "谦", "豫",
            "随", "蛊", "临", "观", "噬嗑", "贲", "剥", "复", "无妄", "大畜", "颐", "大过", "坎", "离", "咸", "恒",
            "遁", "大壮", "晋", "明夷", "家人", "睽", "蹇", "解", "损", "益", "夬", "姤", "萃", "升", "困", "井",
            "革", "鼎", "震", "艮", "渐", "归妹", "丰", "旅", "巽", "兑", "涣", "节", "中孚", "小过", "既济", "未济"
    };

    private final SecureRandom random;

    public TianrenHexagramEngine() {
        this(new SecureRandom());
    }

    TianrenHexagramEngine(SecureRandom random) {
        this.random = random;
    }

    /**
     * 连续六次投掷三枚等概率硬币，正面记 3、反面记 2，按初爻到上爻返回 6/7/8/9。
     *
     * @return 六个服务端随机爻值
     */
    public List<Integer> castLines() {
        List<Integer> lines = new ArrayList<>(6);
        for (int line = 0; line < 6; line++) {
            int value = 0;
            for (int coin = 0; coin < 3; coin++) value += random.nextBoolean() ? 3 : 2;
            lines.add(value);
        }
        return List.copyOf(lines);
    }

    /**
     * 将六个爻值映射为本卦、变爻与之卦；6 为老阴、9 为老阳。
     *
     * @param lines 初爻到上爻的 6/7/8/9
     * @return 可用于持久化和展示的规范卦象
     */
    public Reading describe(List<Integer> lines) {
        validate(lines);
        List<Integer> changing = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index) == 6 || lines.get(index) == 9) changing.add(index + 1);
        }
        TianrenHexagramDto primary = hexagram(lines, false);
        TianrenHexagramDto relating = changing.isEmpty() ? null : hexagram(lines, true);
        return new Reading(List.copyOf(lines), List.copyOf(changing), primary, relating);
    }

    private TianrenHexagramDto hexagram(List<Integer> lines, boolean changed) {
        Trigram lower = trigram(lines, 0, changed);
        Trigram upper = trigram(lines, 3, changed);
        int number = KING_WEN[TRIGRAMS.indexOf(lower)][TRIGRAMS.indexOf(upper)];
        return new TianrenHexagramDto(
                number,
                NAMES[number],
                new String(Character.toChars(0x4DC0 + number - 1)),
                upper.dto(),
                lower.dto());
    }

    private Trigram trigram(List<Integer> lines, int offset, boolean changed) {
        int code = 0;
        for (int bit = 0; bit < 3; bit++) {
            int value = lines.get(offset + bit);
            boolean yang = value % 2 == 1;
            if (changed && (value == 6 || value == 9)) yang = !yang;
            if (yang) code |= 1 << bit;
        }
        int target = code;
        return TRIGRAMS.stream().filter(item -> item.code() == target).findFirst().orElseThrow();
    }

    private static void validate(List<Integer> lines) {
        if (lines == null || lines.size() != 6 || lines.stream().anyMatch(value -> value == null || value < 6 || value > 9)) {
            throw new IllegalArgumentException("卦象必须包含六个 6/7/8/9 爻值");
        }
    }

    private record Trigram(int code, String name, String nature, String symbol) {
        TianrenTrigramDto dto() {
            return new TianrenTrigramDto(name, nature, symbol);
        }
    }

    /** 已完成映射的一次卦象。 */
    public record Reading(
            List<Integer> lines,
            List<Integer> changingLines,
            TianrenHexagramDto primary,
            TianrenHexagramDto relating) {
    }
}
