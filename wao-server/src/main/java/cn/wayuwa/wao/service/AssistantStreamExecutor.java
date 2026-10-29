package cn.wayuwa.wao.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 限制连接等待线程；浏览器离开只取消等待，不取消已持久化的 AI 任务。 */
@Component
public class AssistantStreamExecutor {
    private final ThreadPoolExecutor workers;

    public AssistantStreamExecutor(@Value("${wao.ai.stream.max-concurrency:16}") int concurrency) {
        if (concurrency < 1 || concurrency > 128)
            throw new IllegalArgumentException("AI stream concurrency must be between 1 and 128");
        workers = new ThreadPoolExecutor(0, concurrency, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "wao-assistant-stream");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public SseEmitter submit(SseEmitter emitter, Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        emitter.onCompletion(() -> task.cancel(true));
        emitter.onTimeout(() -> task.cancel(true));
        emitter.onError(error -> task.cancel(true));
        try {
            workers.execute(task);
        } catch (RejectedExecutionException full) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 连接繁忙，请稍后重试");
        }
        return emitter;
    }

    @PreDestroy
    public void stop() { workers.shutdownNow(); }
}
