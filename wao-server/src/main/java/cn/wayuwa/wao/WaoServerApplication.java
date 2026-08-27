package cn.wayuwa.wao;

import java.util.TimeZone;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import cn.wayuwa.wao.auth.AuthProperties;
import cn.wayuwa.wao.auth.JumpServerSsoProperties;
import cn.wayuwa.wao.config.NeteaseMusicProperties;
import cn.wayuwa.wao.auth.WechatLoginProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({FeishuLoginProperties.class, WechatLoginProperties.class, AuthProperties.class,
        JumpServerSsoProperties.class, NeteaseMusicProperties.class})
@EnableScheduling
public class WaoServerApplication {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        SpringApplication.run(WaoServerApplication.class, args);
    }
}
