package cn.wayuwa.wao.common;

/** 请求不合法（参数缺失、类型不支持、超出限制等），由 {@link GlobalExceptionHandler} 转成 400。 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
