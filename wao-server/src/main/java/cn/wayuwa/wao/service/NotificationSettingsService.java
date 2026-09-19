package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.GroupNotificationDecisionDto;
import cn.wayuwa.wao.dto.NotificationSettingsDto;
import cn.wayuwa.wao.dto.NotificationSettingsDto.PersonalPolicy;
import cn.wayuwa.wao.dto.NotificationSettingsDto.SystemPolicy;
import cn.wayuwa.wao.dto.SaveNotificationSettingsRequest;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final SystemPolicy DEFAULT_SYSTEM = new SystemPolicy(
            true, false, "22:00", "08:00",
            false, false, false, true, true, true, true, true);
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
            settings.put(SYSTEM_POLICY_KEY, write(validate(request.system())));
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
                    bool(node, "quietHoursEnabled", DEFAULT_SYSTEM.quietHoursEnabled()),
                    time(node, "quietStart", DEFAULT_SYSTEM.quietStart()),
                    time(node, "quietEnd", DEFAULT_SYSTEM.quietEnd()),
                    bool(node, "taskActivity", DEFAULT_SYSTEM.taskActivity()),
                    bool(node, "taskSuccess", DEFAULT_SYSTEM.taskSuccess()),
                    bool(node, "aiRunSuccess", DEFAULT_SYSTEM.aiRunSuccess()),
                    bool(node, "aiRunFailure", DEFAULT_SYSTEM.aiRunFailure()),
                    bool(node, "manualTask", DEFAULT_SYSTEM.manualTask()),
                    bool(node, "reminders", DEFAULT_SYSTEM.reminders()),
                    bool(node, "deliveryFailure", DEFAULT_SYSTEM.deliveryFailure()),
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
        return allows(scene, LocalDateTime.now(ZONE));
    }

    /** 在指定上海时间判断群通知，供跨午夜沉默窗口测试和定时任务复用。 */
    public boolean allows(java.util.function.Predicate<SystemPolicy> scene, LocalDateTime now) {
        SystemPolicy policy = systemPolicy();
        return policy.enabled() && !isQuiet(policy, now.toLocalTime()) && scene.test(policy);
    }

    /** 返回 Jenkins 等外部发送端可消费的门禁结果，不暴露完整组织配置。 */
    public GroupNotificationDecisionDto deliveryFailureDecision() {
        SystemPolicy policy = systemPolicy();
        if (!policy.enabled()) return new GroupNotificationDecisionDto(false, "群通知总开关已关闭");
        if (!policy.deliveryFailure()) return new GroupNotificationDecisionDto(false, "Jenkins 失败通知已关闭");
        if (isQuiet(policy, LocalDateTime.now(ZONE).toLocalTime())) {
            return new GroupNotificationDecisionDto(false,
                    "当前处于沉默时段 " + policy.quietStart() + "–" + policy.quietEnd());
        }
        return new GroupNotificationDecisionDto(true, "允许发送");
    }

    /** Jenkins 成功通知遵守群通知总开关和沉默时段，独立于失败通知开关。 */
    public GroupNotificationDecisionDto deliverySuccessDecision() {
        return deliverySuccessDecision(LocalDateTime.now(ZONE));
    }

    GroupNotificationDecisionDto deliverySuccessDecision(LocalDateTime now) {
        SystemPolicy policy = systemPolicy();
        if (!policy.enabled()) return new GroupNotificationDecisionDto(false, "群通知总开关已关闭");
        if (isQuiet(policy, now.toLocalTime())) {
            return new GroupNotificationDecisionDto(false,
                    "当前处于沉默时段 " + policy.quietStart() + "–" + policy.quietEnd());
        }
        return new GroupNotificationDecisionDto(true, "允许发送");
    }

    /** 当前是否允许群提醒；私聊提醒不受系统群通知策略影响。 */
    public boolean allowsReminders() {
        return allows(SystemPolicy::reminders);
    }

    /** 当前群通知被总开关或沉默时段阻止时的可操作说明。 */
    public String blockedReason() {
        SystemPolicy policy = systemPolicy();
        if (!policy.enabled()) return "群通知总开关已关闭";
        if (isQuiet(policy, LocalDateTime.now(ZONE).toLocalTime())) {
            return "当前处于群通知沉默时段 " + policy.quietStart() + "–" + policy.quietEnd();
        }
        return "该类群通知已关闭";
    }

    /** 当前成员是否可以维护全组织共享的通知策略和每日提醒。 */
    public boolean canManageSystem(String memberId) {
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

    private SystemPolicy validate(SystemPolicy policy) {
        String start = normalizeTime(policy.quietStart(), "沉默开始时间");
        String end = normalizeTime(policy.quietEnd(), "沉默结束时间");
        if (policy.quietHoursEnabled() && start.equals(end)) {
            throw new BadRequestException("沉默开始时间和结束时间不能相同");
        }
        return new SystemPolicy(policy.enabled(), policy.quietHoursEnabled(), start, end,
                policy.taskActivity(), policy.taskSuccess(), policy.aiRunSuccess(), policy.aiRunFailure(),
                policy.manualTask(), policy.reminders(), policy.deliveryFailure(), policy.automation());
    }

    private static boolean isQuiet(SystemPolicy policy, LocalTime now) {
        if (!policy.quietHoursEnabled()) return false;
        LocalTime start = LocalTime.parse(policy.quietStart(), TIME);
        LocalTime end = LocalTime.parse(policy.quietEnd(), TIME);
        if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
        return !now.isBefore(start) || now.isBefore(end);
    }

    private static String time(JsonNode node, String key, String fallback) {
        if (!node.path(key).isTextual()) return fallback;
        try {
            return LocalTime.parse(node.path(key).asText(), TIME).format(TIME);
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static String normalizeTime(String value, String label) {
        try {
            return LocalTime.parse(value == null ? "" : value.trim(), TIME).format(TIME);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException(label + "必须是 HH:mm 格式");
        }
    }

    private static boolean bool(JsonNode node, String key, boolean fallback) {
        return node.has(key) && node.path(key).isBoolean() ? node.path(key).asBoolean() : fallback;
    }
}
