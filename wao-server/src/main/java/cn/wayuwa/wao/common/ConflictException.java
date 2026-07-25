package cn.wayuwa.wao.common;

/** 请求与已有唯一资源或账号绑定关系冲突。 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
