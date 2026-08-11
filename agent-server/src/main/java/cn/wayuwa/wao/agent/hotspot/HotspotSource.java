package cn.wayuwa.wao.agent.hotspot;

import java.util.List;

/**
 * 热点来源适配器。与 {@code PublishPlatform} 同样的设计：能力显式声明，缺条件的来源自己承认不可用。
 *
 * <p>一个来源抓失败不能影响其他来源，因此 {@link #fetch()} 的异常由 HotspotService 逐源捕获并记录。
 */
public interface HotspotSource {

    /** 来源代码，落库用，例如 WEIBO。 */
    String code();

    /** 展示名，例如「微博热搜」。 */
    String label();

    /** 当前是否可采集。缺凭据的来源返回 false。 */
    default boolean available() {
        return true;
    }

    /** 不可用时的说明，直接展示给用户。 */
    default String note() {
        return "";
    }

    /** 拉取当前榜单，按名次升序返回。 */
    List<RawHotspot> fetch();
}
