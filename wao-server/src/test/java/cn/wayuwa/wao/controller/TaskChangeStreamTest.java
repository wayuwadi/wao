package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.TaskChangeStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

class TaskChangeStreamTest {
    @RestController static class Writes {
        @PutMapping("/api/task-labels/test") ApiResponse<String> write() { return ApiResponse.ok("saved"); }
        @PutMapping("/api/tasks/failed") ApiResponse<Void> failed() { return ApiResponse.fail(409,"conflict"); }
    }
    @Test void successfulWritesNotifyOnlyTheAuthenticatedMemberAndFailedWritesStayQuiet() throws Exception {
        var member=mock(CurrentMember.class);
        when(member.id(any())).thenAnswer(call -> ((HttpServletRequest)call.getArgument(0)).getHeader("Test-Member"));
        var stream=new TaskChangeStream();
        try {
            var mvc=MockMvcBuilders.standaloneSetup(new TaskChangeController(member,stream),new Writes())
                    .setControllerAdvice(new TaskChangeAdvice(member,stream)).build();
            var first=mvc.perform(get("/api/task-changes").header("Test-Member","first")).andReturn();
            var second=mvc.perform(get("/api/task-changes").header("Test-Member","second")).andReturn();
            assertThat(first.getResponse().getContentAsString()).contains("event:ready");
            mvc.perform(put("/api/task-labels/test").header("Test-Member","first"));
            assertThat(first.getResponse().getContentAsString()).contains("event:changed");
            assertThat(second.getResponse().getContentAsString()).doesNotContain("event:changed");
            mvc.perform(put("/api/tasks/failed").header("Test-Member","second"));
            assertThat(second.getResponse().getContentAsString()).doesNotContain("event:changed");
            stream.heartbeat();
            assertThat(second.getResponse().getContentAsString()).contains("event:heartbeat");
        } finally { stream.close(); }
    }
}
