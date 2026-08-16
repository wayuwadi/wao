package cn.wayuwa.wao.agent.video;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 视频提交、查询和下载专用 HTTP 客户端。 */
@Configuration
public class VideoClientConfig {

    @Bean
    public RestClient videoRestClient(VideoGenerationProperties properties, RestClient.Builder builder) {
        return builder.clone()
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(10))
                        .withReadTimeout(Duration.ofMillis(properties.timeout()))))
                .build();
    }
}
