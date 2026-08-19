package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.SystemSettingEntity;
import cn.wayuwa.wao.mapper.SystemSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统级配置读写：全局唯一一份的键值配置（飞书通知 webhook、群聊同步凭据与映射等）。
 *
 * <p>敏感值（{@link #SECRET_KEYS}）用 {@link VaultCryptoService} 的 AES-256-GCM 加密后落库，
 * 密文以 {@value #ENC_PREFIX} 前缀标记；接口层只回 {@link #mask 脱敏形态}，明文只在服务端
 * 调飞书接口时解出来用。真实凭据因此既不进 Git，也不会被前端读走。
 */
@Service
public class SettingService {

    /** 飞书通知 webhook（全局一个，见需求「全局使用一个就可以了」）。 */
    public static final String FEISHU_WEBHOOK_URL = "feishu.webhook.url";
    public static final String FEISHU_WEBHOOK_ENABLED = "feishu.webhook.enabled";
    /** 公开展示的网站信息与 ICP 备案信息。 */
    public static final String SITE_NAME = "site.name";
    public static final String SITE_DESCRIPTION = "site.description";
    public static final String SITE_ICP_NUMBER = "site.icp-number";
    public static final String SITE_ICP_URL = "site.icp-url";
    /** WAO 壳内的自媒体 Agent 菜单；独立应用本身不依赖这些展示配置。 */
    public static final String MEDIA_AGENT_MENU_LABEL = "navigation.media-agent.label";
    public static final String MEDIA_AGENT_MENU_GROUP = "navigation.media-agent.group";
    public static final String MEDIA_AGENT_MENU_ORDER = "navigation.media-agent.order";
    public static final String MEDIA_AGENT_MENU_VISIBLE = "navigation.media-agent.visible";
    /** 通用外部应用菜单注册表（版本化 JSON）；为空时从上面的 Agent 旧键兼容读取。 */
    public static final String EXTERNAL_APP_REGISTRY = "navigation.external-apps";
    public static final String BUILTIN_MENU_REGISTRY = "navigation.builtin-menus";
    /** 飞书群聊同步：自建应用凭据、目标群与落点知识库。 */
    public static final String CHAT_SYNC_APP_ID = "feishu.chat-sync.app-id";
    public static final String CHAT_SYNC_APP_SECRET = "feishu.chat-sync.app-secret";
    public static final String CHAT_SYNC_CHAT_ID = "feishu.chat-sync.chat-id";
    public static final String CHAT_SYNC_CHAT_NAME = "feishu.chat-sync.chat-name";
    public static final String CHAT_SYNC_KB_ID = "feishu.chat-sync.kb-id";
    public static final String CHAT_SYNC_FOLDER_ID = "feishu.chat-sync.folder-id";
    /** 发言人映射：JSON 数组 [{"id":"ou_x","name":"娃哥"}]。 */
    public static final String CHAT_SYNC_SENDERS = "feishu.chat-sync.senders";
    /** 聊天复盘固定的群 id 和两位发言人 open_id（非敏感 JSON）。 */
    public static final String CHAT_REVIEW_SPEAKERS = "feishu.chat-review.speakers";
    /** 群聊快速收集：@机器人 直接建任务的总开关。 */
    public static final String QUICK_COLLECT_ENABLED = "feishu.quick-collect.enabled";
    /** 快速收集出来的任务默认落到哪个项目。 */
    public static final String QUICK_COLLECT_PROJECT_ID = "feishu.quick-collect.project-id";
    /** 事件订阅的 Verification Token（可选，配了就校验）。 */
    public static final String QUICK_COLLECT_VERIFY_TOKEN = "feishu.quick-collect.verification-token";
    /** 事件订阅的 Encrypt Key（可选，配了就解密事件体）。 */
    public static final String QUICK_COLLECT_ENCRYPT_KEY = "feishu.quick-collect.encrypt-key";
    /** 任务进入「收集中」时要 @ 的协作成员 open_id，逗号分隔；留空即只发通知不 @ 人。 */
    public static final String QUICK_COLLECT_MENTIONS = "feishu.quick-collect.mention-open-ids";

    /** 这些键的值加密存储、对外脱敏。 */
    private static final Set<String> SECRET_KEYS = Set.of(FEISHU_WEBHOOK_URL, CHAT_SYNC_APP_SECRET,
            QUICK_COLLECT_VERIFY_TOKEN, QUICK_COLLECT_ENCRYPT_KEY);
    private static final String ENC_PREFIX = "enc:";

    private final SystemSettingMapper mapper;
    private final VaultCryptoService crypto;

    public SettingService(SystemSettingMapper mapper, VaultCryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    /**
     * 读一个配置项的明文。
     *
     * @param key 配置键
     * @return 明文值；没配过时返回 null
     */
    public String get(String key) {
        SystemSettingEntity e = mapper.selectById(key);
        return e == null ? null : decode(e.getCfgValue());
    }

    /**
     * 读一个配置项，缺省时回落。
     *
     * @param key      配置键
     * @param fallback 没配过或为空时返回的值
     * @return 明文值或 fallback
     */
    public String get(String key, String fallback) {
        String value = get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 在同一数据库快照中读取一组配置，避免多行配置保存时被调用方读到新旧混合值。
     *
     * @param keys 配置键
     * @return 已解密值；不存在的键映射为 null
     */
    @Transactional(readOnly = true)
    public Map<String, String> getAll(Set<String> keys) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : keys) values.put(key, null);
        for (SystemSettingEntity setting : mapper.selectBatchIds(keys)) {
            values.put(setting.getCfgKey(), decode(setting.getCfgValue()));
        }
        return values;
    }

    /** 读一个布尔配置项（只有字面量 {@code true} 算真）。 */
    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(get(key));
    }

    /**
     * 写一个配置项。传 null 表示不改；传空串表示清空。
     *
     * <p>「传 null 不改」是为了让前端能提交一份不含密文的表单：密码框留空即保留原值，
     * 用户不必每次保存都重新粘一遍 App Secret。
     *
     * @param key   配置键
     * @param value 明文值，null 时保持原样
     */
    public void put(String key, String value) {
        if (value == null) {
            return;
        }
        SystemSettingEntity e = mapper.selectById(key);
        boolean insert = e == null;
        if (insert) {
            e = new SystemSettingEntity();
            e.setCfgKey(key);
        }
        e.setCfgValue(encode(key, value));
        e.setUpdatedAt(LocalDateTime.now());
        if (insert) {
            mapper.insert(e);
        } else {
            mapper.updateById(e);
        }
    }

    /**
     * 脱敏展示：密钥只留首尾各 4 个字符，webhook 只留域名与末尾。
     *
     * @param value 明文值
     * @return 可以安全回给前端的形态；空值返回空串
     */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String v = value.trim();
        if (v.length() <= 8) {
            return "••••";
        }
        return v.substring(0, 4) + "••••••" + v.substring(v.length() - 4);
    }

    private String encode(String key, String value) {
        if (!SECRET_KEYS.contains(key) || value.isBlank()) {
            return value;
        }
        VaultCryptoService.Encrypted enc = crypto.encrypt(value);
        return ENC_PREFIX + Base64.getEncoder().encodeToString(enc.nonce())
                + ":" + Base64.getEncoder().encodeToString(enc.ciphertext());
    }

    private String decode(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) {
            return stored;
        }
        String[] parts = stored.substring(ENC_PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            return "";
        }
        byte[] nonce = Base64.getDecoder().decode(parts[0]);
        byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
        return crypto.decrypt(ciphertext, nonce);
    }
}
