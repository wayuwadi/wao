package cn.wayuwa.wao.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantStreamExecutorTest {
    @Test void executesAcceptedActionAndReturnsItsEmitter() throws Exception {
        var executor = new AssistantStreamExecutor(1);
        var emitter = new SseEmitter();
        var completed = new CountDownLatch(1);
        try {
            assertThat(executor.submit(emitter, completed::countDown)).isSameAs(emitter);
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        } finally { executor.stop(); }
    }

    @Test void saturationRejectsBeforeExecutingActionAndShutdownInterruptsTheWaiter() throws Exception {
        var executor = new AssistantStreamExecutor(1);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        Runnable rejected = mock(Runnable.class);
        try {
            executor.submit(new SseEmitter(), () -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.submit(new SseEmitter(), rejected))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            verifyNoInteractions(rejected);
            executor.stop();
            assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.submit(new SseEmitter(), rejected))
                    .isInstanceOf(ResponseStatusException.class);
            verifyNoInteractions(rejected);
        } finally { executor.stop(); }
    }

    @Test void completionTimeoutAndTransportErrorEachCancelWaitingAndAllowTheNextConnection() throws Exception {
        for (String event : new String[] { "completion", "timeout", "error" }) {
            var executor = new AssistantStreamExecutor(1);
            var emitter = mock(SseEmitter.class);
            var completion = new AtomicReference<Runnable>();
            var timeout = new AtomicReference<Runnable>();
            var error = new AtomicReference<Consumer<Throwable>>();
            doAnswer(call -> { completion.set(call.getArgument(0)); return null; }).when(emitter).onCompletion(any());
            doAnswer(call -> { timeout.set(call.getArgument(0)); return null; }).when(emitter).onTimeout(any());
            doAnswer(call -> { error.set(call.getArgument(0)); return null; }).when(emitter).onError(any());
            var started = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var nextCompleted = new CountDownLatch(1);
            try {
                executor.submit(emitter, () -> {
                    started.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                });
                assertThat(started.await(3, TimeUnit.SECONDS)).as(event).isTrue();
                switch (event) {
                    case "completion" -> completion.get().run();
                    case "timeout" -> timeout.get().run();
                    default -> error.get().accept(new java.io.IOException("disconnected"));
                }
                assertThat(interrupted.await(3, TimeUnit.SECONDS)).as(event).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (true) {
                    try { executor.submit(new SseEmitter(), nextCompleted::countDown); break; }
                    catch (ResponseStatusException stillReturningWorker) {
                        if (System.nanoTime() >= deadline) throw stillReturningWorker;
                        Thread.sleep(10);
                    }
                }
                assertThat(nextCompleted.await(3, TimeUnit.SECONDS)).as(event).isTrue();
            } finally { executor.stop(); }
        }
    }
}
