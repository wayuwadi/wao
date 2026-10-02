package cn.wayuwa.wao;

import java.util.TimeZone;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import cn.wayuwa.wao.auth.AuthProperties;
import cn.wayuwa.wao.auth.JumpServerSsoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableConfigurationProperties({FeishuLoginProperties.class, AuthProperties.class, JumpServerSsoProperties.class})
public class WaoServerApplication {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        SpringApplication.run(WaoServerApplication.class, args);
    }
}
