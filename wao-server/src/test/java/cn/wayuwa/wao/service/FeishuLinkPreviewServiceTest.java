package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.SharedDocDto;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.dto.SharedTianrenReadingDto;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TianrenHexagramDto;
import cn.wayuwa.wao.dto.TianrenTrigramDto;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FeishuLinkPreviewServiceTest {

    private static final String BASE = "https://wao.example";
    private final ShareService share = mock(ShareService.class);
    private final TianrenReadingService tianren = mock(TianrenReadingService.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final ImageService images = mock(ImageService.class);
    private final FeishuLinkPreviewService service = new FeishuLinkPreviewService(
            share, tianren, settings, feishu, images);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void taskPageReturnsInlineTitleRealFactsAndOriginalLink() {
        when(share.shareTask("t1", true)).thenReturn(new SharedTaskDto(
                "task", "t1", null, "整理证件盒", "进行中", "P1", "2026-09-08", "p1", "Wa All in One",
                "先看 **清单**，再访问[说明](https://example.com)。", List.of(),
                List.of(new Step("已做", true, "s1", "", List.of()),
                        new Step("待办", false, "s2", "", List.of())), List.of()));

        Map<String, Object> result = service.preview(event(BASE + "/s/t/t1?openOnly=true"), BASE);

        assertThat(result.toString()).contains("WAO 任务 · 整理证件盒", "状态：进行中", "优先级：P1",
                "子任务：1 项待办 / 2 项可见", "先看 清单，再访问说明。", BASE + "/s/t/t1?openOnly=true");
        verify(share).shareTask("t1", true);
    }

    @Test
    void markdownSubtaskAddressOnlyReadsThatSubtask() {
        when(share.shareSub("t1", "s 1")).thenReturn(new SharedTaskDto(
                "sub", "t1", "s 1", "扫描身份证", "未完成", null, null, "p1", "WAO", "300 dpi",
                List.of(), List.of(), List.of()));

        Map<String, Object> result = service.preview(event(BASE + "/api/share/tasks/t1/subs/s%201.md"), BASE);

        assertThat(result.toString()).contains("WAO 子任务 · 扫描身份证", "300 dpi");
        verify(share).shareSub("t1", "s 1");
        verify(share, never()).shareTask("t1", false);
    }

    @Test
    void documentAndNoteUseTheExistingPublicProjection() {
        when(share.shareDoc("d1")).thenReturn(new SharedDocDto(
                "d1", "复盘", false, "kb1", "2026-09-03T10:30:00", "# 结论\n\n<script>x()</script> 保留真实结果"));
        when(share.shareDoc("d2")).thenReturn(new SharedDocDto("d2", "", true, "notes", "", "一句小记"));

        assertThat(service.preview(event(BASE + "/s/d/d1"), BASE).toString())
                .contains("WAO 文档 · 复盘", "更新于：2026-09-03T10:30:00", "结论 x() 保留真实结果")
                .doesNotContain("<script>");
        assertThat(service.preview(event(BASE + "/api/share/docs/d2.md"), BASE).toString())
                .contains("WAO 小记 · 未命名小记", "一句小记");
    }

    @Test
    void notePreviewShowsPublicImageAndShortTextWithoutTimestampOrImageAlt() throws Exception {
        image("cover_1", "img_note_cover");
        var content = new cn.wayuwa.wao.dto.RichDocContentDto(1,
                json.readTree("""
                        {"type":"doc","content":[{"type":"image","attrs":{"src":"/api/images/cover_1/raw","alt":"文章总览图"}}]}
                        """), "![文章总览图](/api/images/cover_1/raw)\n\n" + "小记正文".repeat(40));
        when(share.shareDoc("note1")).thenReturn(new SharedDocDto(
                "note1", "真实标题", true, "notes", "2026-09-24T22:32:41", content.markdown(), content));

        JsonNode result = json.valueToTree(service.preview(event(BASE + "/s/d/note1"), BASE));
        JsonNode elements = result.path("card").path("data").path("elements");

        assertThat(result.path("inline").path("i18n_title").path("zh_cn").asText())
                .isEqualTo("WAO 小记 · 真实标题");
        assertThat(elements.get(0).path("tag").asText()).isEqualTo("img");
        assertThat(elements.get(0).path("img_key").asText()).isEqualTo("img_note_cover");
        assertThat(elements.get(1).path("text").path("content").asText())
                .isEqualTo("小记正文".repeat(40));
        assertThat(result.toString()).doesNotContain("文章总览图", "更新于", "2026-09-24T22:32:41");
        assertThat(elements.get(2).path("actions").get(0).path("url").asText())
                .isEqualTo(BASE + "/s/d/note1");
    }

    @Test
    void noteImageFallsBackToLegacyMarkdownAndRejectsExternalSources() {
        image("old", "img_legacy_cover");
        when(share.shareDoc("old")).thenReturn(new SharedDocDto("old", "旧小记", true, "notes", "",
                "![外部](https://elsewhere.example/x.png)\n![配图](/api/images/old/raw)\n正文"));

        JsonNode result = json.valueToTree(service.preview(event(BASE + "/api/share/docs/old.md"), BASE));

        assertThat(result.path("card").path("data").path("elements").get(0)
                .path("img_key").asText()).isEqualTo("img_legacy_cover");
        assertThat(result.toString()).doesNotContain("elsewhere.example", "/api/images/old/raw");
    }

    @Test
    void imageUploadFailureKeepsTextAndNeverShowsImageUrl() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app1");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret1");
        when(images.raw("broken")).thenThrow(new IllegalStateException("图片暂不可用"));
        when(share.shareDoc("broken-note")).thenReturn(new SharedDocDto("broken-note", "标题", true,
                "notes", "", "![封面](/api/images/broken/raw)\n正文"));

        JsonNode result = json.valueToTree(service.preview(event(BASE + "/s/d/broken-note"), BASE));

        assertThat(result.path("card").path("data").path("elements").get(0)
                .path("text").path("content").asText()).isEqualTo("正文");
        assertThat(result.toString()).doesNotContain("broken/raw", "![", "图片暂不可用");
    }

    @Test
    void tianrenPreviewShowsRealHexagramsAndLatestPublicReflection() {
        var qian = new TianrenHexagramDto(1, "乾为天", "䷀",
                new TianrenTrigramDto("乾", "天", "☰"), new TianrenTrigramDto("乾", "天", "☰"));
        var gou = new TianrenHexagramDto(44, "天风姤", "䷫",
                new TianrenTrigramDto("乾", "天", "☰"), new TianrenTrigramDto("巽", "风", "☴"));
        when(tianren.shared("0123456789abcdef0123456789abcdef")).thenReturn(new SharedTianrenReadingDto(
                LocalDate.of(2026, 9, 3), List.of(9, 7, 7, 7, 7, 7), List.of(1), qian, gou,
                "今天先完成一个小行动。", LocalDateTime.of(2026, 9, 3, 8, 0)));

        Map<String, Object> result = service.preview(
                event(BASE + "/s/tianren/0123456789abcdef0123456789abcdef"), BASE);

        assertThat(result.toString()).contains("WAO 天人一卦 · 乾为天", "本卦：䷀ 乾为天",
                "1 个变爻 · 之卦 ䷫ 天风姤", "今天先完成一个小行动。");
    }

    @Test
    void externalUnsupportedAndMalformedUrlsDegradeWithoutReadingBusinessData() {
        assertThat(service.preview(event("https://evil.example/s/t/t1"), BASE)).isEmpty();
        assertThat(service.preview(event("https://wao.example:444/s/t/t1"), BASE)).isEmpty();
        assertThat(service.preview(event("ftp://wao.example/s/t/t1"), BASE)).isEmpty();
        assertThat(service.preview(event(BASE + "/projects/p1"), BASE)).isEmpty();
        assertThat(service.preview(event("not a url"), BASE)).isEmpty();

        verify(share, never()).shareTask("t1", false);
        verify(share, never()).shareDoc("d1");
    }

    @Test
    void deletedPublicContentDegradesWithoutExposingTheLookupError() {
        when(share.shareTask("deleted", false)).thenThrow(new NotFoundException("任务不存在：deleted"));

        Map<String, Object> result = service.preview(event(BASE + "/s/t/deleted"), BASE);

        assertThat(result).isEmpty();
        verify(share).shareTask("deleted", false);
    }

    @Test
    void unsafeEncodedPathSegmentDegradesBeforeLookup() {
        assertThat(service.preview(event(BASE + "/s/t/t1/s%2F1"), BASE)).isEmpty();

        verify(share, never()).shareSub("t1", "s/1");
    }

    @Test
    void textNormalizationIsPlainBoundedAndKeepsVisibleLabels() {
        String longText = "![截图](https://x.test/a.png) [说明](https://x.test) **重点** <b>正文</b> " + "长".repeat(300);

        String result = FeishuLinkPreviewService.plainText(longText, 40);

        assertThat(result).startsWith("截图 说明 重点 正文").endsWith("…").hasSize(40);
        assertThat(result).doesNotContain("https://", "**", "<b>");
    }

    private JsonNode event(String url) {
        return json.createObjectNode().set("context", json.createObjectNode().put("url", url));
    }

    private void image(String id, String key) {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app1");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret1");
        ImageBlobEntity blob = new ImageBlobEntity();
        blob.setContent(new byte[] {1, 2, 3});
        blob.setContentType("image/png");
        when(images.raw(id)).thenReturn(blob);
        when(feishu.tenantAccessToken("app1", "secret1")).thenReturn("token1");
        when(feishu.uploadMessageImage("token1", blob.getContent(), "image/png", "share-preview.png"))
                .thenReturn(key);
    }

    @Test
    void tianrenCardUsesSavedImageAndSummarySectionWithoutLeakingImageSyntax() {
        image("reading_cover", "img_reading");
        var qian = new TianrenHexagramDto(1, "乾为天", "䷀",
                new TianrenTrigramDto("乾", "天", "☰"), new TianrenTrigramDto("乾", "天", "☰"));
        String token = "0123456789abcdef0123456789abcdef";
        when(tianren.shared(token)).thenReturn(new SharedTianrenReadingDto(
                LocalDate.of(2026, 10, 4), List.of(7, 7, 7, 7, 7, 7), List.of(), qian, null,
                "![润色图](/api/images/reading_cover/raw)\n## 卦象事实\n很长的事实。\n"
                        + "## 卦象快速总结\n**保持专注**，先完成一件小事。\n## 边界提醒\n其他正文。",
                LocalDateTime.of(2026, 10, 4, 8, 0)));

        JsonNode result = json.valueToTree(service.preview(event(BASE + "/s/tianren/" + token), BASE));
        assertThat(result.path("card").path("data").path("header").path("template").asText())
                .isEqualTo("purple");
        assertThat(result.path("card").path("data").path("elements").get(0).path("img_key").asText())
                .isEqualTo("img_reading");
        assertThat(result.toString()).contains("保持专注，先完成一件小事。", "查看详情", BASE + "/s/tianren/" + token)
                .doesNotContain("很长的事实", "其他正文", "润色图", "/api/images/", "![");
    }

    @Test
    void summaryHandlesMissingEmptyAndLongSavedInterpretations() {
        assertThat(FeishuLinkPreviewService.quickSummary(null)).isEmpty();
        assertThat(FeishuLinkPreviewService.quickSummary("![配图](https://external.example/img.png)\n旧解读"))
                .isEqualTo("旧解读");
        assertThat(FeishuLinkPreviewService.quickSummary("## 快速总结\n\n## 卦象事实\n真实事实"))
                .contains("真实事实");
        assertThat(FeishuLinkPreviewService.quickSummary("## 快速总结\n" + "长".repeat(500)))
                .hasSize(240).endsWith("…");
    }

    @Test
    void repeatedLinksReuseImageUploadedForTheSameApp() {
        image("one", "img_one");
        when(share.shareDoc("d")).thenReturn(new SharedDocDto("d", "标题", true, "notes", "",
                "![图](/api/images/one/raw)\n内容"));
        service.preview(event(BASE + "/s/d/d"), BASE);
        service.preview(event(BASE + "/api/share/docs/d.md"), BASE);
        verify(feishu, org.mockito.Mockito.times(1)).uploadMessageImage(
                org.mockito.ArgumentMatchers.eq("token1"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("image/png"), org.mockito.ArgumentMatchers.eq("share-preview.png"));
    }

    @Test
    void privateVisualProviderIsVersionCheckedAndFailureKeepsPublicCard() {
        image("unused", "img_private");
        var provider = mock(TianrenShareVisualProvider.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tianrenVisuals", provider);
        String token = "0123456789abcdef0123456789abcdef";
        var qian = new TianrenHexagramDto(1, "乾为天", "䷀",
                new TianrenTrigramDto("乾", "天", "☰"), new TianrenTrigramDto("乾", "天", "☰"));
        when(tianren.shared(token)).thenReturn(new SharedTianrenReadingDto(
                LocalDate.of(2026, 10, 4), List.of(7, 7, 7, 7, 7, 7), List.of(), qian, null,
                "当前解读", LocalDateTime.of(2026, 10, 4, 8, 0)));
        byte[] png = new byte[] {1, 2, 3};
        when(feishu.uploadMessageImage("token1", png, "image/png", "share-preview.png"))
                .thenReturn("img_private");
        when(provider.findSharedVisual(token)).thenReturn(new TianrenShareVisualProvider.Visual(
                "旧解读", "过时摘要", "attempt-old", png, "image/png"));
        assertThat(service.preview(event(BASE + "/s/tianren/" + token), BASE).toString())
                .contains("当前解读").doesNotContain("过时摘要", "img_private");

        when(provider.findSharedVisual(token)).thenReturn(new TianrenShareVisualProvider.Visual(
                "当前解读", "变：及时行动；不变：保持诚意", "attempt-current", png, "image/png"));
        assertThat(service.preview(event(BASE + "/s/tianren/" + token), BASE).toString())
                .contains("img_private", "变：及时行动；不变：保持诚意")
                .doesNotContain("attempt-current", "image/png");
        when(provider.findSharedVisual(token)).thenThrow(new IllegalStateException("私有存储故障"));
        assertThat(service.preview(event(BASE + "/s/tianren/" + token), BASE).toString())
                .contains("当前解读", "查看详情").doesNotContain("私有存储故障", "img_private");
    }

    @Test
    void slowUploadDegradesWithinBudgetAndLaterPreviewReusesFinishedImage() throws Exception {
        image("slow", "img_slow");
        var release = new java.util.concurrent.CountDownLatch(1);
        when(feishu.uploadMessageImage(org.mockito.ArgumentMatchers.eq("token1"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("image/png"),
                org.mockito.ArgumentMatchers.eq("share-preview.png"))).thenAnswer(call -> {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return "img_slow";
                });
        when(share.shareDoc("slow-note")).thenReturn(new SharedDocDto("slow-note", "标题", true,
                "notes", "", "![封面](/api/images/slow/raw)\n正文"));
        long start = System.nanoTime();
        try {
            assertThat(service.preview(event(BASE + "/s/d/slow-note"), BASE).toString())
                    .contains("正文", "打开原文").doesNotContain("img_slow", "/api/images/");
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                    .isLessThan(2_800);
        } finally { release.countDown(); }
        assertThat(service.preview(event(BASE + "/s/d/slow-note"), BASE).toString()).contains("img_slow");
    }
}
