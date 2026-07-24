package cn.wayuwa.wao;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文启动冒烟测试。测试用 src/test/resources/application.yml 提供 H2 数据源，
 * 并关闭 Flyway / Nacos / Redis，因此无需外部依赖即可加载持久层。
 */
@SpringBootTest
class WaoServerApplicationTest {

    @Test
    void applicationContextStarts() {
    }
}
