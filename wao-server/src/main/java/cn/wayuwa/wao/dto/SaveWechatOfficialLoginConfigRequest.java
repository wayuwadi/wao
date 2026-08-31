package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Size;

/** 保存微信公众号扫码登录配置；密钥留空表示保留已有密文。 */
public record SaveWechatOfficialLoginConfigRequest(
        Boolean enabled,
        @Size(max = 128) String appId,
        @Size(max = 255) String appSecret,
        @Size(max = 255) String token) { }
