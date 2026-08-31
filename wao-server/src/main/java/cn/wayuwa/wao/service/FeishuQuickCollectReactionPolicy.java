package cn.wayuwa.wao.service;

import java.util.Set;

import cn.wayuwa.wao.common.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 飞书快速收集的表情工作流配置。
 *
 * <p>“已收下”表情同时是成员手工派工的触发动作；AI Run 执行期间不追加新表情，成功后再使用
 * “待验收”表情。只接受已经在 WAO 真实链路中使用过的飞书官方枚举，避免保存一个飞书拒绝的值后
 * 让整个群聊工作流静默失效。
 */
@Service
public class FeishuQuickCollectReactionPolicy {

    public static final String DEFAULT_RECEIVED_EMOJI = "GET";
    public static final String DEFAULT_COMPLETED_EMOJI = "DONE";
    public static final Set<String> SUPPORTED_EMOJIS = Set.of("GET", "DONE", "StatusInFlight", "CheckMark");

    private final SettingService settings;

    public FeishuQuickCollectReactionPolicy(SettingService settings) {
        this.settings = settings;
    }

    /** 返回任务已保存、同时供成员手工触发 AI Run 的表情类型。 */
    public String receivedEmoji() {
        return configured(SettingService.QUICK_COLLECT_RECEIVED_EMOJI, DEFAULT_RECEIVED_EMOJI);
    }

    /** 返回 AI Run 完成、任务进入待验收阶段时的表情类型。 */
    public String completedEmoji() {
        return configured(SettingService.QUICK_COLLECT_COMPLETED_EMOJI, DEFAULT_COMPLETED_EMOJI);
    }

    /**
     * 保存工作流表情；null 表示保持原值。
     *
     * @param receivedEmoji 已收下及人工派工表情
     * @param completedEmoji 待验收表情
     */
    public void save(String receivedEmoji, String completedEmoji) {
        put(SettingService.QUICK_COLLECT_RECEIVED_EMOJI, receivedEmoji, "已收下 / 人工派工");
        put(SettingService.QUICK_COLLECT_COMPLETED_EMOJI, completedEmoji, "待验收");
    }

    private String configured(String key, String fallback) {
        String value = settings.get(key, fallback);
        return value != null && SUPPORTED_EMOJIS.contains(value) ? value : fallback;
    }

    private void put(String key, String value, String stage) {
        if (value == null) return;
        String normalized = value.trim();
        if (!StringUtils.hasText(normalized) || !SUPPORTED_EMOJIS.contains(normalized)) {
            throw new BadRequestException(stage + "表情不受支持");
        }
        settings.put(key, normalized);
    }
}
