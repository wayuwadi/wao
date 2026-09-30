package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.SaveFileSettingsRequest;
import org.junit.jupiter.api.Test;

class FileSettingsServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final FileSettingsService service = new FileSettingsService(settings);

    @Test
    void invalidStoredValuesFallBackToSafeDeploymentLimits() {
        when(settings.get(SettingService.FILE_UPLOAD_MAX_MB, "200")).thenReturn("500");
        when(settings.get(SettingService.IMAGE_UPLOAD_MAX_MB, "10")).thenReturn("broken");
        when(settings.get(SettingService.FILE_DOWNLOAD_CAPTCHA_ENABLED, "true")).thenReturn("true");

        assertThat(service.get().fileMaxMb()).isEqualTo(200);
        assertThat(service.get().imageMaxMb()).isEqualTo(10);
        assertThat(service.get().downloadCaptchaEnabled()).isTrue();
    }

    @Test
    void savesLimitsAndCaptchaSwitchAsPersistentSettings() {
        when(settings.get(SettingService.FILE_UPLOAD_MAX_MB, "200")).thenReturn("12");
        when(settings.get(SettingService.IMAGE_UPLOAD_MAX_MB, "10")).thenReturn("4");
        when(settings.get(SettingService.FILE_DOWNLOAD_CAPTCHA_ENABLED, "true")).thenReturn("false");

        var result = service.save(new SaveFileSettingsRequest(12, 4, false));

        assertThat(result.downloadCaptchaEnabled()).isFalse();
        verify(settings).put(SettingService.FILE_UPLOAD_MAX_MB, "12");
        verify(settings).put(SettingService.IMAGE_UPLOAD_MAX_MB, "4");
        verify(settings).put(SettingService.FILE_DOWNLOAD_CAPTCHA_ENABLED, "false");
    }

    @Test
    void acceptsTwoHundredMbAndRejectsHigherBusinessLimit() {
        when(settings.get(SettingService.FILE_UPLOAD_MAX_MB, "200")).thenReturn("200");
        when(settings.get(SettingService.IMAGE_UPLOAD_MAX_MB, "10")).thenReturn("10");
        service.save(new SaveFileSettingsRequest(200, 10, true));
        verify(settings).put(SettingService.FILE_UPLOAD_MAX_MB, "200");
        assertThatThrownBy(() -> service.save(new SaveFileSettingsRequest(201, 10, true)))
                .isInstanceOf(cn.wayuwa.wao.common.BadRequestException.class);
    }
}
