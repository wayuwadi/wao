package cn.wayuwa.wao.auth;

import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.mapper.MemberMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 全局飞书密钥与机器人能力只允许真实管理员会话操作，不接受设备或工作区令牌。 */
@Component
public class FeishuBotAdminInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final CurrentMember current;
    private final MemberMapper members;
    public FeishuBotAdminInterceptor(CurrentMember current, MemberMapper members) {
        this.current=current; this.members=members;
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/settings/webhooks", "/api/settings/webhooks/**",
                "/api/settings/feishu-webhook", "/api/settings/feishu-webhook/**",
                "/api/feishu/chat-sync/**", "/api/feishu/quick-collect/config");
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String memberId = current.id(request);
        var member = memberId == null ? null : members.selectById(memberId);
        if (member == null || !("管理员".equals(member.getRole()) || "超级管理员".equals(member.getRole())))
            throw new UnauthorizedException("只有管理员会话可以管理全局飞书机器人配置");
        return true;
    }
}
