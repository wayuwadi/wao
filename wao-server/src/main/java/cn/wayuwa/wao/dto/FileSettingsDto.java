package cn.wayuwa.wao.dto;

/** 文件与图床的可调上传限制，以及公开下载页的人机校验开关。 */
public record FileSettingsDto(
        int fileMaxMb,
        int imageMaxMb,
        boolean downloadCaptchaEnabled) {
}
