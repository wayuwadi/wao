package cn.wayuwa.wao.agent;

import cn.wayuwa.wao.agent.ai.AiProperties;
import cn.wayuwa.wao.agent.auth.SharedSessionProperties;
import cn.wayuwa.wao.agent.config.MediaProperties;
import cn.wayuwa.wao.agent.config.NodeProperties;
import cn.wayuwa.wao.agent.platform.douyin.DouyinProperties;
import cn.wayuwa.wao.agent.video.VideoGenerationProperties;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SharedSessionProperties.class, AiProperties.class, DouyinProperties.class,
        NodeProperties.class, MediaProperties.class, VideoGenerationProperties.class})
public class WaoAgentApplication {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        SpringApplication.run(WaoAgentApplication.class, args);
    }
}
