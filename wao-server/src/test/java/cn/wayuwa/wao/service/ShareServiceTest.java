package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.ImageRef;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TaskDto;
import org.junit.jupiter.api.Test;

class ShareServiceTest {

    private final TaskService tasks = mock(TaskService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private static final String BASE = "https://wao.example";

    private final AiRunService aiRuns = mock(AiRunService.class);

    private final ShareService service = new ShareService(tasks, projects, aiRuns);

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
}
