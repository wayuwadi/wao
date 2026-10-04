package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本人的天人解读与配图设置，原子保存并保护跨设备草稿。 */
@Service
public class TianrenSettingsService {
    public static final String KEY = "tianren-ai-settings-v1";
    public static final String DEFAULT_INTERPRETATION = """
            请把今天的卦象解释成容易理解、能够联系日常生活的反思。
            用通俗、温和、具体的中文，结合这次提供的关注点，说明本卦的主要主题、变爻提示以及之卦提供的观察方向。
            不要逐字堆砌古文，也不要为了显得深奥使用晦涩术语。
            重点讲清“变与不变”：哪些行为或节奏值得调整，哪些原则值得继续保持。变化是可供反思的方向，不是注定的结果。
            没有变爻时明确说明，从当前主题的守持与深化解释，不虚构转变。
            保留完整解读的九个章节，让传统观点与当日建议各有清晰标注。给出一件今天能尝试的小事和一个值得自问的问题。
            关注点为空时从日常情境出发，不猜测我的身份、经历、健康或人际关系。
            同时提炼简短标题、一条值得调整的短句、一条值得保持的短句，以及适合横向图片的具体场景。
            画面和短句必须来自本次正文，不增加正文没有的预测和结论，按实际卦象与关注点选择画面，不固定套用山水、旅人或道路。
            """;
    public static final String DEFAULT_IMAGE = """
            根据本次卦象事实、解读正文和视觉摘要，生成一张帮助快速理解“变与不变”的横向图片，画面约 2:1。
            使用清楚的场景、动作和关系表达卦意，风格明亮、清爽、有温度，主次分明，不堆砌装饰。
            可以是现代插画或适合该内容的生活场景，不必每次都是山水图。
            有变爻时用连贯的两个区域表达当前主题与值得调整的方向，用共同主体、路径、光线等表达贯穿始终的原则。
            左右区域是理解线索，不是现实必然发生的时间顺序。无变爻时画完整稳定的场景，表达守持与深化，不制造不存在的变化。
            变爻多时提炼主要关系，不为每爻堆图标。缩小到卡片后仍能理解主要关系，保留关键主体，避免边缘裁切。
            不出现文字、字母、卦名、数字、卦符、爻线、水印和界面控件；标题与“变 / 不变”由页面单独排版。
            忠于本次解读，不新增吉凶承诺、财富或健康保证，不画确定的未来事件，不复现姓名、联系方式等可识别私人信息。
            """;
    public record Save(long version, Boolean autoImage, String interpretationPrompt, String imagePrompt) { }
    public record View(long version, boolean autoImage, String interpretationPrompt, String imagePrompt,
                       String defaultInterpretationPrompt, String defaultImagePrompt) { }
    private final MemberPreferenceMapper preferences;
    private final ObjectMapper json;
    public TianrenSettingsService(MemberPreferenceMapper preferences, ObjectMapper json) {
        this.preferences = preferences; this.json = json;
    }
    /** 无保存值才使用默认；加载或格式错误不能默默覆盖设置。 */
    public View get(String member) { requireMember(member); return parse(preferences.find(member, KEY)); }
    /** 对整个设置执行 CAS；冲突保留客户端草稿。 */
    @Transactional
    public View save(String member, Save input) {
        requireMember(member);
        if (input == null || input.autoImage() == null) throw new BadRequestException("请填写完整设置");
        String interpretation = checked(input.interpretationPrompt()), image = checked(input.imagePrompt());
        String old = preferences.find(member, KEY);
        if (parse(old).version() != input.version()) throw new ConflictException("设置已在其他设备更新，请重新读取；草稿仍保留");
        String next;
        try { next = json.writeValueAsString(new Save(input.version() + 1, input.autoImage(), interpretation, image)); }
        catch (Exception e) { throw new IllegalStateException("设置保存失败"); }
        int changed = old == null ? preferences.insertIfAbsent(member, KEY, next) : preferences.compareAndSet(member, KEY, old, next);
        if (changed != 1) throw new ConflictException("设置已在其他设备更新，请重新读取；草稿仍保留");
        return parse(next);
    }
    private View parse(String value) {
        if (value == null) return new View(0, true, DEFAULT_INTERPRETATION, DEFAULT_IMAGE, DEFAULT_INTERPRETATION, DEFAULT_IMAGE);
        try {
            var node = json.readTree(value);
            if (!node.isObject() || !node.path("version").isIntegralNumber() || node.path("version").asLong() < 1
                    || !node.path("autoImage").isBoolean() || !node.path("interpretationPrompt").isTextual()
                    || !node.path("imagePrompt").isTextual()) throw new IllegalArgumentException();
            return new View(node.get("version").asLong(), node.get("autoImage").asBoolean(),
                    checked(node.get("interpretationPrompt").asText()), checked(node.get("imagePrompt").asText()), DEFAULT_INTERPRETATION, DEFAULT_IMAGE);
        } catch (Exception e) { throw new BadRequestException("天人一卦设置格式异常，请检查已有配置"); }
    }
    private static String checked(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 4000) throw new BadRequestException("每项提示词须为 1–4000 字");
        return value.trim();
    }
    static void requireMember(String member) { if (member == null || member.isBlank()) throw new UnauthorizedException("请先登录"); }
}
