package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.CrawlerCapabilityEntity;
import cn.wayuwa.wao.dto.SaveCrawlerCapabilityRequest;
import cn.wayuwa.wao.mapper.CrawlerCapabilityMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CrawlerCapabilityServiceTest {
    private final CrawlerCapabilityMapper mapper = mock(CrawlerCapabilityMapper.class);
    private final CrawlerCapabilityService service = new CrawlerCapabilityService(mapper);

    @Test
    void createsAuditableSourceAssetWithoutExecutingIt() {
        ArgumentCaptor<CrawlerCapabilityEntity> inserted = ArgumentCaptor.forClass(CrawlerCapabilityEntity.class);

        var result = service.create(request(null, "  群消息采集  ", "print('真实源码')"), "wg");

        verify(mapper).insert(inserted.capture());
        assertThat(result.name()).isEqualTo("群消息采集");
        assertThat(result.sourceCode()).isEqualTo("print('真实源码')");
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.createdBy()).isEqualTo("wg");
        assertThat(inserted.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancesRevisionOnlyWhenStoredRevisionStillMatches() {
        when(mapper.selectById("cc1")).thenReturn(entity(2), entity(3));
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);

        var result = service.update("cc1", request(2, "更新后", "const result = 1"), "member-2");

        assertThat(result.revision()).isEqualTo(3);
        assertThat(result.updatedBy()).isEqualTo("member-2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsConcurrentOverwriteWhenConditionalUpdateLoses() {
        when(mapper.selectById("cc1")).thenReturn(entity(2));
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.update(
                "cc1", request(2, "更新后", "const result = 1"), "member-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void rejectsStaleRevisionBeforeWriting() {
        when(mapper.selectById("cc1")).thenReturn(entity(3));

        assertThatThrownBy(() -> service.update(
                "cc1", request(2, "旧草稿", "print('old')"), "member-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("其他成员");
    }

    private SaveCrawlerCapabilityRequest request(Integer revision, String name, String source) {
        return new SaveCrawlerCapabilityRequest(name, "说明", source.startsWith("const")
                ? "JAVASCRIPT" : "PYTHON", source, true, revision);
    }

    private CrawlerCapabilityEntity entity(int revision) {
        CrawlerCapabilityEntity entity = new CrawlerCapabilityEntity();
        entity.setId("cc1");
        entity.setName("已有能力");
        entity.setDescription("说明");
        entity.setLanguage("PYTHON");
        entity.setSourceCode("print('ok')");
        entity.setEnabled(true);
        entity.setRevision(revision);
        entity.setCreatedBy("wg");
        entity.setUpdatedBy(revision == 3 ? "member-2" : "wg");
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 31, 10, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 8, 31, 11, 0));
        return entity;
    }
}
