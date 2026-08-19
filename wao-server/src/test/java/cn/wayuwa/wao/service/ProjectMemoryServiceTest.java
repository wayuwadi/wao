package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AiRunMemoryReadEntity;
import cn.wayuwa.wao.domain.AiRunMemoryWriteEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.ProjectMemoryEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.ProjectMemoryCandidate;
import cn.wayuwa.wao.dto.SaveProjectMemoryRequest;
import cn.wayuwa.wao.mapper.AiRunMemoryReadMapper;
import cn.wayuwa.wao.mapper.AiRunMemoryWriteMapper;
import cn.wayuwa.wao.mapper.ProjectMemoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectMemoryServiceTest {
    private final ProjectMemoryMapper memories = mock(ProjectMemoryMapper.class);
    private final AiRunMemoryReadMapper reads = mock(AiRunMemoryReadMapper.class);
    private final AiRunMemoryWriteMapper writes = mock(AiRunMemoryWriteMapper.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectMemoryService service = new ProjectMemoryService(
            memories, reads, writes, projects, new ObjectMapper());

    @Test
    void projectAccessIsCheckedBeforeListingAnotherProjectsMemory() {
        when(projects.get("p-private", "wd")).thenThrow(new NotFoundException("项目不存在：p-private"));

        assertThatThrownBy(() -> service.list("p-private", "wd", null, null))
                .isInstanceOf(NotFoundException.class);

        verify(projects).get("p-private", "wd");
        verify(memories, never()).selectList(any());
    }

    @Test
    void recallRanksRelevantItemsAndKeepsCountAndLengthBounded() {
        List<ProjectMemoryEntity> candidates = new ArrayList<>();
        candidates.add(memory("pm-best", "pitfall", "Flyway migration 编号冲突",
                "新增 migration 前先核对 origin/main，运行 FlywayMigrationsTest。", LocalDateTime.now()));
        candidates.add(memory("pm-unrelated", "context", "前端配色", "卡片使用蓝色。", LocalDateTime.now()));
        for (int index = 0; index < 7; index++) {
            candidates.add(memory("pm" + index, "verification", "migration 验收 " + index,
                    "migration 测试证据".repeat(700), LocalDateTime.now().minusMinutes(index + 1)));
        }
        when(memories.selectList(any())).thenReturn(candidates);
        AiTaskRunEntity run = run();
        TaskEntity task = new TaskEntity();
        task.setTitle("新增 Flyway migration 并完成验收");
        task.setDescription("注意 migration 编号冲突");

        var result = service.recall(run, task);

        assertThat(result).hasSizeLessThanOrEqualTo(5);
        assertThat(result).extracting(item -> item.id()).doesNotContain("pm-unrelated");
        assertThat(result.stream().mapToInt(item -> item.title().length() + item.content().length()).sum())
                .isLessThanOrEqualTo(10_000);
        assertThat(result.get(0).id()).isEqualTo("pm-best");
        verify(reads, org.mockito.Mockito.times(result.size())).insert(any(AiRunMemoryReadEntity.class));
    }

    @Test
    void recallUsesTheSelectedSubtaskTitleAndDescription() {
        ProjectMemoryEntity memory = memory("pm-sub", "convention", "窄屏弹窗约定",
                "移动端弹窗首次渲染必须就位。", LocalDateTime.now());
        when(memories.selectList(any())).thenReturn(List.of(memory));
        AiTaskRunEntity run = run();
        run.setSubId("s-mobile");
        TaskEntity task = new TaskEntity();
        task.setTitle("优化页面");
        task.setDescription("处理现有体验问题");
        task.setDetailJson("""
                {"steps":[],"subs":[
                  {"id":"s-mobile","text":"修复窄屏弹窗","done":false,"desc":"移动端首次渲染跳动","images":[]},
                  {"id":"s-other","text":"更新桌面配色","done":false,"desc":"桌面端","images":[]}
                ]}
                """);

        var result = service.recall(run, task);

        assertThat(result).extracting(item -> item.id()).containsExactly("pm-sub");
    }

    @Test
    void projectFingerprintDeduplicatesWhitespaceEquivalentContent() {
        when(memories.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create("p1", "wg",
                new SaveProjectMemoryRequest("decision", "选择 MySQL", "继续 使用  MySQL", List.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("相同内容");

        verify(memories, never()).insert(any(ProjectMemoryEntity.class));
    }

    @Test
    void sensitiveCredentialsAreRejected() {
        assertThatThrownBy(() -> service.create("p1", "wg",
                new SaveProjectMemoryRequest("operation", "部署凭据",
                        "api_key=sk-123456789012345678901234567890", List.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("凭据");

        verify(memories, never()).insert(any(ProjectMemoryEntity.class));
    }

    @Test
    void protocolRelativeEvidenceLinkIsRejected() {
        assertThatThrownBy(() -> service.create("p1", "wg",
                new SaveProjectMemoryRequest("context", "不安全链接", "正文内容",
                        List.of("//attacker.example/evidence"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("证据链接");
    }

    @Test
    void candidatePersistenceDropsDuplicatesSensitiveAndExcessItems() {
        when(memories.selectCount(any())).thenReturn(0L);
        List<ProjectMemoryCandidate> candidates = new ArrayList<>();
        List<String> evidence = List.of("https://github.com/acme/wao/actions/runs/1");
        candidates.add(new ProjectMemoryCandidate("verification", "真实验收", "线上健康检查通过", evidence));
        candidates.add(new ProjectMemoryCandidate("verification", "真实验收", "线上健康检查通过", evidence));
        candidates.add(new ProjectMemoryCandidate("operation", "凭据", "password=super-secret-value", evidence));
        for (int index = 0; index < 6; index++) {
            candidates.add(new ProjectMemoryCandidate("context", "背景 " + index, "内容 " + index, evidence));
        }

        var result = service.persistCandidates(run(), candidates);

        assertThat(result).hasSize(3);
        verify(memories, org.mockito.Mockito.times(3)).insert(any(ProjectMemoryEntity.class));
        verify(writes, org.mockito.Mockito.times(3)).insert(any(AiRunMemoryWriteEntity.class));
    }

    @Test
    void fixedCategoriesAreEnforced() {
        assertThatThrownBy(() -> service.create("p1", "wg",
                new SaveProjectMemoryRequest("personal", "生活记忆", "不应进入项目域", List.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("分类");
    }

    private static AiTaskRunEntity run() {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId("ar1");
        run.setTaskId("t1");
        run.setProjectId("p1");
        return run;
    }

    private static ProjectMemoryEntity memory(String id, String category, String title, String content,
            LocalDateTime updatedAt) {
        ProjectMemoryEntity memory = new ProjectMemoryEntity();
        memory.setId(id);
        memory.setProjectId("p1");
        memory.setCategory(category);
        memory.setTitle(title);
        memory.setContent(content);
        memory.setEvidenceLinksJson("[]");
        memory.setUpdatedAt(updatedAt);
        return memory;
    }
}
