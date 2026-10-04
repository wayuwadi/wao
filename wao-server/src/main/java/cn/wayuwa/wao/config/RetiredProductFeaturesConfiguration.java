package cn.wayuwa.wao.config;

import cn.wayuwa.wao.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/** 关闭已退役产品的全部专属接口；保留历史数据和仍被其它能力复用的服务。 */
@Configuration
public class RetiredProductFeaturesConfiguration {
    private static final Set<String> PATHS = Set.of("/api/life-reviews", "/api/system-reports",
            "/api/ideas", "/api/settings/network-outage-window", "/api/ai/application-skills");

    /** 在路由匹配及参数解析前拒绝旧请求，未知子路径与错误方法也返回一致的退役结果。 */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> retiredProductFeaturesFilter(ObjectMapper json) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (PATHS.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"))) {
                    response.setStatus(HttpServletResponse.SC_GONE);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    json.writeValue(response.getWriter(), ApiResponse.fail(41000, "该功能已下线"));
                    return;
                }
                chain.doFilter(request, response);
            }
        });
        registration.setOrder(-100);
        return registration;
    }
}
