package cn.wayuwa.wao.feishu;

/** 调用飞书开放平台失败。消息面向使用者，可直接展示在配置页上。 */
public class FeishuApiException extends RuntimeException {
    private int apiCode;

    public FeishuApiException(String message, int apiCode) {
        super(message);
        this.apiCode = apiCode;
    }

    /** 从结构化响应识别月额度超限，不依赖可能变化的错误文案。 */
    public static boolean monthlyQuotaExceeded(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof FeishuApiException api && api.apiCode == 99991403) return true;
            if (cause instanceof org.springframework.web.client.RestClientResponseException response) {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(response.getResponseBodyAsString()).path("code").asInt() == 99991403;
                } catch (java.io.IOException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    public FeishuApiException(String message) {
        super(message);
    }

    public FeishuApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
