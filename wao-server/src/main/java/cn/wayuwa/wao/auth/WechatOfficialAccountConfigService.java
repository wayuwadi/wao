package cn.wayuwa.wao.auth;

import cn.wayuwa.wao.dto.SaveWechatOfficialLoginConfigRequest;
import cn.wayuwa.wao.dto.WechatOfficialLoginConfigDto;
import cn.wayuwa.wao.service.SettingService;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 管理微信公众号登录的运行时配置，并兼容尚未迁移的部署环境变量。 */
@Service
public class WechatOfficialAccountConfigService {
    public static final String CALLBACK_PATH = "/api/auth/wechat/official/callback";
    private static final Set<String> KEYS = Set.of(
            SettingService.WECHAT_OFFICIAL_ENABLED,
            SettingService.WECHAT_OFFICIAL_APP_ID,
            SettingService.WECHAT_OFFICIAL_APP_SECRET,
            SettingService.WECHAT_OFFICIAL_TOKEN);

    private final SettingService settings;
    private final WechatOfficialAccountProperties deployment;
    private final WechatOfficialLoginStore store;

    @Autowired
    public WechatOfficialAccountConfigService(SettingService settings,
            WechatOfficialAccountProperties deployment, WechatOfficialLoginStore store) {
        this.settings = settings;
        this.deployment = deployment;
        this.store = store;
    }

    /** 仅供认证服务单元测试复用原部署配置。 */
    WechatOfficialAccountConfigService(WechatOfficialAccountProperties deployment) {
        this(null, deployment, null);
    }

    /** 返回当前真正供微信公众号接口使用的完整配置。 */
    public Effective current() {
        Map<String, String> stored = settings == null ? Map.of() : settings.getAll(KEYS);
        return new Effective(
                booleanValue(stored.get(SettingService.WECHAT_OFFICIAL_ENABLED), deployment.enabled()),
                value(stored.get(SettingService.WECHAT_OFFICIAL_APP_ID), deployment.appId()),
                value(stored.get(SettingService.WECHAT_OFFICIAL_APP_SECRET), deployment.appSecret()),
                value(stored.get(SettingService.WECHAT_OFFICIAL_TOKEN), deployment.token()),
                deployment.apiBaseUrl(), deployment.qrBaseUrl());
    }

    /** 返回系统设置页面可展示的脱敏配置。 */
    public WechatOfficialLoginConfigDto get() {
        return dto(current());
    }

    /**
     * 保存运行时配置；密钥留空时保留原值，启用前必须具备一套完整真实配置。
     * 保存完成后清除旧 access token，使新 App 凭据无需重启即可生效。
     */
    @Transactional
    public WechatOfficialLoginConfigDto save(SaveWechatOfficialLoginConfigRequest request) {
        Effective before = current();
        Effective candidate = new Effective(
                request.enabled() == null ? before.enabled() : request.enabled(),
                request.appId() == null ? before.appId() : request.appId().trim(),
                secret(request.appSecret(), before.appSecret()),
                secret(request.token(), before.token()),
                before.apiBaseUrl(), before.qrBaseUrl());
        if (candidate.enabled() && !candidate.configured()) {
            throw new IllegalArgumentException("启用公众号登录前，请完整填写 App ID、App Secret 与消息回调 Token");
        }

        settings.put(SettingService.WECHAT_OFFICIAL_ENABLED,
                request.enabled() == null ? null : String.valueOf(request.enabled()));
        settings.put(SettingService.WECHAT_OFFICIAL_APP_ID,
                request.appId() == null ? null : request.appId().trim());
        settings.put(SettingService.WECHAT_OFFICIAL_APP_SECRET,
                StringUtils.hasText(request.appSecret()) ? request.appSecret().trim() : null);
        settings.put(SettingService.WECHAT_OFFICIAL_TOKEN,
                StringUtils.hasText(request.token()) ? request.token().trim() : null);
        if (store != null && credentialsChanged(before, candidate)) store.clearAccessToken();
        return dto(candidate);
    }

    /** 微信官方 API 根地址来自部署基线，不允许通过业务表单改写。 */
    public String apiBaseUrl() {
        return deployment.apiBaseUrl();
    }

    private static boolean credentialsChanged(Effective before, Effective after) {
        return before.enabled() != after.enabled()
                || !Objects.equals(before.appId(), after.appId())
                || !Objects.equals(before.appSecret(), after.appSecret());
    }

    private static String secret(String supplied, String current) {
        return StringUtils.hasText(supplied) ? supplied.trim() : current;
    }

    private static String value(String stored, String fallback) {
        return stored == null ? fallback : stored;
    }

    private static boolean booleanValue(String stored, boolean fallback) {
        return stored == null ? fallback : "true".equalsIgnoreCase(stored);
    }

    private static WechatOfficialLoginConfigDto dto(Effective config) {
        return new WechatOfficialLoginConfigDto(config.enabled(), value(config.appId(), ""),
                SettingService.mask(config.appSecret()), StringUtils.hasText(config.appSecret()),
                SettingService.mask(config.token()), StringUtils.hasText(config.token()),
                config.configured(), CALLBACK_PATH);
    }

    /** 同一次认证动作使用的一致配置快照。 */
    public record Effective(boolean enabled, String appId, String appSecret, String token,
                            String apiBaseUrl, String qrBaseUrl) {
        /** 真实微信官方接口所需字段是否完整。 */
        public boolean configured() {
            return enabled && StringUtils.hasText(appId) && StringUtils.hasText(appSecret)
                    && StringUtils.hasText(token) && StringUtils.hasText(apiBaseUrl)
                    && StringUtils.hasText(qrBaseUrl);
        }
    }
}
