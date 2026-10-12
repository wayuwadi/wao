package cn.wayuwa.wao.controller;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 在 ResponseEntity 写出之前强制覆盖私有素材的历史公开缓存头。 */
@RestControllerAdvice
public class NotePrivacyResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter parameter,Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body,MethodParameter parameter,MediaType media,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
        if(request instanceof ServletServerHttpRequest servlet && Boolean.TRUE.equals(servlet.getServletRequest().getAttribute("wao.private-note-asset"))) {
            response.getHeaders().set("Cache-Control","private, no-store");
            response.getHeaders().set("X-Robots-Tag","noindex, nofollow, noarchive");
        }
        return body;
    }
}
