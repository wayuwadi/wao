package cn.wayuwa.wao.agent.hotspot.sources;

import cn.wayuwa.wao.agent.hotspot.HotspotSource;
import cn.wayuwa.wao.agent.hotspot.RawHotspot;
import cn.wayuwa.wao.agent.platform.douyin.DouyinProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 抖音站内热榜占位。
 *
 * <p>抖音热点数据只在开放平台的热点接口下开放，需要与发布相同的 client_key 与审核通过的权限。
 * 拿到凭据前如实声明不可用，不用第三方聚合站的抓取结果冒充抖音官方热榜。
 */
@Component
public class DouyinHotSource implements HotspotSource {
    private final DouyinProperties properties;

    public DouyinHotSource(DouyinProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return "DOUYIN";
    }

    @Override
    public String label() {
        return "抖音热榜";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String note() {
        return properties.configured()
                ? "抖音热点接口需要在开放平台单独申请权限，当前应用尚未开通，暂用头条热榜作为字节系热点的替代参考。"
                : "抖音开放平台应用尚未配置，热榜与发布共用同一套凭据。在此之前用头条热榜作为字节系热点的替代参考。";
    }

    @Override
    public List<RawHotspot> fetch() {
        throw new UnsupportedOperationException(note());
    }
}
