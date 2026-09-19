package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.charset.StandardCharsets;
import java.util.List;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.HostedPageEntity;
import cn.wayuwa.wao.mapper.HostedPageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class HostedPageServiceTest {
    private final HostedPageMapper mapper = mock(HostedPageMapper.class);
    private final HostedPageService service = new HostedPageService(mapper);

    @Test void createPersistsUtf8HtmlAndNormalizesSlug() {
        var html = new MockMultipartFile("html", "demo.html", "text/html", "<html><head></head><body>你好</body></html>".getBytes(StandardCharsets.UTF_8));
        var item = service.create(html, " My-Demo ", "演示页", null, "wg");
        assertThat(item.slug()).isEqualTo("my-demo");
        assertThat(item.sourceType()).isEqualTo("MANUAL_UPLOAD");
        assertThat(item.url()).isEqualTo("/api/pages/my-demo");
        verify(mapper).insert(any(HostedPageEntity.class));
    }

    @Test void projectHtmlAndAiReportKeepProjectClassification() {
        var html = new MockMultipartFile("html", "report.html", "text/html", "<p>报告</p>".getBytes(StandardCharsets.UTF_8));
        var page = service.create(html, "project-report", "项目报告", null, "wg", "p1");
        assertThat(page.projectId()).isEqualTo("p1");

        var report = service.saveRunReport("ar-project", html, "AI 报告", "wg", "p1");
        assertThat(report.projectId()).isEqualTo("p1");
    }

    @Test void renderReplacesUploadedTitleAndIcon() {
        HostedPageEntity entity = new HostedPageEntity();
        entity.setSlug("demo"); entity.setTitle("新<title>"); entity.setIconContent(new byte[] { 1 });
        entity.setHtmlContent("<html><head><title>旧标题</title><link rel=\"icon\" href=\"old.png\"></head></html>".getBytes(StandardCharsets.UTF_8));
        String rendered = new String(service.render(entity), StandardCharsets.UTF_8);
        assertThat(rendered).contains("<title>新&lt;title&gt;</title>", "/api/pages/demo/icon").doesNotContain("旧标题", "old.png");
    }

    @Test void rejectsInvalidSlugAndNonHtmlFile() {
        var text = new MockMultipartFile("html", "demo.txt", "text/plain", "ok".getBytes());
        assertThatThrownBy(() -> service.create(text, "bad/path", "标题", null, "wg")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(text, "valid", "标题", null, "wg")).isInstanceOf(BadRequestException.class);
    }

    @Test void updateKeepsExistingHtmlAndIconWhenFilesAreOmitted() {
        HostedPageEntity entity = new HostedPageEntity(); entity.setId("p1"); entity.setSlug("old"); entity.setTitle("旧");
        entity.setFileName("old.html"); entity.setHtmlContent("<p>正文</p>".getBytes()); entity.setIconContent(new byte[] { 1 }); entity.setIconContentType("image/png");
        when(mapper.selectById("p1")).thenReturn(entity);
        var item = service.update("p1", null, "new", "新", null);
        assertThat(item.slug()).isEqualTo("new"); assertThat(entity.getHtmlContent()).isNotEmpty(); assertThat(item.hasIcon()).isTrue();
        verify(mapper).updateById(entity);
    }

    @Test void aiRunReportUsesStablePageAndPreservesManualMetadataOnUpdate() {
        var html = new MockMultipartFile("html", "report.html", "text/html", "<p>第一版</p>".getBytes(StandardCharsets.UTF_8));
        var created = service.saveRunReport("ar123", html, "任务 · AI 报告", "wg");
        assertThat(created.id()).isEqualTo("run-report-ar123");
        assertThat(created.sourceType()).isEqualTo("AI_RUN_REPORT");
        assertThat(created.url()).isEqualTo("/api/pages/ai-run-ar123");

        HostedPageEntity existing = new HostedPageEntity();
        existing.setId("run-report-ar123"); existing.setSlug("custom-report"); existing.setTitle("自定义标题");
        existing.setFileName("report.html"); existing.setHtmlContent("<p>第一版</p>".getBytes(StandardCharsets.UTF_8));
        when(mapper.selectById("run-report-ar123")).thenReturn(existing);
        var next = new MockMultipartFile("html", "report.html", "text/html", "<p>第二版</p>".getBytes(StandardCharsets.UTF_8));

        var updated = service.saveRunReport("ar123", next, "不应覆盖", "wg");

        assertThat(updated.slug()).isEqualTo("custom-report");
        assertThat(updated.title()).isEqualTo("自定义标题");
        assertThat(new String(existing.getHtmlContent(), StandardCharsets.UTF_8)).contains("第二版");
        verify(mapper).updateById(existing);
    }

    @Test void reportUrlsLoadOnlyExistingMetadata() {
        HostedPageEntity report = new HostedPageEntity();
        report.setId("run-report-ar123"); report.setSlug("custom-report"); report.setTitle("报告");
        when(mapper.listMetaByIds(List.of("run-report-ar123", "run-report-missing"))).thenReturn(List.of(report));

        assertThat(service.runReportUrls(List.of("ar123", "missing")))
                .isEqualTo(java.util.Map.of("ar123", "/api/pages/custom-report"));
    }

    @Test void listValidatesAndPassesTheStableSourceTypeToTheMetadataQuery() {
        when(mapper.listMeta("AI_RUN_REPORT")).thenReturn(List.of());

        assertThat(service.list("ai_run_report")).isEmpty();

        verify(mapper).listMeta("AI_RUN_REPORT");
        assertThatThrownBy(() -> service.list("inspection-report"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("来源类型");
    }
    @Test void replacementPreservesPublicUrlTitleIconAndProject() {
        var entity = new HostedPageEntity();
        entity.setId("h1"); entity.setSlug("stable"); entity.setTitle("保留标题");
        entity.setProjectId("p1"); entity.setSourceType("MANUAL_UPLOAD");
        entity.setIconContent(new byte[] { 1 }); entity.setIconContentType("image/png");
        entity.setHtmlContent("old".getBytes(StandardCharsets.UTF_8));
        when(mapper.selectById("h1")).thenReturn(entity);
        when(mapper.replaceContent(entity)).thenReturn(1);
        var html = new MockMultipartFile("html", "new.html", "text/html", "<p>新版</p>".getBytes(StandardCharsets.UTF_8));
        var result = service.replace("h1", html);
        assertThat(result.id()).isEqualTo("h1");
        assertThat(result.url()).isEqualTo("/api/pages/stable");
        assertThat(result.title()).isEqualTo("保留标题");
        assertThat(result.fileName()).isEqualTo("new.html");
        assertThat(result.projectId()).isEqualTo("p1");
        assertThat(entity.getIconContent()).containsExactly((byte) 1);
        assertThat(new String(service.render(entity), StandardCharsets.UTF_8)).contains("<p>新版</p>");
        verify(mapper).replaceContent(entity);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateById(any(HostedPageEntity.class));
    }

    @Test void replacementRejectsEmptyWrongTypeAndInvalidEncodingBeforePersisting() {
        var entity = new HostedPageEntity();
        entity.setId("h1"); entity.setSlug("stable"); entity.setTitle("保留标题");
        when(mapper.selectById("h1")).thenReturn(entity);
        for (var file : List.of(
                new MockMultipartFile("html", "empty.html", "text/html", new byte[0]),
                new MockMultipartFile("html", "wrong.pdf", "application/pdf", "bad".getBytes()),
                new MockMultipartFile("html", "bad.html", "text/html", new byte[] {(byte) 0xc3, 0x28}),
                new MockMultipartFile("html", "large.html", "text/html", new byte[10 * 1024 * 1024 + 1]))) {
            assertThatThrownBy(() -> service.replace("h1", file)).isInstanceOf(BadRequestException.class);
        }
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).replaceContent(any());
    }

}
