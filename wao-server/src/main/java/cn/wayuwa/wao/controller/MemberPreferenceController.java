package cn.wayuwa.wao.controller;

import java.util.Map;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
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

    private final MemberPreferenceMapper mapper;
    private final CurrentMember currentMember;

    public MemberPreferenceController(MemberPreferenceMapper mapper, CurrentMember currentMember) {
        this.mapper = mapper;
        this.currentMember = currentMember;
    }

    @GetMapping("/{key}")
    public ApiResponse<Map<String, String>> get(@PathVariable String key, HttpServletRequest http) {
        String value = mapper.find(currentMember.idOr(http, FALLBACK_OWNER), key);
        return ApiResponse.ok(Map.of("value", value == null ? "" : value));
    }

    @PutMapping("/{key}")
    public ApiResponse<Void> put(@PathVariable String key, @RequestBody Map<String, String> body,
            HttpServletRequest http) {
        String value = body.get("value");
        if (cn.wayuwa.wao.service.NavigationLayoutService.PREFERENCE_KEY.equals(key)) {
            throw new BadRequestException("请使用个人导航接口保存布局，以保护跨设备修改");
        }
        if (value == null || value.length() > MAX_VALUE_LENGTH) {
            throw new BadRequestException("偏好值不能为空且不超过 " + MAX_VALUE_LENGTH + " 字符");
        }
        mapper.upsert(currentMember.idOr(http, FALLBACK_OWNER), key, value);
        return ApiResponse.ok(null);
    }
}
