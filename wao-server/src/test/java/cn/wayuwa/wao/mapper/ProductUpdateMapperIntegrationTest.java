package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.service.AiAssistantService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:updatediary;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.flyway.enabled=false",
        "spring.sql.init.schema-locations=classpath:data-center-schema.sql",
        "spring.task.scheduling.enabled=false"
})
class ProductUpdateMapperIntegrationTest {

    @Autowired
    private ProductUpdateMapper mapper;
    @MockBean
    private AiAssistantService aiAssistantService;

    @Test
    void storesEachCommitOnceAndPagesNonEmptyDaysNewestFirst() {
        assertThat(mapper.insertIgnore("run-1", "sha-new", "updates",
                "feat(updates): 新增更新日记", LocalDateTime.of(2026, 9, 8, 10, 30))).isOne();
        assertThat(mapper.insertIgnore("run-1", "sha-old", "notes",
                "fix(notes): 修复小记保存", LocalDateTime.of(2026, 9, 6, 18, 0))).isOne();
        assertThat(mapper.insertIgnore("run-2", "sha-new", "updates",
                "feat(updates): 重试不重复", LocalDateTime.of(2026, 9, 8, 11, 0))).isZero();

        assertThat(mapper.countDays()).isEqualTo(2);
        assertThat(mapper.selectDays(0, 1)).containsExactly(LocalDate.of(2026, 9, 8));
        assertThat(mapper.selectDays(1, 1)).containsExactly(LocalDate.of(2026, 9, 6));
        assertThat(mapper.selectBetween(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 8)))
                .extracting("commitSubject")
                .containsExactly("feat(updates): 新增更新日记", "fix(notes): 修复小记保存");
    }
}
