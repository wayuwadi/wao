package cn.wayuwa.wao.dto;

/** 系统设置中可安全展示的微信公众号登录配置。 */
public record WechatOfficialLoginConfigDto(
        boolean enabled,
        String appId,
        String appSecretMask,
        boolean appSecretSet,
        String tokenMask,
        boolean tokenSet,
        boolean configured,
        String callbackPath) { }
