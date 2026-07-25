package cn.wayuwa.wao.common;

/** 当前请求没有有效登录会话或登录凭据无效。 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
