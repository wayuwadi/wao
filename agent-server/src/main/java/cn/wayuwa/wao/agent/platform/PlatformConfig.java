package cn.wayuwa.wao.agent.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册暂未开放官方发布接口的平台占位适配器。 */
@Configuration
public class PlatformConfig {

    @Bean
    public PublishPlatform wechatMpPlatform() {
        return new PlannedPlatform(PlatformType.WECHAT_MP,
                "公众号有官方草稿箱与发布接口，但尚未接入。需要认证服务号的 AppID/AppSecret 并把节点公网 IP 加入白名单。");
    }

    @Bean
    public PublishPlatform wechatChannelsPlatform() {
        return new PlannedPlatform(PlatformType.WECHAT_CHANNELS,
                "视频号没有面向第三方的内容发布接口，微信开放的是小店与直播能力。当前只能生成内容后导出，由人工在视频号助手发布。");
    }

    @Bean
    public PublishPlatform xiaohongshuPlatform() {
        return new PlannedPlatform(PlatformType.XIAOHONGSHU,
                "小红书开放平台目前主要开放电商侧接口，内容发布仅对少数服务商定向开放。当前只能生成内容后导出，由人工发布。");
    }
}
