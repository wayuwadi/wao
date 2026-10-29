package cn.wayuwa.wao.config;

import cn.wayuwa.wao.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 覆盖正文读取至响应写出，避免并发文件请求同时持有多个大字节数组。 */
@Component
public class FileTransferLimitFilter extends OncePerRequestFilter {
    private final Semaphore permits;
    private final ObjectMapper json;

    public FileTransferLimitFilter(ObjectMapper json,
            @Value("${wao.files.max-concurrent-transfers:2}") int concurrency) {
        if (concurrency < 1 || concurrency > 16)
            throw new IllegalArgumentException("File transfer concurrency must be between 1 and 16");
        this.json = json;
        permits = new Semaphore(concurrency);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        String method = request.getMethod();
        return !("POST".equals(method) && path.equals("/api/files")
                || "PUT".equals(method) && path.matches("/api/files/[^/]+/content")
                || ("GET".equals(method) || "HEAD".equals(method))
                        && path.matches("/api/files/[^/]+/(download|stream|preview|public-preview)"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            response.setStatus(503);
            response.setHeader("Retry-After", "2");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getWriter(), ApiResponse.fail(50301, "文件传输繁忙，请稍后重试"));
            return;
        }
        try { chain.doFilter(request, response); }
        finally { permits.release(); }
    }
}
