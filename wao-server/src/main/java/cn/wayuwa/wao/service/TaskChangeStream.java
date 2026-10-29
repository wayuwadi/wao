package cn.wayuwa.wao.service;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Member-scoped invalidations only: snapshots still pass through normal authorization. */
@Service
public class TaskChangeStream {
    private final ConcurrentHashMap<String, Set<SseEmitter>> clients = new ConcurrentHashMap<>();

    private final java.util.concurrent.ScheduledExecutorService heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "task-change-heartbeat"); thread.setDaemon(true); return thread;
    });
    public TaskChangeStream() { heartbeat.scheduleWithFixedDelay(this::heartbeat, 15, 15, java.util.concurrent.TimeUnit.SECONDS); }
    @PreDestroy public void close() { heartbeat.shutdownNow(); clients.values().forEach(group -> group.forEach(SseEmitter::complete)); clients.clear(); }

    public SseEmitter subscribe(String member) {
        var emitter = new SseEmitter(60_000L);
        var group = clients.computeIfAbsent(member, key -> ConcurrentHashMap.newKeySet());
        // Bound open connections; each minute requires a freshly authenticated request.
        if (group.size() >= 12) {
            var oldest = group.iterator().next();
            group.remove(oldest);
            oldest.complete();
        }
        group.add(emitter);
        Runnable cleanup = () -> group.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> { cleanup.run(); emitter.complete(); });
        emitter.onError(error -> cleanup.run());
        send(group, emitter, "ready");
        return emitter;
    }

    public void changed(String member) {
        var group = member == null ? null : clients.get(member);
        if (group != null) for (var emitter : group) send(group, emitter, "changed");
    }

    public void heartbeat() {
        clients.values().forEach(group -> group.forEach(emitter -> send(group, emitter, "heartbeat")));
    }

    private void send(Set<SseEmitter> group, SseEmitter emitter, String event) {
        try { emitter.send(SseEmitter.event().name(event).data("refresh")); }
        catch (IOException | RuntimeException error) { group.remove(emitter); emitter.complete(); }
    }
}
