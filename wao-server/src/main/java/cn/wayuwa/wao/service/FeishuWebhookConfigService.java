package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
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
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
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
    private static final String LEGACY_TASK_RETIRED = "feishu.bot-center.legacy-task-retired";
    private static final String LEGACY_NOTE_RETIRED = "feishu.bot-center.legacy-note-retired";
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
    private FeishuBotActivityService activity;

    /** 注入真实引用查询；旧单元调用保持可独立构造。 */
    @Autowired
    public void setActivityService(FeishuBotActivityService activity) { this.activity = activity; }

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
        if (systemDefault && count > 0 && !Boolean.TRUE.equals(request.impactConfirmed()))
            throw new ConflictException("切换系统默认会影响现有通知，请核对影响后确认");
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
        entity.setRevision(0L);
        applyCapture(entity, request);
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
        long previousRevision = revision(entity);
        if (request.revision() != null && request.revision() != previousRevision)
            throw new ConflictException("机器人配置已更新，请重新加载后保存");
        boolean impact = Boolean.TRUE.equals(entity.getEnabled()) && !Boolean.TRUE.equals(request.enabled())
                || request.captureKind() != null && !request.captureKind().equals(kind(entity))
                || request.mentionId() != null && !trim(request.mentionId()).equals(trim(entity.getMentionId()))
                || request.mentionName() != null && !trim(request.mentionName()).equals(trim(entity.getMentionName()))
                || Boolean.TRUE.equals(request.systemDefault()) && !isDefault(entity);
        if (impact && activity != null && (!activity.usages(entity).isEmpty()
                || Boolean.TRUE.equals(request.systemDefault())) && !Boolean.TRUE.equals(request.impactConfirmed()))
            throw new ConflictException("此变更会影响已引用的通知或收集，请核对影响后确认");
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
        applyCapture(entity, request);
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
        entity.setRevision(previousRevision + 1);
        if (mapper.update(entity, new UpdateWrapper<FeishuWebhookConfigEntity>()
                .eq("id", id).eq("revision", previousRevision)) != 1)
            throw new ConflictException("机器人配置已更新，请重新加载后保存");
        markInitialized();
        if (isDefault(entity)) syncLegacyDefault(entity);
        return toDto(entity);
    }

    /** 删除一项配置；删除系统默认项时自动把最近修改的剩余项提升为默认。 */
    @Transactional
    public void delete(String id) {
        delete(id, null, false);
    }

    /** 删除前核对版本及真实引用；有引用时必须提交影响确认。 */
    @Transactional
    public void delete(String id, Long expectedRevision, boolean impactConfirmed) {
        FeishuWebhookConfigEntity deleted = required(id);
        if (expectedRevision != null && expectedRevision != revision(deleted))
            throw new ConflictException("机器人配置已更新，请重新加载后删除");
        if (activity != null && !activity.usages(deleted).isEmpty() && !impactConfirmed)
            throw new ConflictException("机器人仍被引用，请核对影响后确认删除");
        if (mapper.delete(new QueryWrapper<FeishuWebhookConfigEntity>().eq("id", id)
                .eq("revision", revision(deleted))) != 1)
            throw new ConflictException("机器人配置已更新，请重新加载后删除");
        retireLegacyName(deleted);
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
        promoted.setRevision(revision(promoted) + 1);
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
                .setSql("revision = revision + 1")
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
                entity.getCreatedAt(), entity.getUpdatedAt(), kind(entity), trim(entity.getMentionId()),
                trim(entity.getMentionName()), trim(entity.getChatId()), trim(entity.getAppId()), revision(entity));
    }

    /** 根据精确 @ 元数据选择唯一启用路由；未知 @、冲突和已停用路由一律拒绝。 */
    @Transactional(readOnly = true)
    public CaptureRoute captureRoute(JsonNode mentions) {
        List<FeishuWebhookConfigEntity> all = entities();
        CaptureRoute matched = null;
        for (JsonNode mention : mentions) {
            String mentionId = mention.path("id").path("open_id").asText("");
            String mentionName = mention.path("name").asText("");
            boolean reserved = "任务待办".equals(mentionName) && settings.getBoolean(LEGACY_TASK_RETIRED)
                    || "小记创作".equals(mentionName) && settings.getBoolean(LEGACY_NOTE_RETIRED);
            for (FeishuWebhookConfigEntity bot : all) {
                boolean explicit = !trim(bot.getMentionId()).isEmpty() || !trim(bot.getMentionName()).isEmpty();
                boolean same = !trim(bot.getMentionId()).isEmpty()
                        ? bot.getMentionId().equals(mentionId)
                        : explicit && bot.getMentionName().equals(mentionName);
                // 旧名称一旦明确绑定或停用，绝不能借名称兼容绕开。
                if (mentionName.equals(bot.getName()) && (explicit || !Boolean.TRUE.equals(bot.getEnabled()))) reserved = true;
                if (!same) continue;
                reserved = true;
                if (!Boolean.TRUE.equals(bot.getEnabled()) || "NONE".equals(kind(bot))) return null;
                String currentApp = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
                String currentChat = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
                if (!trim(bot.getAppId()).equals(currentApp) || !trim(bot.getChatId()).equals(currentChat)) return null;
                CaptureRoute route = new CaptureRoute(bot.getId(), kind(bot));
                if (matched != null && !matched.equals(route)) return null;
                matched = route;
            }
            if (!reserved && ("任务待办".equals(mentionName) || "小记创作".equals(mentionName))) {
                CaptureRoute legacy = new CaptureRoute(null, "小记创作".equals(mentionName) ? "NOTE" : "TASK");
                if (matched != null && !matched.equals(legacy)) return null;
                matched = legacy;
            }
        }
        return matched;
    }

    private void applyCapture(FeishuWebhookConfigEntity entity, SaveFeishuWebhookEndpointRequest request) {
        if (request.captureKind() == null) {
            if (entity.getCaptureKind() == null) entity.setCaptureKind("NONE");
            return;
        }
        String kind = trim(request.captureKind());
        if (!Set.of("NONE", "TASK", "NOTE").contains(kind)) throw new BadRequestException("请选择有效的收集角色");
        String mentionId = request.mentionId() == null ? trim(entity.getMentionId()) : trim(request.mentionId());
        String mentionName = request.mentionName() == null ? trim(entity.getMentionName()) : trim(request.mentionName());
        if (mentionName.length() > 64 || !mentionId.isEmpty() && (!mentionId.matches("[A-Za-z0-9_-]{1,128}") || mentionId.equals(mentionName)))
            throw new BadRequestException("稳定标识须填写飞书真实 ID；不知道时请留空，按机器人 @ 名称匹配");
        if (!"NONE".equals(kind)) {
            if (mentionName.isEmpty() && mentionId.isEmpty()) mentionName = "NOTE".equals(kind) ? "小记创作" : "任务待办";
            String app = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String chat = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
            if (trim(app).isEmpty() || trim(chat).isEmpty() || trim(settings.get(SettingService.CHAT_SYNC_APP_SECRET)).isEmpty())
                throw new BadRequestException("请先配置飞书应用和目标群，再关联收集机器人");
            if (request.appId() != null && !request.appId().equals(app)
                    || request.chatId() != null && !request.chatId().equals(chat))
                throw new ConflictException("飞书应用或目标群已改变，请重新加载后关联");
            entity.setAppId(app); entity.setChatId(chat);
            for (FeishuWebhookConfigEntity other : entities()) {
                if (entity.getId().equals(other.getId()) || "NONE".equals(kind(other))) continue;
                if (!mentionId.isEmpty() && mentionId.equals(other.getMentionId())
                        || !mentionName.isEmpty() && mentionName.equals(other.getMentionName()))
                    throw new ConflictException("这个 @ 身份已被另一个收集机器人使用");
            }
        }
        entity.setCaptureKind(kind); entity.setMentionId(mentionId); entity.setMentionName(mentionName);
    }

    private static String kind(FeishuWebhookConfigEntity entity) {
        return trim(entity.getCaptureKind()).isEmpty() ? "NONE" : entity.getCaptureKind();
    }
    private static long revision(FeishuWebhookConfigEntity entity) {
        return entity.getRevision() == null ? 0 : entity.getRevision();
    }

    /** 精确匹配的收集目标；旧名称兼容路由没有 Webhook id。 */
    public record CaptureRoute(String webhookId, String kind) { }

    private void retireLegacyName(FeishuWebhookConfigEntity entity) {
        if ("任务待办".equals(entity.getName()) || "任务待办".equals(entity.getMentionName()))
            settings.put(LEGACY_TASK_RETIRED, "true");
        if ("小记创作".equals(entity.getName()) || "小记创作".equals(entity.getMentionName()))
            settings.put(LEGACY_NOTE_RETIRED, "true");
    }

    /** 根据真正使用的地址记发送结果；地址明文只在本方法内比较，不保留。 */
    public void recordSend(String url, String kind, boolean success) {
        if (activity == null || trim(url).isEmpty()) return;
        for (FeishuWebhookConfigEntity entity : entities()) {
            Credential candidate = credential(entity);
            if (candidate.usable() && candidate.url().equals(url)) {
                activity.recordSend(entity.getId(), kind, success);
                return;
            }
        }
    }

    /** 显式指定目标的发送按稳定 id 记录，即使两条注册记录使用同一个地址也不串记录。 */
    public void recordSendForId(String id, String kind, boolean success) {
        if (activity != null) activity.recordSend(id, kind, success);
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
