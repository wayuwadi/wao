package cn.wayuwa.wao.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.GoneException;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 旧入口只读兼容，绝不创建新的强制三类或图片任务。 */
class NoteMiningServiceTest {
    private final NoteMiningMapper mapper=mock(NoteMiningMapper.class);
    private final NoteMiningVisualMapper visuals=mock(NoteMiningVisualMapper.class);
    private final AiAssistantService assistant=mock(AiAssistantService.class);
    private final ArticleImageService images=mock(ArticleImageService.class);
    private final NoteMiningService service=new NoteMiningService(mapper,visuals,assistant,images,mock(MemberPreferenceMapper.class),new ObjectMapper());
    @Test void manualStartIsRetiredEvenWithOldRequestId() {
        assertThatThrownBy(()->service.start("m1","0123456789abcdef0123456789abcdef")).isInstanceOf(GoneException.class);
        verifyNoInteractions(mapper,assistant,images);
    }
    @Test void imageRetryCannotBypassPauseOrBudget() {
        assertThatThrownBy(()->service.retryVisual("m1","old","PROGRESS")).isInstanceOf(GoneException.class);verifyNoInteractions(images,visuals);
    }
    @Test void legacyArtifactRemainsReadableWithoutAi() {
        var job=new NoteMiningMapper.Job("old","m1","[]",2,"expired",LocalDateTime.now(),null,LocalDateTime.now());
        when(mapper.latestJob("m1")).thenReturn(job);when(mapper.lockOwnedJob("old","m1")).thenReturn(job);
        when(mapper.artifacts("old","m1")).thenReturn(List.of(new NoteMiningMapper.Artifact("a","old","m1","INSIGHT","旧内容","旧理由","SAVED",null,null)));
        assertThat(service.latest("m1").artifacts()).extracting(a->a.content()).containsExactly("旧内容");verifyNoInteractions(assistant,images);
    }
    @Test void unfinishedLegacyJobReportsRetirementWithoutMaterializing() {
        when(mapper.lockOwnedJob("old","m1")).thenReturn(new NoteMiningMapper.Job("old","m1","[]",2,"run",null,null,null));
        assertThat(service.get("m1","old").job().status()).isEqualTo("FAILED");verifyNoInteractions(assistant,images);verify(mapper).markError(eq("old"),contains("退役"));
    }
    @Test void pendingImagesDoNotStartNewCalls() {
        when(visuals.lock("old","PROGRESS")).thenReturn(new NoteMiningVisualMapper.Visual("old","m1","PROGRESS","旧图","理由","[0]",null,null,"PENDING",null,"SAVED",null,null));
        service.advanceVisual("old","PROGRESS");verifyNoInteractions(images);verify(visuals).updateImage("old","m1","PROGRESS","FAILED",null,null,"旧版配图已退役，历史图片仍保留");
    }
    @Test void anotherMembersHistoricalJobIsNotFound() { assertThatThrownBy(()->service.get("m2","old")).hasMessageContaining("不存在"); }
}
