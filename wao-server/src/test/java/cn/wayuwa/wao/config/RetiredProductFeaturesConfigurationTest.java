package cn.wayuwa.wao.config;

import cn.wayuwa.wao.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RetiredProductFeaturesConfigurationTest {
    @Configuration
    @EnableWebMvc
    static class MvcConfiguration {
        @org.springframework.context.annotation.Bean
        ObjectMapper json() { return new ObjectMapper(); }
    }

    @RestController
    static class Endpoint {
        @RequestMapping(value = {"/api/projects", "/api/ai/projects/project/skills",
                "/api/settings/site-info", "/api/notification-settings"}, method = org.springframework.web.bind.annotation.RequestMethod.GET)
        String active() { return "active"; }
    }

    @Test
    void rejectsAllRetiredEndpointMethodsAndKeepsAdjacentCapabilities() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(MvcConfiguration.class, RetiredProductFeaturesConfiguration.class,
                    GlobalExceptionHandler.class, Endpoint.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterRegistrationBean.class).getFilter()).build();
            for (String path : new String[]{"/api/life-reviews", "/api/life-reviews/generate",
                    "/api/system-reports", "/api/system-reports/settings", "/api/ideas",
                    "/api/ideas/old/convert-to-project", "/api/settings/network-outage-window",
                    "/api/ai/application-skills"}) {
                for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                        get(path), post(path), put(path), delete(path)}) {
                    mvc.perform(request).andExpect(status().isGone())
                            .andExpect(jsonPath("$.code").value(41000));
                }
            }
            for (String path : new String[]{"/api/projects", "/api/ai/projects/project/skills",
                    "/api/settings/site-info", "/api/notification-settings"}) {
                mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("active"));
            }
        }
    }
}
