package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiActivityRow;
import cn.wayuwa.wao.mapper.AiActivityMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiActivityServiceTest {
    private final AiActivityMapper mapper = mock(AiActivityMapper.class);
    private final AiActivityService service = new AiActivityService(mapper);

    @Test
    void normalizesTypeAndUsesUnifiedPagination() {
        AiActivityRow row = new AiActivityRow();
        row.setId("cr1");
        row.setActivityType("CHAT_REVIEW");
        row.setTitle("聊天复盘");
        row.setStatus("SUCCEEDED");
        row.setSubtitle("最近 20 条消息 · 20 条真实消息");
        row.setCapabilityLabel("DeepSeek");
        row.setTargetId("cr1");
        when(mapper.count("CHAT_REVIEW", "SUCCEEDED")).thenReturn(17L);
        when(mapper.page("CHAT_REVIEW", "SUCCEEDED", 8, 8)).thenReturn(List.of(row));

        var result = service.page("chat_review", "succeeded", 2, 3);

        assertThat(result.total()).isEqualTo(17);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(8);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("CHAT_REVIEW");
            assertThat(item.capabilityLabel()).isEqualTo("DeepSeek");
        });
        verify(mapper).page("CHAT_REVIEW", "SUCCEEDED", 8, 8);
    }

    @Test
    void rejectsUnknownFutureTypesUntilTheyHaveARealSource() {
        assertThatThrownBy(() -> service.page("VIDEO", "ALL", 1, 20))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不支持");
    }

    @Test
    void acceptsInspectionTypeAndRejectsUnknownResult() {
        when(mapper.count("AI_INSPECTION", "FAILED")).thenReturn(0L);

        var result = service.page("ai_inspection", "failed", 1, 20);

        assertThat(result.total()).isZero();
        verify(mapper).page("AI_INSPECTION", "FAILED", 0, 20);
        assertThatThrownBy(() -> service.page("ALL", "CANCELLED", 1, 20))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("结果");
    }

    @Test
    void marksRunnerCredentialFailureAsInfrastructureActivity() {
        AiActivityRow row = new AiActivityRow();
        row.setId("ar1");
        row.setActivityType("FEATURE_DEVELOPMENT");
        row.setStatus("FAILED");
        row.setFailureDetail("PR 创建失败：GitHub 返回 HTTP 401 Requires authentication，GH_TOKEN 不可用");
        when(mapper.count("ALL", "FAILED")).thenReturn(1L);
        when(mapper.page("ALL", "FAILED", 0, 20)).thenReturn(List.of(row));

        var item = service.page("ALL", "FAILED", 1, 20).items().get(0);

        assertThat(item.failureKind()).isEqualTo("INFRASTRUCTURE");
        assertThat(item.failureCode()).isEqualTo("RUNNER_GITHUB_CREDENTIALS");
        assertThat(item.failureLabel()).isEqualTo("执行节点 GitHub 凭据不可用");
    }
}
