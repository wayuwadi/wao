package cn.wayuwa.wao;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(FeishuLoginProperties.class)
@EnableScheduling
public class WaoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaoServerApplication.class, args);
    }
}
