package cn.wayuwa.wao;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

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
}
