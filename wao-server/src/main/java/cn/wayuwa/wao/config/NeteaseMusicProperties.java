package cn.wayuwa.wao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网易云音乐开放平台应用与设备参数；所有敏感值由生产环境变量注入。 */
@ConfigurationProperties("netease.music")
public record NeteaseMusicProperties(
        boolean enabled,
        String appId,
        String appSecret,
        String privateKey,
        String redirectUri,
        String frontendUrl,
        String apiBaseUrl,
        String authorizeUrl,
        String clientType,
        String channel,
        String deviceType,
        String os,
        String osVer,
        String appVer,
        String brand,
        String model) {
}
