package cn.wayuwa.wao.common;

/** 已退役的业务写入口，历史读取仍然有效。 */
public class GoneException extends RuntimeException {
    public GoneException(String message) { super(message); }
}
