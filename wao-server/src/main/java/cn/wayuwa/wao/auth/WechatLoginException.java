package cn.wayuwa.wao.auth;

/** 可安全返回给用户的微信登录失败。 */
public class WechatLoginException extends RuntimeException {
    public WechatLoginException(String message) { super(message); }
    public WechatLoginException(String message, Throwable cause) { super(message, cause); }
}
