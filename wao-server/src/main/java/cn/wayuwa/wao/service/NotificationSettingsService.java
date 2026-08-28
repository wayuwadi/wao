package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.NotificationSettingsDto;
import cn.wayuwa.wao.dto.NotificationSettingsDto.PersonalPolicy;
import cn.wayuwa.wao.dto.NotificationSettingsDto.SystemPolicy;
import cn.wayuwa.wao.dto.SaveNotificationSettingsRequest;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 持久化并执行系统/成员两个隔离层级的通知策略。 */
@Service
public class NotificationSettingsService {
    public static final String SYSTEM_POLICY_KEY = SettingService.NOTIFICATION_POLICY;
    public static final String PERSONAL_POLICY_KEY = "notification-center-policy-v1";
    private static final Set<String> ADMIN_ROLES = Set.of("管理员", "超级管理员");

    private static final SystemPolicy DEFAULT_SYSTEM = new SystemPolicy(
            true, false, false, false, true, true, true);
    private static final PersonalPolicy DEFAULT_PERSONAL = new PersonalPolicy(
            true, true, true, true, true, true);

    private final SettingService settings;
    private final MemberPreferenceMapper preferences;
    private final MemberMapper members;
    private final ObjectMapper json;

    public NotificationSettingsService(SettingService settings, MemberPreferenceMapper preferences,
            MemberMapper members, ObjectMapper json) {
        this.settings = settings;
        this.preferences = preferences;
        this.members = members;
        this.json = json;
    }

    /** 返回系统共享策略和当前成员自己的偏好，并明确是否有权改系统层。 */
    public NotificationSettingsDto get(String memberId) {
        return new NotificationSettingsDto(canManageSystem(memberId), systemPolicy(), personalPolicy(memberId));
    }

    /**
     * 保存当前成员偏好；只有管理员提交 system 时才更新全组织共享策略。
     *
     * @param memberId 当前登录成员
     * @param request 完整草稿
     * @return 保存后的有效配置
     */
    @Transactional
    public NotificationSettingsDto save(String memberId, SaveNotificationSettingsRequest request) {
        if (request == null) return get(memberId);
        if (request.system() != null) {
            if (!canManageSystem(memberId)) throw new UnauthorizedException("只有管理员可以修改系统通知策略");
            settings.put(SYSTEM_POLICY_KEY, write(request.system()));
        }
        if (request.personal() != null) {
            requireMember(memberId);
            preferences.upsert(memberId, PERSONAL_POLICY_KEY, write(request.personal()));
        }
        return get(memberId);
    }

    /** 当前全组织共享的飞书通知策略；损坏或未保存时使用低噪音安全默认值。 */
    public SystemPolicy systemPolicy() {
        String value = settings.get(SYSTEM_POLICY_KEY, "");
        if (!StringUtils.hasText(value)) return DEFAULT_SYSTEM;
        try {
            JsonNode node = json.readTree(value);
            return new SystemPolicy(bool(node, "enabled", DEFAULT_SYSTEM.enabled()),
                    bool(node, "taskActivity", DEFAULT_SYSTEM.taskActivity()),
                    bool(node, "taskSuccess", DEFAULT_SYSTEM.taskSuccess()),
                    bool(node, "aiRunSuccess", DEFAULT_SYSTEM.aiRunSuccess()),
                    bool(node, "aiRunFailure", DEFAULT_SYSTEM.aiRunFailure()),
                    bool(node, "manualTask", DEFAULT_SYSTEM.manualTask()),
                    bool(node, "automation", DEFAULT_SYSTEM.automation()));
        } catch (Exception ignored) {
            return DEFAULT_SYSTEM;
        }
    }

    /** 当前成员独享的站内通知偏好；不会读取或覆盖其他成员。 */
    public PersonalPolicy personalPolicy(String memberId) {
        String value = StringUtils.hasText(memberId) ? preferences.find(memberId, PERSONAL_POLICY_KEY) : null;
        if (!StringUtils.hasText(value)) return DEFAULT_PERSONAL;
        try {
            JsonNode node = json.readTree(value);
            return new PersonalPolicy(bool(node, "enabled", DEFAULT_PERSONAL.enabled()),
                    bool(node, "taskCreated", DEFAULT_PERSONAL.taskCreated()),
                    bool(node, "taskCompleted", DEFAULT_PERSONAL.taskCompleted()),
                    bool(node, "aiRunSuccess", DEFAULT_PERSONAL.aiRunSuccess()),
                    bool(node, "aiRunFailure", DEFAULT_PERSONAL.aiRunFailure()),
                    bool(node, "loginActivity", DEFAULT_PERSONAL.loginActivity()));
        } catch (Exception ignored) {
            return DEFAULT_PERSONAL;
        }
    }

    /** 系统总开关和指定业务开关同时开启时才允许群推送。 */
    public boolean allows(java.util.function.Predicate<SystemPolicy> scene) {
        SystemPolicy policy = systemPolicy();
        return policy.enabled() && scene.test(policy);
    }

    private boolean canManageSystem(String memberId) {
        MemberEntity member = StringUtils.hasText(memberId) ? members.selectById(memberId) : null;
        return member != null && ADMIN_ROLES.contains(member.getRole());
    }

    private void requireMember(String memberId) {
        if (!StringUtils.hasText(memberId) || members.selectById(memberId) == null) {
            throw new UnauthorizedException("当前成员不存在或登录已失效");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("通知设置无法序列化", ex);
        }
    }

    private static boolean bool(JsonNode node, String key, boolean fallback) {
        return node.has(key) && node.path(key).isBoolean() ? node.path(key).asBoolean() : fallback;
    }
}
