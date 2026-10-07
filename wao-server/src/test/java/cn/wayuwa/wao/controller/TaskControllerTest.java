package cn.wayuwa.wao.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = TaskController.class, excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = cn.wayuwa.wao.auth.FeishuBotAdminInterceptor.class))
@Import(GlobalExceptionHandler.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @MockBean
    private CurrentMember currentMember;

    private TaskDto sample() {
        return new TaskDto("t1", "设计任务详情抽屉交互稿", "p1", "进行中", "P0", "wg",
                List.of(), null, "2026-07-24", null, "desc",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void listReturnsEnvelope() throws Exception {
        when(currentMember.idOr(org.mockito.ArgumentMatchers.any(), eq("wg"))).thenReturn("wg");
        when(taskService.list("wg")).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value("t1"))
                .andExpect(jsonPath("$.data[0].pr").value("P0"));
    }

    @Test
    void missingTaskMapsTo404() throws Exception {
        when(currentMember.idOr(org.mockito.ArgumentMatchers.any(), eq("wg"))).thenReturn("wg");
        when(taskService.get(eq("nope"), eq("wg"))).thenThrow(new NotFoundException("任务不存在：nope"));

        mockMvc.perform(get("/api/tasks/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void taskPromotionEndpointIsRetired() {
        assertFalse(java.util.Arrays.stream(TaskController.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("promoteToProject")));
    }
}
