package cn.wayuwa.wao.service;

/** 网易云开放平台返回的业务错误；不携带签名、令牌或请求原文。 */
public class NeteaseMusicApiException extends RuntimeException {
    private final int code;

    public NeteaseMusicApiException(int code, String message) {
        super(message == null || message.isBlank() ? "网易云音乐服务暂时不可用" : message);
        this.code = code;
    }

    public int code() { return code; }
}
