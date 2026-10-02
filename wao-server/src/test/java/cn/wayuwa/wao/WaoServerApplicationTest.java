package cn.wayuwa.wao;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上下文启动冒烟测试。测试用 src/test/resources/application.yml 提供 H2 数据源，
 * 并关闭 Flyway / Nacos / Redis，因此无需外部依赖即可加载持久层。
 */
@SpringBootTest
class WaoServerApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextStarts() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
    @Test
    void musicRoutesAreRetiredWhileFileStreamingRemainsAvailable() {
        var paths = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()).toList();
        assertThat(paths).noneMatch(path -> path.startsWith("/api/music/"));
        assertThat(paths).contains("/api/files/{id}/stream");
    }

    @Test
    void retiredMusicRequestsReturnNotFoundInsteadOfServerErrors() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
        for (var path : new String[] {"/api/music/netease/status", "/api/music/netease/callback?state=old"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        }
    }
}
