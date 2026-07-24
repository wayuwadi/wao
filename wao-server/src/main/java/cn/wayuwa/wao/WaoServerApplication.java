package cn.wayuwa.wao;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableConfigurationProperties(FeishuLoginProperties.class)
public class WaoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaoServerApplication.class, args);
    }
}
