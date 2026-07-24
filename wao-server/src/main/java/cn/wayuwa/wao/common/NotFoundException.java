package cn.wayuwa.wao.common;

/** 资源不存在，映射为 HTTP 404 + 业务码 40400。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
