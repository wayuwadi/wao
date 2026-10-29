package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.mapper.InternalNotificationMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationsNotificationInboxTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final InternalNotificationMapper notifications = mock(InternalNotificationMapper.class);
    final MemberMapper members = mock(MemberMapper.class);

    OperationsNotificationInbox inbox(String recipient) {
        when(members.selectById("owner")).thenReturn(new MemberEntity());
        return new OperationsNotificationInbox(directory.toString(), recipient, json, notifications, members);
    }

    Path event(String status) throws Exception {
        String id = UUID.randomUUID().toString();
        Path file = directory.resolve(id + ".json");
        json.writeValue(file.toFile(), Map.of("id", id, "status", status, "title", "异常：Jenkins",
                "body", "连续两次检查异常", "occurredAt", "2026-10-29T00:00:00Z", "memberId", "other"));
        return file;
    }

    @Test void deliversToConfiguredRecipientAndDeletesOnlyAfterPersistence() throws Exception {
        Path file = event("FAILURE");
        inbox("owner").tick();
        verify(notifications).insertOperation(startsWith("ops:"), eq("owner"), eq("异常：Jenkins"), contains("连续两次"));
        assertThat(file).doesNotExist();
    }

    @Test void storageFailureRetainsSameEventForIdempotentRetry() throws Exception {
        Path file = event("RECOVERY");
        when(notifications.insertOperation(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException()).thenReturn(1);
        var inbox = inbox("owner");
        inbox.tick();
        assertThat(file).exists();
        inbox.tick();
        assertThat(file).doesNotExist();
        verify(notifications, times(2)).insertOperation(eq("ops:" + file.getFileName().toString().replace(".json", "")),
                eq("owner"), anyString(), anyString());
    }

    @Test void malformedUnknownRecipientAndDisabledInboxDoNotPublish() throws Exception {
        Path file = event("UNRECOGNIZED");
        inbox("owner").tick();
        inbox("missing").tick();
        inbox("").tick();
        assertThat(file).exists();
        verifyNoInteractions(notifications);
    }

    @Test void temporaryFilesAndMismatchedIdsCannotPublish() throws Exception {
        Path file = event("TEST");
        Files.move(file, directory.resolve("other.json"));
        Files.writeString(directory.resolve("partial.tmp"), "{");
        inbox("owner").tick();
        verifyNoInteractions(notifications);
    }
}
