package cn.wayuwa.wao.agent.platform;

import cn.wayuwa.wao.agent.common.BadRequestException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 按平台类型查找适配器。 */
@Component
public class PlatformRegistry {
    private final Map<PlatformType, PublishPlatform> platforms = new EnumMap<>(PlatformType.class);

    public PlatformRegistry(List<PublishPlatform> beans) {
        for (PublishPlatform platform : beans) {
            platforms.put(platform.type(), platform);
        }
    }

    /** 返回指定平台适配器，未注册视为未支持。 */
    public PublishPlatform get(PlatformType type) {
        PublishPlatform platform = platforms.get(type);
        if (platform == null) throw new BadRequestException("平台尚未接入：" + type.label());
        return platform;
    }

    /** 按枚举顺序返回全部适配器，用于前端渲染能力矩阵。 */
    public List<PublishPlatform> all() {
        return Arrays.stream(PlatformType.values()).map(platforms::get).filter(Objects::nonNull).toList();
    }
}
