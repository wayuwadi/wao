package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.SaveMemoryRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.jdbc.Sql;

/** 真实 H2 表验证即时发布、审核、乐观并发、失效和范围隔离。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:unifiedmemory;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql({"/project-memory-schema.sql", "/unified-memory-schema.sql"})
class UnifiedMemoryServiceIntegrationTest {
    @Autowired private UnifiedMemoryService memories;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void useRealAdminRole() {
        jdbc.update("UPDATE member SET role = ? WHERE id = 'wg'", "超级管理员");
    }

    @Test
    void publishesPersonalImmediatelyAndRejectsStaleRevisionWithoutLosingCurrentVersion() {
        var created = memories.create("wg", request("personal", "wg", "preference", "输出偏好", "先给结论。", null), "human");
        assertThat(created.revision().state()).isEqualTo("published");
        assertThat(memories.searchPublished("wg", "personal", "wg", "输出偏好")).hasSize(1);

        var revised = memories.revise("wg", created.id(), request("personal", "wg", "preference",
                "输出偏好", "先给结论，再补证据。", created.currentPublishedRevisionId()), "human");
        assertThat(revised.revision().versionNo()).isEqualTo(2);
        assertThatThrownBy(() -> memories.revise("wg", created.id(), request("personal", "wg", "preference",
                "过期设备草稿", "不应覆盖。", created.currentPublishedRevisionId()), "human"))
                .isInstanceOf(ConflictException.class);
        assertThat(memories.get("wg", created.id(), null).revision().content()).contains("再补证据");
    }

    @Test
    void hidesCandidateAndExpiredMemoryAndEnforcesScopeAndSensitiveFilter() {
        var candidate = memories.create("wg", request("organization", "o1", "policy",
                "发布规范", "发布前必须完成验收。", null), "human");
        assertThat(memories.searchPublished("wg", "organization", "o1", "发布规范")).isEmpty();
        memories.publish("wg", candidate.revision().id());
        assertThat(memories.searchPublished("wg", "organization", "o1", "发布规范")).hasSize(1);

        var expired = new SaveMemoryRequest("personal", "wg", "context", "旧上下文", "已经过期的事实。",
                "测试失效", List.of(), LocalDateTime.now().minusMinutes(1), null);
        memories.create("wg", expired, "human");
        assertThat(memories.searchPublished("wg", "personal", "wg", "过期事实")).isEmpty();
        assertThatThrownBy(() -> memories.list("wg", "personal", "someone-else", null, null, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> memories.create("wg", request("personal", "wg", "context",
                "凭据", "api_key=abcdefghijk12345", null), "human"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void boundsPublishedSearchToFiveResultsAndTenThousandCharacters() {
        for (int index = 0; index < 6; index++) {
            memories.create("wg", request("personal", "wg", "context", "性能基线 " + index,
                    "统一检索 " + "正文".repeat(1_300), null), "human");
        }
        var found = memories.searchPublished("wg", "personal", "wg", "统一检索");
        assertThat(found).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(found.stream().mapToInt(item -> item.revision().title().length()
                + item.revision().content().length()).sum()).isLessThanOrEqualTo(10_000);
    }

    private static SaveMemoryRequest request(String scope, String scopeId, String category,
            String title, String content, String baseRevisionId) {
        return new SaveMemoryRequest(scope, scopeId, category, title, content,
                "集成测试", List.of(), null, baseRevisionId);
    }
}
