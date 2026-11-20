package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DrawingAiJobServiceTest {
    private final LocalNoteAiService runner = mock(LocalNoteAiService.class);
    private final AiAssistantRunMapper runs = mock(AiAssistantRunMapper.class);
    private final DrawingAiJobService service = new DrawingAiJobService(runner, runs, new ObjectMapper());

    @Test
    void layoutDoesNotReadPhotosOrOtherMembersJobs() {
        var job = new AiAssistantRunEntity();
        job.setId("job");
        job.setUsagePurpose("DRAWING_LAYOUT");
        job.setMemberId("owner");
        job.setStatus("provider_running");
        when(runs.selectById("job")).thenReturn(job);

        assertThat(service.getLayout("owner", "job").status()).isEqualTo("running");
        assertThatThrownBy(() -> service.getLayout("other", "job")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getPhoto("owner", "job")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(runner);
    }

    @Test
    void queuesOnlyValidatedLayoutSnapshotsWithoutAnImageReference() {
        assertThatThrownBy(() -> service.startLayout("owner", "{\"nodes\":[],\"links\":[]}", "b".repeat(32)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(runner, runs);

        var job = new AiAssistantRunEntity();
        job.setUsagePurpose("DRAWING_LAYOUT");
        job.setMemberId("owner");
        job.setStatus("secure_queued");
        when(runs.selectById(anyString())).thenReturn(null, job);
        assertThat(service.startLayout("owner", "{\"nodes\":[{},{}],\"links\":[]}", "a".repeat(32)).status())
                .isEqualTo("queued");
        verify(runner).enqueueDrawingJob(anyString(), eq("owner"), anyString(), eq("DRAWING_LAYOUT"), isNull());
    }
}
