package cn.wayuwa.wao.dto;

/** 设备本地暂存的短期配对材料；轮询密钥明文只出现在本次响应。 */
public record CompanionPairingCreatedDto(String pairingId, String userCode, String pollSecret,
        String verificationUrl, String expiresAt) { }
