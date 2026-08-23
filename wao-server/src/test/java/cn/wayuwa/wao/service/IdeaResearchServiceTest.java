package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.IdeaEntity;
import cn.wayuwa.wao.domain.IdeaResearchRunEntity;
import cn.wayuwa.wao.dto.AiAssistantClaimDto;
import cn.wayuwa.wao.mapper.IdeaArtifactMapper;
import cn.wayuwa.wao.mapper.IdeaMapper;
import cn.wayuwa.wao.mapper.IdeaResearchRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdeaResearchServiceTest {
    private final IdeaResearchRunMapper runs = mock(IdeaResearchRunMapper.class);
    private final IdeaMapper ideas = mock(IdeaMapper.class);
    private final IdeaArtifactMapper artifacts = mock(IdeaArtifactMapper.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private IdeaResearchService service;

    @BeforeEach
    void setUp() {
        service = new IdeaResearchService(runs, ideas, artifacts, knowledge);
        IdeaEntity idea = new IdeaEntity(); idea.setId("i1"); idea.setTitle("点子");
        idea.setRawIdea("原始想法"); idea.setProblemText("问题"); idea.setTargetOutcome("目标");
        when(ideas.selectById("i1")).thenReturn(idea);
    }

    @Test
    void enqueueKeepsIdeaStageUntouchedAndBuildsSourceGroundedPrompt() {
        when(runs.selectCount(any())).thenReturn(0L);

        service.enqueue("i1", "wg");

        var captor = org.mockito.ArgumentCaptor.forClass(IdeaResearchRunEntity.class);
        verify(runs).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("QUEUED");
        assertThat(captor.getValue().getPromptText()).contains("3 到 5", "直接来源 URL", "绝不虚构");
        verify(ideas, org.mockito.Mockito.never()).updateById(any(IdeaEntity.class));
    }

    @Test
    void sameIdeaCannotStartTwoResearchRunsAtOnce() {
        when(runs.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.enqueue("i1", "wg"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("正在执行");
    }

    @Test
    void claimedResearchUsesDedicatedMode() {
        IdeaResearchRunEntity queued = new IdeaResearchRunEntity(); queued.setId("ir1"); queued.setStatus("QUEUED");
        IdeaResearchRunEntity claimed = new IdeaResearchRunEntity(); claimed.setId("ir1"); claimed.setStatus("RUNNING"); claimed.setPromptText("prompt");
        when(runs.selectList(any())).thenReturn(List.of(queued));
        when(runs.claim(any(), any(), any())).thenReturn(1);
        when(runs.selectById("ir1")).thenReturn(claimed);

        AiAssistantClaimDto claim = service.claim("rn1", null);

        assertThat(claim.mode()).isEqualTo("IDEA_RESEARCH");
        assertThat(claim.maxRuntimeSeconds()).isEqualTo(3600);
    }

    @Test
    void staleResearchIsClosedSoThePromptDoesNotPersistForever() {
        when(runs.expireStale()).thenReturn(2);
        service.expireStale();
        verify(runs).expireStale();
    }
}
