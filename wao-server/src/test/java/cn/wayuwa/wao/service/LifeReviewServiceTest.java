package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.mapper.AiAssistantConversationMapper;
import cn.wayuwa.wao.mapper.AiAssistantMessageMapper;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.LifeReviewMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LifeReviewServiceTest {
    private final LifeReviewService service = new LifeReviewService(
            mock(LifeReviewMapper.class), mock(MemberMapper.class), mock(TaskMapper.class),
            mock(ProjectMapper.class), mock(AiAssistantConversationMapper.class),
            mock(AiAssistantMessageMapper.class), mock(DocMapper.class), mock(SettingService.class),
            mock(AiAssistantService.class), new ObjectMapper().findAndRegisterModules());

    @Test
    void acceptsCompleteReportWhenEveryConclusionReferencesKnownEvidence() {
        var report = service.parseReport("""
                {"pastActions":"完成了登录修复 [S1]",\
                "meaningfulTopics":"讨论了工作边界 [S2]",\
                "trajectory":"如果当前模式持续，可能更重视长期积累；时间投入变化会改变趋势 [S1][S2]",\
                "nextFocus":"- 哪件事最值得继续？"}
                """, 2);

        assertThat(report.pastActions()).contains("[S1]");
        assertThat(report.trajectory()).contains("可能").contains("改变趋势");
    }

    @Test
    void rejectsInventedSourceReferenceOrConclusionWithoutEvidence() {
        assertThatThrownBy(() -> service.parseReport("""
                {"pastActions":"完成事项 [S3]","meaningfulTopics":"话题 [S1]",\
                "trajectory":"可能继续 [S1]","nextFocus":"继续观察"}
                """, 2)).isInstanceOf(BadRequestException.class).hasMessageContaining("可追溯");

        assertThatThrownBy(() -> service.parseReport("""
                {"pastActions":"完成事项","meaningfulTopics":"话题 [S1]",\
                "trajectory":"可能继续 [S1]","nextFocus":"继续观察"}
                """, 1)).isInstanceOf(BadRequestException.class).hasMessageContaining("可追溯");
    }

    @Test
    void rejectsExtraFieldsSoModelCannotSmuggleUnreviewedSections() {
        assertThatThrownBy(() -> service.parseReport("""
                {"pastActions":"事项 [S1]","meaningfulTopics":"话题 [S1]",\
                "trajectory":"趋势 [S1]","nextFocus":"问题","fortune":"确定会发财"}
                """, 1)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsDeterministicTrajectoryEvenWhenItCitesEvidence() {
        assertThatThrownBy(() -> service.parseReport("""
                {"pastActions":"事项 [S1]","meaningfulTopics":"话题 [S1]",\
                "trajectory":"你注定会成功 [S1]","nextFocus":"问题"}
                """, 1)).isInstanceOf(BadRequestException.class).hasMessageContaining("可追溯");
    }
}
