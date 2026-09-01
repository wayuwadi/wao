package cn.wayuwa.wao.dto;

/** 仅在用户显式查看时返回一次的账号密码。 */
public record AccountResourceSecretDto(String secret) { }
