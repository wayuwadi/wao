package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.service.TaskHierarchyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskHierarchyControllerTest {
    final TaskHierarchyService service = mock(TaskHierarchyService.class);
    final CurrentMember member = mock(CurrentMember.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskHierarchyController(service, member))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test void moveUsesSessionIdentityAndExpectedParent() throws Exception {
        when(member.id(any())).thenReturn("member");
        mvc.perform(post("/api/tasks/a/parent").contentType("application/json")
                .content("{\"parentTaskId\":\"b\",\"expectedParentTaskId\":null,\"memberId\":\"forged\"}"))
                .andExpect(status().isOk());
        verify(service).move("a", "b", null, "member");
    }
    @Test void detachAcceptsNullParent() throws Exception {
        when(member.id(any())).thenReturn("member");
        mvc.perform(post("/api/tasks/a/parent").contentType("application/json")
                .content("{\"parentTaskId\":null,\"expectedParentTaskId\":\"b\"}"))
                .andExpect(status().isOk());
        verify(service).move("a", null, "b", "member");
    }
    @Test void staleRelationshipReturnsConflict() throws Exception {
        when(member.id(any())).thenReturn("member");
        when(service.move("a", "b", null, "member")).thenThrow(new ConflictException("父任务已变化"));
        mvc.perform(post("/api/tasks/a/parent").contentType("application/json")
                .content("{\"parentTaskId\":\"b\"}"))
                .andExpect(status().isConflict());
    }
}
