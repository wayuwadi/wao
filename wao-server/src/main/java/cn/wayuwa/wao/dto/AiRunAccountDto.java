package cn.wayuwa.wao.dto;

/**
 * Runner 领取一次运行时获得的账号配置。
 *
 * <p>{@code credentials} 或 {@code apiKey} 含明文，只能返回给已配对且实际领取本次运行的 Runner。
 */
public record AiRunAccountDto(
        String id,
        String provider,
        String label,
        String authMethod,
        String credentials,
        String apiKey,
        String baseUrl,
        String model,
        String apiProtocol) {
}
