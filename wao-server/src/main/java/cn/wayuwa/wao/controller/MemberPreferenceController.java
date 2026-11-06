package cn.wayuwa.wao.controller;

import java.util.Map;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 成员级偏好键值接口（如左侧菜单顺序），按登录成员维度隔离。 */
@RestController
@RequestMapping("/api/preferences")
public class MemberPreferenceController {

    private static final String FALLBACK_OWNER = "wg";
    private static final int MAX_VALUE_LENGTH = 4000;
    private static final java.util.Set<String> TASK_ORGANIZATION_KEYS = java.util.Set.of(
            "todo-named-filters-v1", "todo-list-folders-v1");

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.wayuwa.wao.service.NoteAiSettingsService noteSettings;
    private final MemberPreferenceMapper mapper;
    private final CurrentMember currentMember;

    public MemberPreferenceController(MemberPreferenceMapper mapper, CurrentMember currentMember) {
        this.mapper = mapper;
        this.currentMember = currentMember;
    }

    @GetMapping("/{key}")
    public ApiResponse<Map<String, String>> get(@PathVariable String key, HttpServletRequest http) {
        String value = mapper.find(TASK_ORGANIZATION_KEYS.contains(key) ? taskOrganizationOwner(http)
                : currentMember.idOr(http, FALLBACK_OWNER), key);
        return ApiResponse.ok(Map.of("value", value == null ? "" : value));
    }

    @PutMapping("/{key}")
    public ApiResponse<Void> put(@PathVariable String key, @RequestBody Map<String, String> body,
            HttpServletRequest http) {
        String value = body.get("value");
        if (key.startsWith(SmartListVisibilityController.PREFIX)) {
            throw new BadRequestException("请使用智能清单显示设置接口，以保护跨设备修改");
        }
        if (cn.wayuwa.wao.service.HomeDateReminderService.SETTINGS_KEY.equals(key)
                || key.startsWith(cn.wayuwa.wao.service.HomeDateReminderService.DELIVERY_PREFIX)) {
            throw new BadRequestException("请使用首页日期提醒接口，以保护版本和展示回执");
        }
        if (cn.wayuwa.wao.service.TianrenSettingsService.KEY.equals(key)) {
            throw new BadRequestException("请使用天人一卦设置接口保存，以保护跨设备修改");
        }
        if (cn.wayuwa.wao.service.NoteDiscoveryService.PREFERENCE_KEY.equals(key)) {
            throw new BadRequestException("请使用自动发现设置接口保存，以保护暂停及跨设备修改");
        }
        if (cn.wayuwa.wao.service.NavigationLayoutService.PREFERENCE_KEY.equals(key)
                || java.util.Set.of("nav-personal-order", "mobile-bottom-nav-v1", "nav-top-shortcuts-v1", "quick-links").contains(key)) {
            throw new BadRequestException("菜单由开发人员统一维护，不再支持个人配置");
        }
        int limit = TASK_ORGANIZATION_KEYS.contains(key) || key.startsWith("task-order-v1:") || key.startsWith("project-layout-v1:")
                ? 60_000 : MAX_VALUE_LENGTH;
        if (value == null || value.length() > limit) {
            throw new BadRequestException("偏好值不能为空且不超过 " + limit + " 字符");
        }
        if (TASK_ORGANIZATION_KEYS.contains(key)) {
            if (!body.containsKey("expected") || body.get("expected") == null)
                throw new BadRequestException("请先读取账号配置，再提交 expected 版本以保护跨设备修改");
            String owner = taskOrganizationOwner(http);
            String previous = mapper.find(owner, key);
            // A lost acknowledgement may safely retry the exact same value.
            if (java.util.Objects.equals(previous, value)) return ApiResponse.ok(null);
            if (!java.util.Objects.equals(previous == null ? "" : previous, body.get("expected")))
                throw new ConflictException("账号配置已被其他窗口修改，请保留草稿并重新读取");
            int changed = previous == null ? mapper.insertIfAbsent(owner, key, value)
                    : mapper.compareAndSet(owner, key, previous, value);
            if (changed != 1 && !java.util.Objects.equals(mapper.find(owner, key), value))
                throw new ConflictException("账号配置已被其他窗口修改，请保留草稿并重新读取");
            return ApiResponse.ok(null);
        }
        if (noteSettings != null && cn.wayuwa.wao.service.NoteAiSettingsService.KEYS.contains(key)) noteSettings.saveLegacy(currentMember.id(http), key, value);
        else mapper.upsert(currentMember.idOr(http, FALLBACK_OWNER), key, value);
        return ApiResponse.ok(null);
    }

    private String taskOrganizationOwner(HttpServletRequest request) {
        String owner = currentMember.id(request);
        if (owner == null || owner.isBlank())
            throw new cn.wayuwa.wao.common.UnauthorizedException("请登录后读取或保存个人清单配置");
        return owner;
    }
}
