package cn.wayuwa.wao.agent.hotspot;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 热点采集专用 HTTP 客户端。超时收得比较紧：某个站点卡住不该拖慢整轮采集。
 *
 * <p>单独成 Bean 而不是在各来源构造器里改 builder，是为了让测试能用 MockRestServiceServer
 * 绑定 builder —— 在构造器里覆盖 requestFactory 会把 mock 的绑定顶掉。
 */
@Configuration
public class HotspotClientConfig {

    @Bean
    public RestClient hotspotRestClient(RestClient.Builder builder) {
        return builder.clone()
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(5))
                        .withReadTimeout(Duration.ofSeconds(12))))
                .build();
    }
}
