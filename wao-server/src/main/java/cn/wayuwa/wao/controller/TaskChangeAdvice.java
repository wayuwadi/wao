package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.TaskChangeStream;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Runs after the transactional service has returned, never publishes failed writes. */
@RestControllerAdvice
public class TaskChangeAdvice implements ResponseBodyAdvice<Object> {
    private final java.util.function.Supplier<CurrentMember> member;
    private final java.util.function.Supplier<TaskChangeStream> stream;
    @org.springframework.beans.factory.annotation.Autowired
    public TaskChangeAdvice(org.springframework.beans.factory.ObjectProvider<CurrentMember> member,
            org.springframework.beans.factory.ObjectProvider<TaskChangeStream> stream) {
        this.member = member::getIfAvailable; this.stream = stream::getIfAvailable;
    }
    TaskChangeAdvice(CurrentMember member, TaskChangeStream stream) { this.member = () -> member; this.stream = () -> stream; }
    public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    public Object beforeBodyWrite(Object body, MethodParameter type, MediaType media,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> result && result.code() == 0
                && request instanceof ServletServerHttpRequest servlet
                && !READ_METHODS.contains(request.getMethod().name())
                && request.getURI().getPath().matches("/api/(tasks|task-labels|task-hierarchy|projects|todo)(/.*)?")) {
            if (stream.get() != null && member.get() != null) stream.get().changed(member.get().id(servlet.getServletRequest()));
        }
        return body;
    }
    private static final java.util.Set<String> READ_METHODS = java.util.Set.of("GET", "HEAD", "OPTIONS");
}
