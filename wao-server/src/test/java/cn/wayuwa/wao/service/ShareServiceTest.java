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
    private final ShareService service = new ShareService(tasks, projects);

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

        SharedTaskDto shared = service.shareTask("t1");

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

        String md = service.toMarkdown(service.shareTask("t1"));

        assertThat(md).startsWith("# 整理证件盒");
        assertThat(md).contains("- 状态：进行中").contains("- 优先级：P1").contains("- 截止：2026-07-30");
        assertThat(md).contains("先按人分堆，再按年份排。");
        assertThat(md).contains("- [x] 扫描身份证").contains("- [ ] 归档到 NAS");
        assertThat(md).contains("![盒子.png](/api/images/img1/raw)");
    }

    @Test
    void markdownForEmptyDescriptionSaysSo() {
        TaskDto bare = new TaskDto("t2", "空任务", "", "待办", "P3", "wg",
                List.of(), null, "", null, "", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(tasks.get("t2")).thenReturn(bare);

        String md = service.toMarkdown(service.shareTask("t2"));

        assertThat(md).contains("（暂无描述）");
        assertThat(md).doesNotContain("## 子任务");
        assertThat(md).doesNotContain("## 图片");
    }
}
