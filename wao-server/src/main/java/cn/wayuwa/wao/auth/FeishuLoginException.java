package cn.wayuwa.wao.auth;

/** Safe, user-facing failure raised while completing Feishu authentication. */
public class FeishuLoginException extends RuntimeException {
    public FeishuLoginException(String message) {
        super(message);
    }

    public FeishuLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
