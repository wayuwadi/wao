package cn.wayuwa.wao.service;

/** 仅由服务端业务入口赋值；未知用途永远不能使用集中 DeepSeek 凭据。 */
public enum DeepSeekPurpose {
    POLISH, ASSISTANT, TIANREN, BACKGROUND, UNKNOWN;

    /** 旧队列和未登记用途按禁止处理。 */
    public static DeepSeekPurpose parse(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return UNKNOWN; }
    }
}
