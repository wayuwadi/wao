package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.FileSettingsDto;
import cn.wayuwa.wao.dto.SaveFileSettingsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文件与图床的系统级限制；损坏配置回落到部署支持的安全默认值。 */
@Service
public class FileSettingsService {

    static final int DEFAULT_FILE_MAX_MB = 200;
    static final int MAX_FILE_MAX_MB = 200;
    static final int DEFAULT_IMAGE_MAX_MB = 10;

    private final SettingService settings;

    public FileSettingsService(SettingService settings) {
        this.settings = settings;
    }

    /** 返回当前真实生效的上传限制与公开下载验证码开关。 */
    public FileSettingsDto get() {
        return new FileSettingsDto(
                bounded(SettingService.FILE_UPLOAD_MAX_MB, DEFAULT_FILE_MAX_MB, 1, MAX_FILE_MAX_MB),
                bounded(SettingService.IMAGE_UPLOAD_MAX_MB, DEFAULT_IMAGE_MAX_MB, 1, 10),
                enabled());
    }

    /** 在一个事务里保存三项设置，避免请求读取到新旧混合配置。 */
    @Transactional
    public FileSettingsDto save(SaveFileSettingsRequest request) {
        if (request.fileMaxMb() < 1 || request.fileMaxMb() > MAX_FILE_MAX_MB
                || request.imageMaxMb() < 1 || request.imageMaxMb() > 10) {
            throw new BadRequestException("文件上传上限超出当前部署支持范围");
        }
        settings.put(SettingService.FILE_UPLOAD_MAX_MB, String.valueOf(request.fileMaxMb()));
        settings.put(SettingService.IMAGE_UPLOAD_MAX_MB, String.valueOf(request.imageMaxMb()));
        settings.put(SettingService.FILE_DOWNLOAD_CAPTCHA_ENABLED,
                String.valueOf(request.downloadCaptchaEnabled()));
        return get();
    }

    /** 公开下载是否需要回答算术题；一次性票据不受此开关影响。 */
    public boolean captchaEnabled() {
        return enabled();
    }

    private boolean enabled() {
        return !"false".equalsIgnoreCase(settings.get(
                SettingService.FILE_DOWNLOAD_CAPTCHA_ENABLED, "true"));
    }

    private int bounded(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(settings.get(key, String.valueOf(fallback)));
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
