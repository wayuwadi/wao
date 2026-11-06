package cn.wayuwa.wao.controller;

import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.dto.TaskTemplateRequest;
import cn.wayuwa.wao.service.TaskTemplateService;
import cn.wayuwa.wao.service.TaskTemplateCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TaskTemplateController.class, excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = cn.wayuwa.wao.auth.FeishuBotAdminInterceptor.class))
@Import(GlobalExceptionHandler.class)
class TaskTemplateControllerTest {
    @Autowired MockMvc mvc;
    @MockBean TaskTemplateService templates;
    @MockBean TaskTemplateCreationService creation;
    @MockBean CurrentMember member;

    @Test void returnsOnlyCurrentMemberTemplatesWithPrivateCachePolicy() throws Exception {
        when(member.id(any())).thenReturn("m1");
        var content = new TaskTemplateRequest("名称", "任务", "说明", List.of());
        when(templates.list("m1")).thenReturn(List.of(new TaskTemplateService.Template("id", content)));
        mvc.perform(get("/api/task-templates"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data[0].content.title").value("任务"));
        verify(templates).list("m1");
    }
    @Test void rejectsInvalidRequestBeforeSaving() throws Exception {
        mvc.perform(put("/api/task-templates/0123456789abcdef0123456789abcdef")
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"a1\",\"content\":{\"name\":\"\",\"title\":\"任务\",\"description\":\"\",\"steps\":[]}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(templates);
    }
    @Test void matchingAccountCanSaveAndDeleteItsTemplate() throws Exception {
        String id = "0123456789abcdef0123456789abcdef";
        when(member.id(any())).thenReturn("m1");
        when(member.accountId(any())).thenReturn("a1");
        when(templates.save(eq(id), eq("m1"), any())).thenAnswer(call -> new TaskTemplateService.Template(id, call.getArgument(2)));
        mvc.perform(put("/api/task-templates/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"a1\",\"content\":{\"name\":\"模板\",\"title\":\"任务\",\"description\":\"说明\",\"steps\":[]}}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.content.title").value("任务"));
        mvc.perform(delete("/api/task-templates/" + id).header("X-WAO-Account-Id", "a1"))
                .andExpect(status().isOk());
        verify(templates).delete(id, "m1");
    }
    @Test void missingMemberCannotReadTemplates() throws Exception {
        when(member.id(any())).thenReturn(null);
        mvc.perform(get("/api/task-templates")).andExpect(status().isUnauthorized());
        verifyNoInteractions(templates);
    }
    @Test void changedAccountCannotWriteTemplatePreparedByAnotherAccount() throws Exception {
        when(member.id(any())).thenReturn("m1");
        when(member.accountId(any())).thenReturn("new-account");
        mvc.perform(put("/api/task-templates/0123456789abcdef0123456789abcdef")
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"old-account\",\"content\":{\"name\":\"模板\",\"title\":\"任务\",\"description\":\"\",\"steps\":[]}}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/task-templates/0123456789abcdef0123456789abcdef").header("X-WAO-Account-Id", "old-account"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/task-templates/creations/0123456789abcdef0123456789abcdef")
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"old-account\",\"templateId\":\"0123456789abcdef0123456789abcdef\",\"projectId\":\"\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(templates);
        verifyNoInteractions(creation);
    }
}
