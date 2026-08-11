package cn.wayuwa.wao.agent.ai;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 文案生成专用的 HTTP 客户端，超时比默认值宽松，因为生成一段长文案可能要几十秒。 */
@Configuration
public class AiClientConfig {

    @Bean
    public RestClient aiRestClient(AiProperties properties, RestClient.Builder builder) {
        return builder.clone()
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(10))
                        .withReadTimeout(Duration.ofMillis(properties.timeout()))))
                .build();
    }
}
