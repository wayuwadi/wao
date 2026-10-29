package cn.wayuwa.wao.controller;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 在 ResponseEntity 写出之前强制覆盖私有素材的历史公开缓存头。 */
@RestControllerAdvice
public class NotePrivacyResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter parameter,Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body,MethodParameter parameter,MediaType media,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
        String path=request.getURI().getPath();
        if(path.startsWith("/api/share/") || path.matches("/api/docs/[^/]+/public-shares(?:/[^/]+)?")
                || request instanceof ServletServerHttpRequest servlet && Boolean.TRUE.equals(servlet.getServletRequest().getAttribute("wao.private-note-asset"))) {
            response.getHeaders().set("Cache-Control","private, no-store");
            response.getHeaders().set("X-Robots-Tag","noindex, nofollow, noarchive");
            response.getHeaders().set("Referrer-Policy","no-referrer");
            // MVC 写头时可能追加到拦截器已设置的 Servlet 响应，须同步覆盖底层旧值。
            if(response instanceof ServletServerHttpResponse servletResponse) {
                var raw=servletResponse.getServletResponse();
                raw.setHeader("Cache-Control","private, no-store");
                raw.setHeader("X-Robots-Tag","noindex, nofollow, noarchive");
                raw.setHeader("Referrer-Policy","no-referrer");
            }
        }
        return body;
    }
}
