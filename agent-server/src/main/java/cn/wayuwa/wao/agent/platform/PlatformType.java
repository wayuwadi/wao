package cn.wayuwa.wao.agent.platform;

import cn.wayuwa.wao.agent.common.BadRequestException;
import java.util.Arrays;

/** 支持接管的自媒体平台。 */
public enum PlatformType {
    DOUYIN("抖音"),
    WECHAT_MP("公众号"),
    WECHAT_CHANNELS("视频号"),
    XIAOHONGSHU("小红书");

    private final String label;

    PlatformType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析前端传入的平台代码，非法值直接拒绝而不是静默降级。 */
    public static PlatformType parse(String raw) {
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(raw == null ? "" : raw.trim()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("不支持的平台：" + raw));
    }
}
