package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.FeishuWebhookConfigEntity;
import cn.wayuwa.wao.dto.FeishuWebhookEndpointDto;
import cn.wayuwa.wao.dto.SaveFeishuWebhookEndpointRequest;
import cn.wayuwa.wao.mapper.FeishuWebhookConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用飞书自定义机器人配置管理。
 *
 * <p>每条 Webhook 地址独立使用 AES-256-GCM 加密。读取接口只经过 {@link #toDto} 返回脱敏地址；
 * 只有通知发送与测试接口会调用 {@link #endpointUrl(String)} / {@link #defaultTarget()} 解出明文。
 * 任意时刻最多一条记录带 {@code SYSTEM} 标记，现有系统通知据此选择默认机器人。
 */
@Service
public class FeishuWebhookConfigService {

    private static final String SYSTEM_DEFAULT = "SYSTEM";
    private static final int MAX_CONFIGS = 30;
    private static final String HOOK_PATH = "/open-apis/bot/v2/hook/";
    private static final Set<String> FEISHU_HOSTS = Set.of("open.feishu.cn", "open.larksuite.com");
    private static final Pattern HOOK_TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,512}");
    private static final int MAX_STORED_PLAINTEXT_BYTES = 2048 - 16;
    private static final String INVALID_URL_MASK = "需替换（请重新填写地址）";
    private static final String UNREADABLE_URL_MASK = "无法读取（请重新填写地址）";

    private final FeishuWebhookConfigMapper mapper;
    private final VaultCryptoService crypto;
    private final SettingService settings;
    private final String legacyDeploymentUrl;

    public FeishuWebhookConfigService(FeishuWebhookConfigMapper mapper, VaultCryptoService crypto,
            SettingService settings, @Value("${feishu.comment-webhook-url:}") String legacyDeploymentUrl) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.settings = settings;
        this.legacyDeploymentUrl = trim(legacyDeploymentUrl);
    }

    /** 读取全部机器人配置；系统默认项在前，其余按最近修改时间倒序。 */
    @Transactional
    public List<FeishuWebhookEndpointDto> list() {
        importLegacyDefaultIfNecessary();
        return entities().stream().map(this::toDto).toList();
    }

    /** 新增机器人；第一条配置会自动成为系统默认，保证已有通知链路始终有明确目标。 */
    @Transactional
    public FeishuWebhookEndpointDto create(SaveFeishuWebhookEndpointRequest request) {
        long count = mapper.selectCount(null);
        if (count >= MAX_CONFIGS) {
            throw new BadRequestException("飞书机器人配置不能超过 30 个");
        }
        String url = requiredUrl(request.url());
        VaultCryptoService.Encrypted encrypted = crypto.encrypt(url);
        LocalDateTime now = LocalDateTime.now();
        boolean systemDefault = count == 0 || Boolean.TRUE.equals(request.systemDefault());
        if (systemDefault) clearSystemDefault();

        FeishuWebhookConfigEntity entity = new FeishuWebhookConfigEntity();
        entity.setId(newId());
        entity.setName(name(request.name()));
        entity.setDescription(description(request.description()));
        entity.setWebhookCiphertext(encrypted.ciphertext());
        entity.setWebhookNonce(encrypted.nonce());
        entity.setEnabled(Boolean.TRUE.equals(request.enabled()));
        entity.setDefaultMarker(systemDefault ? SYSTEM_DEFAULT : null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        markInitialized();
        if (systemDefault) syncLegacyDefault(entity);
        return toDto(entity);
    }

    /**
     * 编辑机器人。url 为空表示保留原地址；系统默认项不能直接取消，需把另一项设为默认。
     */
    @Transactional
    public FeishuWebhookEndpointDto update(String id, SaveFeishuWebhookEndpointRequest request) {
        FeishuWebhookConfigEntity entity = required(id);
        Credential credential = credential(entity);
        String suppliedUrl = trim(request.url());
        if (!credential.usable() && suppliedUrl.isEmpty()) {
            throw new BadRequestException("当前 Webhook 地址无法安全使用，请填写新的飞书官方地址");
        }
        boolean wasDefault = isDefault(entity);
        boolean makeDefault = Boolean.TRUE.equals(request.systemDefault());
        if (wasDefault && !makeDefault) {
            throw new BadRequestException("系统默认机器人不能直接取消，请将另一项设为系统默认");
        }
        entity.setName(name(request.name()));
        entity.setDescription(description(request.description()));
        entity.setEnabled(Boolean.TRUE.equals(request.enabled()));
        if (!suppliedUrl.isEmpty()) {
            String url = validateUrl(suppliedUrl);
            VaultCryptoService.Encrypted encrypted = crypto.encrypt(url);
            entity.setWebhookCiphertext(encrypted.ciphertext());
            entity.setWebhookNonce(encrypted.nonce());
        }
        if (makeDefault && !wasDefault) {
            clearSystemDefault();
            entity.setDefaultMarker(SYSTEM_DEFAULT);
        }
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        markInitialized();
        if (isDefault(entity)) syncLegacyDefault(entity);
        return toDto(entity);
    }

    /** 删除一项配置；删除系统默认项时自动把最近修改的剩余项提升为默认。 */
    @Transactional
    public void delete(String id) {
        FeishuWebhookConfigEntity deleted = required(id);
        mapper.deleteById(id);
        markInitialized();
        if (!isDefault(deleted)) return;

        List<FeishuWebhookConfigEntity> remaining = entities();
        if (remaining.isEmpty()) {
            settings.put(SettingService.FEISHU_WEBHOOK_URL, "");
            settings.put(SettingService.FEISHU_WEBHOOK_ENABLED, "false");
            return;
        }
        FeishuWebhookConfigEntity promoted = remaining.get(0);
        promoted.setDefaultMarker(SYSTEM_DEFAULT);
        promoted.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(promoted);
        syncLegacyDefault(promoted);
    }

    /** 返回当前系统默认配置的安全 DTO，不包含地址明文。 */
    @Transactional
    public Optional<FeishuWebhookEndpointDto> defaultConfig() {
        importLegacyDefaultIfNecessary();
        return defaultEntity().map(this::toDto);
    }

    /**
     * 返回当前系统默认通知目标。该返回值含地址明文，只允许在服务端通知发送链路内使用。
     */
    @Transactional
    public Optional<NotificationTarget> defaultTarget() {
        importLegacyDefaultIfNecessary();
        return defaultEntity().map(entity -> {
            Credential credential = credential(entity);
            return new NotificationTarget(entity.getId(), entity.getName(),
                    credential.usable() ? credential.url() : "",
                    credential.usable() && Boolean.TRUE.equals(entity.getEnabled()));
        });
    }

    /**
     * 解密指定配置地址，供“发送测试消息”使用；调用方不得记录或返回该字符串。
     */
    @Transactional(readOnly = true)
    public String endpointUrl(String id) {
        Credential credential = credential(required(id));
        if (!credential.usable()) {
            throw new BadRequestException("当前 Webhook 地址无法安全使用，请先填写新的飞书官方地址");
        }
        return credential.url();
    }

    /**
     * 返回指定业务选择的通知目标；配置不存在、已停用或凭据不可读时明确拒绝发送。
     *
     * @param id 业务偏好中保存的稳定配置 id
     * @return 可发送的内部目标，地址不得写入响应或日志
     */
    @Transactional(readOnly = true)
    public NotificationTarget enabledTarget(String id) {
        FeishuWebhookConfigEntity entity = required(id);
        Credential credential = credential(entity);
        if (!credential.usable()) {
            throw new BadRequestException("所选 Webhook 地址无法安全使用，请在系统设置中更新");
        }
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new BadRequestException("所选 Webhook 已停用，请在今日节奏中改选或启用它");
        }
        return new NotificationTarget(entity.getId(), entity.getName(), credential.url(), true);
    }

    /**
     * 注册表是否已经接管过配置。接管后即使用户删空，也不再回落到已废弃的部署级地址。
     */
    @Transactional(readOnly = true)
    public boolean registryInitialized() {
        return mapper.selectCount(null) > 0
                || settings.getBoolean(SettingService.FEISHU_WEBHOOK_REGISTRY_INITIALIZED);
    }

    /**
     * 兼容旧版单例保存接口：有默认项时原位更新，没有时用旧地址补齐一条默认配置。
     */
    @Transactional
    public Optional<FeishuWebhookEndpointDto> saveLegacyDefault(String url, Boolean enabled) {
        importLegacyDefaultIfNecessary();
        Optional<FeishuWebhookConfigEntity> current = defaultEntity();
        if (current.isPresent()) {
            FeishuWebhookConfigEntity entity = current.get();
            return Optional.of(update(entity.getId(), new SaveFeishuWebhookEndpointRequest(
                    entity.getName(), entity.getDescription(), url,
                    enabled == null ? entity.getEnabled() : enabled, true)));
        }
        String supplied = trim(url);
        String effectiveUrl = supplied.isEmpty() ? trim(settings.get(SettingService.FEISHU_WEBHOOK_URL)) : supplied;
        if (effectiveUrl.isEmpty()) {
            if (enabled != null) settings.put(SettingService.FEISHU_WEBHOOK_ENABLED, String.valueOf(enabled));
            return Optional.empty();
        }
        return Optional.of(create(new SaveFeishuWebhookEndpointRequest(
                "系统通知", "WAO 系统与项目通知的默认飞书群机器人", effectiveUrl,
                enabled == null ? settings.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED) : enabled, true)));
    }

    private List<FeishuWebhookConfigEntity> entities() {
        return mapper.selectList(new QueryWrapper<FeishuWebhookConfigEntity>()
                .orderByDesc("default_marker")
                .orderByDesc("updated_at")
                .orderByAsc("id"));
    }

    private Optional<FeishuWebhookConfigEntity> defaultEntity() {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<FeishuWebhookConfigEntity>()
                .eq("default_marker", SYSTEM_DEFAULT)
                .last("LIMIT 1")));
    }

    private FeishuWebhookConfigEntity required(String id) {
        FeishuWebhookConfigEntity entity = mapper.selectById(id);
        if (entity == null) throw new NotFoundException("飞书机器人配置不存在");
        return entity;
    }

    private void clearSystemDefault() {
        mapper.update(null, new UpdateWrapper<FeishuWebhookConfigEntity>()
                .eq("default_marker", SYSTEM_DEFAULT)
                .set("default_marker", null));
    }

    private void markInitialized() {
        settings.put(SettingService.FEISHU_WEBHOOK_REGISTRY_INITIALIZED, "true");
    }

    /**
     * V78 会无损搬运标准加密旧值；这里再接住历史明文和只存在于部署环境的兜底地址。
     * 固定 id 与数据库唯一默认标记让两个应用节点同时首次读取时最多插入一项。
     */
    private void importLegacyDefaultIfNecessary() {
        if (mapper.selectCount(null) > 0
                || settings.getBoolean(SettingService.FEISHU_WEBHOOK_REGISTRY_INITIALIZED)) {
            return;
        }
        String legacySettingUrl = "";
        boolean unreadableLegacySetting = false;
        try {
            legacySettingUrl = trim(settings.get(SettingService.FEISHU_WEBHOOK_URL));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // 非法 Base64、nonce/ciphertext 损坏或密钥不匹配都只转为“需重新填写”；
            // 不传播异常内容，避免首次打开列表或通知触发链路直接 500。
            unreadableLegacySetting = true;
        }
        boolean configuredInSettings = unreadableLegacySetting || !legacySettingUrl.isEmpty();
        String legacyUrl = unreadableLegacySetting ? ""
                : configuredInSettings ? legacySettingUrl : legacyDeploymentUrl;
        if (legacyUrl.isEmpty() && !unreadableLegacySetting) return;
        // 历史值先原样接管，之后统一由 credential() 判定是否可发送。超出列容量的旧脏值
        // 不再复制，只落一个不可用凭据供用户在列表中看到并替换，避免首次读取再次 500。
        String storableLegacyUrl = legacyUrl.getBytes(StandardCharsets.UTF_8).length
                <= MAX_STORED_PLAINTEXT_BYTES ? legacyUrl : "";
        VaultCryptoService.Encrypted encrypted = crypto.encrypt(storableLegacyUrl);
        LocalDateTime now = LocalDateTime.now();
        FeishuWebhookConfigEntity imported = new FeishuWebhookConfigEntity();
        imported.setId("00000000000000000000000000000001");
        imported.setName("系统通知（原配置）");
        imported.setDescription(unreadableLegacySetting
                ? "原全局飞书 Webhook 无法读取，请重新填写"
                : "由原全局飞书 Webhook 配置自动迁移");
        imported.setWebhookCiphertext(encrypted.ciphertext());
        imported.setWebhookNonce(encrypted.nonce());
        imported.setEnabled(!unreadableLegacySetting && (configuredInSettings
                ? settings.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED) : true));
        imported.setDefaultMarker(SYSTEM_DEFAULT);
        imported.setCreatedAt(now);
        imported.setUpdatedAt(now);
        try {
            mapper.insert(imported);
        } catch (DuplicateKeyException ignored) {
            // 另一应用节点已用同一固定 id / SYSTEM 唯一标记完成导入，读取它即可。
        }
        markInitialized();
    }

    private void syncLegacyDefault(FeishuWebhookConfigEntity entity) {
        Credential credential = credential(entity);
        settings.put(SettingService.FEISHU_WEBHOOK_URL, credential.usable() ? credential.url() : "");
        settings.put(SettingService.FEISHU_WEBHOOK_ENABLED,
                String.valueOf(credential.usable() && Boolean.TRUE.equals(entity.getEnabled())));
    }

    private FeishuWebhookEndpointDto toDto(FeishuWebhookConfigEntity entity) {
        Credential credential = credential(entity);
        return new FeishuWebhookEndpointDto(
                entity.getId(), entity.getName(), entity.getDescription(), credential.display(), credential.usable(),
                credential.usable() && Boolean.TRUE.equals(entity.getEnabled()), isDefault(entity),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private Credential credential(FeishuWebhookConfigEntity entity) {
        final String url;
        try {
            url = decryptStored(entity);
        } catch (IllegalStateException exception) {
            return Credential.unusable(UNREADABLE_URL_MASK);
        }
        try {
            String validated = validateUrl(url);
            return Credential.usable(validated, maskUrl(validated));
        } catch (BadRequestException exception) {
            return Credential.unusable(INVALID_URL_MASK);
        }
    }

    private String decryptStored(FeishuWebhookConfigEntity entity) {
        byte[] ciphertext = entity.getWebhookCiphertext();
        byte[] nonce = entity.getWebhookNonce();
        if (ciphertext == null || ciphertext.length == 0 || nonce == null || nonce.length == 0) {
            throw new IllegalStateException("飞书机器人密文不完整");
        }
        return crypto.decrypt(ciphertext, nonce);
    }

    private static boolean isDefault(FeishuWebhookConfigEntity entity) {
        return SYSTEM_DEFAULT.equals(entity.getDefaultMarker());
    }

    private static String requiredUrl(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) throw new BadRequestException("请填写飞书机器人 Webhook 地址");
        return validateUrl(value);
    }

    private static String validateUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("飞书机器人 Webhook 地址格式不正确");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String token = path.startsWith(HOOK_PATH) ? path.substring(HOOK_PATH.length()) : "";
        boolean valid = "https".equalsIgnoreCase(uri.getScheme())
                && FEISHU_HOSTS.contains(host)
                && uri.getUserInfo() == null
                && uri.getPort() == -1
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && HOOK_TOKEN.matcher(token).matches();
        if (!valid) {
            throw new BadRequestException("仅支持飞书官方 HTTPS 自定义机器人 Webhook 地址");
        }
        return value;
    }

    private static String maskUrl(String value) {
        if (value == null || value.isBlank()) return "";
        URI uri = URI.create(value);
        String path = uri.getRawPath();
        String token = path.substring(HOOK_PATH.length());
        String suffix = token.length() <= 4 ? "••••" : "••••" + token.substring(token.length() - 4);
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + HOOK_PATH + suffix;
    }

    private static String name(String value) {
        String normalized = trim(value);
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new BadRequestException("配置名称不能为空且不能超过 64 个字符");
        }
        return normalized;
    }

    private static String description(String value) {
        String normalized = trim(value);
        if (normalized.length() > 255) throw new BadRequestException("用途说明不能超过 255 个字符");
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String newId() {
        return "fw" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    }

    /** 服务端内部通知目标；url 含密钥，不得进入接口响应或日志。 */
    public record NotificationTarget(String id, String name, String url, boolean enabled) {
    }

    /** 解密后的内部凭据状态；不可用状态只携带固定安全提示，不保留异常或历史明文。 */
    private record Credential(String url, String display, boolean usable) {
        private static Credential usable(String url, String display) {
            return new Credential(url, display, true);
        }

        private static Credential unusable(String display) {
            return new Credential("", display, false);
        }
    }
}
