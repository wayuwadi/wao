package cn.wayuwa.wao.common;

/**
 * Unified API response envelope shared by all application endpoints.
 *
 * @param code business status code; zero indicates success
 * @param message human-readable result message
 * @param data response payload
 * @param timestamp response creation time in epoch milliseconds
 * @param <T> payload type
 */
public record ApiResponse<T>(int code, String message, T data, long timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, System.currentTimeMillis());
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, System.currentTimeMillis());
    }
}
