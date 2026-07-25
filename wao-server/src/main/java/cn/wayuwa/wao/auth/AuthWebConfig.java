package cn.wayuwa.wao.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 认证拦截器与密码哈希器配置。 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {
    private final ObjectProvider<AuthInterceptor> interceptor;

    public AuthWebConfig(ObjectProvider<AuthInterceptor> interceptor) {
        this.interceptor = interceptor;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        AuthInterceptor available = interceptor.getIfAvailable();
        if (available != null) registry.addInterceptor(available).addPathPatterns("/api/**");
    }
}
