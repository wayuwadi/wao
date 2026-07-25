package cn.wayuwa.wao.feishu;

/** 调用飞书开放平台失败。消息面向使用者，可直接展示在配置页上。 */
public class FeishuApiException extends RuntimeException {

    public FeishuApiException(String message) {
        super(message);
    }

    public FeishuApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
