package cn.wayuwa.wao.common;

import cn.wayuwa.wao.auth.FeishuLoginException;
import cn.wayuwa.wao.auth.WechatLoginException;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.service.NeteaseMusicApiException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NotFoundException exception) {
        return ApiResponse.fail(40400, exception.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(BadRequestException exception) {
        return ApiResponse.fail(40000, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException exception) {
        String message = Optional.ofNullable(exception.getBindingResult().getFieldError())
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数不合法");
        return ApiResponse.fail(40001, message);
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorized(UnauthorizedException exception) {
        return ApiResponse.fail(40100, exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConflict(ConflictException exception) {
        return ApiResponse.fail(40900, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDataConflict(DataIntegrityViolationException exception) {
        // 只记类名时，任何唯一键冲突在日志里都长得一模一样，排查只能去翻库；带上根因就能直接看出撞的是哪张表哪个键。
        log.warn("Data integrity conflict: {}", exception.getMostSpecificCause().getMessage(), exception);
        return ApiResponse.fail(40901, "数据刚刚被其他请求占用，请刷新后重试");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException exception) {
        return ApiResponse.fail(40000, exception.getMessage());
    }

    @ExceptionHandler(FeishuLoginException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> handleFeishuLogin(FeishuLoginException exception) {
        log.warn("Feishu login failed: {}", exception.getMessage());
        return ApiResponse.fail(50201, exception.getMessage());
    }

    @ExceptionHandler(WechatLoginException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> handleWechatLogin(WechatLoginException exception) {
        log.warn("Wechat login failed: {}", exception.getMessage());
        return ApiResponse.fail(50202, exception.getMessage());
    }

    @ExceptionHandler(FeishuApiException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> handleFeishuApi(FeishuApiException exception) {
        log.warn("Feishu open API call failed: {}", exception.getMessage());
        return ApiResponse.fail(50203, exception.getMessage());
    }

    @ExceptionHandler(NeteaseMusicApiException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> handleNeteaseMusicApi(NeteaseMusicApiException exception) {
        log.warn("NetEase Music open API call failed: code={}, message={}", exception.code(), exception.getMessage());
        return ApiResponse.fail(50204, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception exception) {
        log.error("Unhandled application error", exception);
        return ApiResponse.fail(50000, "系统繁忙，请稍后重试");
    }
}
