package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.ImageRef;
import cn.wayuwa.wao.dto.PdcaCycle;
import cn.wayuwa.wao.dto.SharedDocDto;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TaskDto;
import org.junit.jupiter.api.Test;

class ShareServiceTest {

    private final TaskService tasks = mock(TaskService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private static final String BASE = "https://wao.example";

    private final AiRunService aiRuns = mock(AiRunService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);

    private final ShareService service = new ShareService(tasks, projects, aiRuns, knowledge);

    @Test
    void noteSharingUsesCurrentAuthorsPolishedVersionAndRejectsStaleOrForeignResults() {
        var versions = mock(cn.wayuwa.wao.mapper.NotePolishVersionMapper.class);
        var sharing = new ShareService(tasks, projects, aiRuns, knowledge, versions);
        when(knowledge.getDoc("d1")).thenReturn(doc(KnowledgeService.NOTES_KB_ID));
        String source = service.noteSnapshot("d1", "wg").md();
        String polished = "![润色图](/api/images/polished/raw)\n润色正文";
        when(versions.findShareVersion("d1")).thenReturn(
                new cn.wayuwa.wao.mapper.NotePolishVersionMapper.Version("d1", "wg", source, polished));
        assertThat(sharing.noteSnapshot("d1", "wg").md()).isEqualTo(polished);
        assertThat(sharing.noteSnapshot("d1", "wg").content()).isNull();
        assertThat(sharing.toMarkdown(sharing.noteSnapshot("d1", "wg"), BASE)).contains("润色正文", "/api/images/polished/raw");

        when(versions.findShareVersion("d1")).thenReturn(
                new cn.wayuwa.wao.mapper.NotePolishVersionMapper.Version("d1", "wg", "过期原文", polished));
        assertThat(sharing.noteSnapshot("d1", "wg").md()).isEqualTo(source);
        when(versions.findShareVersion("d1")).thenReturn(
                new cn.wayuwa.wao.mapper.NotePolishVersionMapper.Version("d1", "other", source, polished));
        assertThat(sharing.noteSnapshot("d1", "wg").md()).isEqualTo(source);
    }

    private TaskDto task() {
        return new TaskDto("t1", "整理证件盒", "p1", "进行中", "P1", "wg",
                List.of("wd"), "2026-07-20", "2026-07-30", null, "先按人分堆，再按年份排。",
                List.of(), List.of(
                        new Step("扫描身份证", true, "s1", "分辨率 300dpi。", List.of()),
                        new Step("归档到 NAS", false, "s2", "", List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ImageRef("盒子.png", "/api/images/img1/raw")));
    }

    @Test
    void shareSubShowsPendingVerificationWithoutMarkingItComplete() {
        TaskDto parent = mock(TaskDto.class);
        when(parent.id()).thenReturn("t1");
        when(parent.subs()).thenReturn(List.of(new Step("等待验收", false, "s1", "", List.of(), null,
                null, null, null, "待验证", null, null, null, null)));
        when(tasks.get("t1")).thenReturn(parent);
        assertThat(service.shareSub("t1", "s1").status()).isEqualTo("待验证");
    }

    @Test
    void shareTaskIncludesSubtasksButNotInternalFields() {
        when(tasks.get("t1")).thenReturn(task());
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));

        SharedTaskDto shared = service.shareTask("t1", false);

        assertThat(shared.kind()).isEqualTo("task");
        assertThat(shared.title()).isEqualTo("整理证件盒");
        assertThat(shared.subs()).hasSize(2);
        assertThat(shared.images()).hasSize(1);
        // 分享视图里没有负责人 / 参与人 / 评论字段可言，DTO 本身就不带它们
        assertThat(shared.subId()).isNull();
    }

    @Test
    void shareSubReturnsOnlyThatSubtask() {
        when(tasks.get("t1")).thenReturn(task());

        SharedTaskDto shared = service.shareSub("t1", "s1");

        assertThat(shared.kind()).isEqualTo("sub");
        assertThat(shared.title()).isEqualTo("扫描身份证");
        assertThat(shared.status()).isEqualTo("已完成");
        assertThat(shared.desc()).isEqualTo("分辨率 300dpi。");
        assertThat(shared.subs()).isEmpty();
        assertThat(shared.pr()).isNull();
    }

    @Test
    void subRunMarkdownUsesParentAndCompletedSubtasksAsReadOnlyMemory() {
        when(tasks.get("t1")).thenReturn(task());

        String md = service.toSubRunMarkdown("t1", "s2", BASE);

        assertThat(md).startsWith("# 子任务迭代：归档到 NAS");
        assertThat(md).contains("本次只执行“归档到 NAS”")
                .contains("不得把它们当成待办重复执行")
                .contains("## 主任务背景（只读记忆）")
                .contains("- 标题：整理证件盒")
                .contains("先按人分堆，再按年份排。")
                .contains("## 已完成子任务（只读记忆）")
                .contains("- [x] 扫描身份证")
                .contains("分辨率 300dpi。");
        assertThat(md).contains("## 本次目标").contains("（暂无描述）");
        assertThat(md).doesNotContain("- [ ] 归档到 NAS");
        assertThat(md).contains("![盒子.png](https://wao.example/api/images/img1/raw)");
    }

    @Test
    void shareSubFallsBackToIndexKeyForLegacyDataWithoutId() {
        TaskDto legacy = new TaskDto("t9", "老任务", "p1", "待办", "P2", "wg",
                List.of(), null, "2026-08-01", null, "",
                List.of(), List.of(new Step("没有 id 的子任务", false, null, null, null)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(tasks.get("t9")).thenReturn(legacy);

        assertThat(service.shareSub("t9", "t9#0").title()).isEqualTo("没有 id 的子任务");
    }

    @Test
    void shareSubRejectsUnknownSubId() {
        when(tasks.get("t1")).thenReturn(task());

        assertThatThrownBy(() -> service.shareSub("t1", "nope"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void markdownRendersTitleMetaDescriptionAndSubtaskCheckboxes() {
        when(tasks.get("t1")).thenReturn(task());
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));

        String md = service.toMarkdown(service.shareTask("t1", false), BASE);

        assertThat(md).startsWith("# 整理证件盒");
        assertThat(md).contains("- 状态：进行中").contains("- 优先级：P1").contains("- 截止：2026-07-30");
        assertThat(md).contains("先按人分堆，再按年份排。");
        assertThat(md).contains("- [x] 扫描身份证").contains("- [ ] 归档到 NAS");
        // 图片必须是绝对地址：Markdown 被复制到别处后相对路径解析不出来，图就丢了
        assertThat(md).contains("![盒子.png](https://wao.example/api/images/img1/raw)");
        assertThat(md).doesNotContain("](/api/images");
    }

    @Test
    void mergedRunMarkdownKeepsTaskBoundariesAndOneExecutionContract() {
        when(aiRuns.runTaskIds("ar1")).thenReturn(List.of("t1", "t2"));
        when(tasks.get("t1")).thenReturn(task());
        when(tasks.get("t2")).thenReturn(task());
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));

        String md = service.toRunMarkdown("ar1", true, BASE);

        assertThat(md).startsWith("# 合并 AI Run：2 个任务");
        assertThat(md).contains("一个工作区和一个上下文").contains("逐项说明结果与验收证据");
        assertThat(md).contains("## 任务 1/2：整理证件盒").contains("## 任务 2/2：整理证件盒");
        assertThat(md.indexOf("## 任务 1/2")).isLessThan(md.indexOf("## 任务 2/2"));
        assertThat(md).doesNotContain("# 整理证件盒");
    }

    @Test
    void markdownRetiresPdcaDisplayWithoutDeletingHistoryFromDto() {
        PdcaCycle cycle = new PdcaCycle("pc1", "连续七天早睡", "做到了六天，周五被临时工作打断",
                "下一轮给临时工作预留半小时", "部分达成",
                "2026-08-01T09:00:00+08:00", "2026-08-08T21:00:00+08:00");
        TaskDto task = new TaskDto("t8", "调整作息", "", "已完成", "P1", "wg",
                List.of(), null, "2026-08-08", null, "", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null,
                null, null, null, List.of(cycle));
        when(tasks.get("t8")).thenReturn(task);

        SharedTaskDto shared = service.shareTask("t8", false);
        String md = service.toMarkdown(shared, BASE);

        assertThat(shared.pdcaCycles()).containsExactly(cycle);
        assertThat(md).doesNotContain("PDCA 记录", "连续七天早睡", "下一轮给临时工作预留半小时");
    }

    @Test
    void openOnlyDropsFinishedSubtasks() {
        when(tasks.get("t1")).thenReturn(task());
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));

        SharedTaskDto shared = service.shareTask("t1", true);

        assertThat(shared.subs()).hasSize(1);
        assertThat(shared.subs().get(0).text()).isEqualTo("归档到 NAS");

        String md = service.toMarkdown(shared, BASE);
        assertThat(md).contains("- [ ] 归档到 NAS");
        assertThat(md).doesNotContain("扫描身份证");
    }

    @Test
    void openOnlyAlsoDropsSubtasksThatAlreadyHaveTheirOwnAiRun() {
        when(tasks.get("t1")).thenReturn(task());
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));
        // 「归档到 NAS」已经单独派给 AI 了，再分享给下一个 AI 就是重复算力
        when(aiRuns.dispatchedSubIds("t1")).thenReturn(java.util.Set.of("s2"));

        SharedTaskDto shared = service.shareTask("t1", true);

        assertThat(shared.subs()).isEmpty();
        assertThat(service.shareTask("t1", false).subs()).hasSize(2);
    }

    @Test
    void descriptionInlineImagesAreAlsoAbsolutized() {
        TaskDto withInline = new TaskDto("t3", "带内联图的任务", "", "待办", "P2", "wg",
                List.of(), null, "", null,
                "先看这张 ![图](/api/images/img7/raw)，再看[说明](/docs/a)。",
                List.of(), List.of(new Step("子项", false, "s1", "子图 ![s](/api/images/img8/raw)", List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(tasks.get("t3")).thenReturn(withInline);

        String md = service.toMarkdown(service.shareTask("t3", false), BASE);

        assertThat(md).contains("![图](https://wao.example/api/images/img7/raw)");
        assertThat(md).contains("[说明](https://wao.example/docs/a)");
        assertThat(md).contains("![s](https://wao.example/api/images/img8/raw)");
    }

    @Test
    void externalAndDataUrlsAreLeftAlone() {
        TaskDto external = new TaskDto("t4", "外链图", "", "待办", "P2", "wg",
                List.of(), null, "", null,
                "![远程](https://cdn.example/a.png) 与 ![内嵌](data:image/png;base64,AAAA)",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(tasks.get("t4")).thenReturn(external);

        String md = service.toMarkdown(service.shareTask("t4", false), BASE);

        assertThat(md).contains("![远程](https://cdn.example/a.png)");
        assertThat(md).contains("![内嵌](data:image/png;base64,AAAA)");
        assertThat(md).doesNotContain("wao.example/https");
    }

    @Test
    void blankBaseUrlLeavesMarkdownUntouched() {
        when(tasks.get("t1")).thenReturn(task());
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));

        String md = service.toMarkdown(service.shareTask("t1", false), "");

        assertThat(md).contains("![盒子.png](/api/images/img1/raw)");
    }

    @Test
    void markdownForEmptyDescriptionSaysSo() {
        TaskDto bare = new TaskDto("t2", "空任务", "", "待办", "P3", "wg",
                List.of(), null, "", null, "", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(tasks.get("t2")).thenReturn(bare);

        String md = service.toMarkdown(service.shareTask("t2", false), BASE);

        assertThat(md).contains("（暂无描述）");
        assertThat(md).doesNotContain("## 子任务");
        assertThat(md).doesNotContain("## 图片");
    }

    private DocDto doc(String kbId) {
        return new DocDto("d1", "装修避坑清单", "wg", "2026-07-01T10:00:00+08:00", "2026-07-20T09:30:00+08:00",
                false, List.of(),
                List.of(new DocBlockDto(null, "装修总览", null, null, null, 1),
                        new DocBlockDto(null, "水电阶段", null, null, null),
                        new DocBlockDto(null, null, "先看这张 ![图](/api/images/img9/raw)。", null, null),
                        new DocBlockDto("留好每面墙的走线照片", null, null, null, null),
                        new DocBlockDto(null, null, null, List.of("插座要带开关", "网线走六类"), null),
                        new DocBlockDto(null, null, null, null, List.of("宁多勿少", "包工头"))),
                List.of(), kbId, "f1", 3);
    }

    @Test
    void shareDocConvertsBlocksToStandardMarkdown() {
        when(knowledge.getDoc("d1")).thenReturn(doc("kb1"));

        assertThatThrownBy(() -> service.shareDoc("d1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.documentSnapshot("d1", "other"))
                .isInstanceOf(cn.wayuwa.wao.common.ForbiddenException.class);

        SharedDocDto shared = service.documentSnapshot("d1", "wg");

        assertThat(shared.title()).isEqualTo("装修避坑清单");
        assertThat(shared.note()).isFalse();
        assertThat(shared.kbId()).isEqualTo("kb1");
        assertThat(shared.md()).contains("# 装修总览");
        assertThat(shared.md()).contains("## 水电阶段");
        assertThat(shared.md()).contains("- 插座要带开关\n- 网线走六类");
        // 提示块 / 引用块用标准 blockquote，任何 Markdown 渲染器都认识
        assertThat(shared.md()).contains("> 💡 留好每面墙的走线照片");
        assertThat(shared.md()).contains("> 宁多勿少 —— 包工头");
        // JSON 视图给同域分享页用，图片保持相对路径即可
        assertThat(shared.md()).contains("](/api/images/img9/raw)");
    }

    @Test
    void shareDocKeepsRichJsonAndUsesItsMarkdownProjection() throws Exception {
        DocDto legacy = doc("kb1");
        RichDocContentDto rich = new RichDocContentDto(1, new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"type":"doc","content":[{"type":"mermaid","attrs":{"source":"flowchart TD\\nA-->B"}}]}
                """), "```mermaid\nflowchart TD\nA-->B\n```");
        when(knowledge.getDoc("d1")).thenReturn(new DocDto(legacy.id(), legacy.title(), legacy.author(),
                legacy.created(), legacy.updated(), legacy.auto(), legacy.links(), legacy.blocks(), rich,
                legacy.backlinks(), legacy.kbId(), legacy.folderId(), legacy.rev()));

        SharedDocDto shared = service.documentSnapshot("d1", "wg");

        assertThat(shared.md()).isEqualTo(rich.markdown());
        assertThat(shared.content()).isEqualTo(rich);
        assertThat(service.toMarkdown(shared, BASE)).contains("```mermaid");
    }

    @Test
    void docInNotesKbIsMarkedAsNote() {
        when(knowledge.getDoc("d2")).thenReturn(doc(KnowledgeService.NOTES_KB_ID));

        assertThatThrownBy(() -> service.shareDoc("d2")).isInstanceOf(NotFoundException.class);
        assertThat(service.noteSnapshot("d2", "wg").note()).isTrue();
        assertThatThrownBy(() -> service.noteSnapshot("d2", "other")).isInstanceOf(cn.wayuwa.wao.common.ForbiddenException.class);
    }

    @Test
    void docMarkdownEndpointAbsolutizesInlineImages() {
        when(knowledge.getDoc("d1")).thenReturn(doc("kb1"));

        String md = service.toMarkdown(service.documentSnapshot("d1", "wg"), BASE);

        assertThat(md).startsWith("# 装修避坑清单");
        assertThat(md).contains("- 更新于：2026-07-20T09:30:00+08:00");
        assertThat(md).contains("![图](https://wao.example/api/images/img9/raw)");
        assertThat(md).doesNotContain("](/api/images");
    }

    @Test
    void emptyDocRendersPlaceholderAndFallbackTitle() {
        DocDto empty = new DocDto("d3", "", "wg", null, null, false,
                List.of(), List.of(), List.of(), KnowledgeService.NOTES_KB_ID, "f1", 1);
        when(knowledge.getDoc("d3")).thenReturn(empty);

        String md = service.toMarkdown(service.noteSnapshot("d3", "wg"), BASE);

        assertThat(md).startsWith("# 未命名小记");
        assertThat(md).contains("（暂无内容）");
    }
}
