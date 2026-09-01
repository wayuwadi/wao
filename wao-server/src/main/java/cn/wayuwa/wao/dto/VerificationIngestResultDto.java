package cn.wayuwa.wao.dto;

/** 短信入站结果；重复请求不会再次转发。 */
public record VerificationIngestResultDto(String id, boolean duplicate, boolean codeRecognized,
        boolean forwarded, String message) { }
