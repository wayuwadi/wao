package cn.wayuwa.wao.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.dto.HelloData;
import cn.wayuwa.wao.service.HelloService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HelloController.class, excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = cn.wayuwa.wao.auth.FeishuBotAdminInterceptor.class))
@Import(GlobalExceptionHandler.class)
class HelloControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HelloService helloService;

    @Test
    void returnsUnifiedEnvelope() throws Exception {
        when(helloService.hello()).thenReturn(new HelloData(
                "Hello from WAO",
                "wao-server",
                OffsetDateTime.parse("2026-07-24T00:00:00Z")));

        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.service").value("wao-server"))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }
}
