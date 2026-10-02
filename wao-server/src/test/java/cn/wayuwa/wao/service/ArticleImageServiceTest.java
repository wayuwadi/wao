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
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.ArticleImageRequest;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

class ArticleImageServiceTest {
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final AiAssistantRunMapper runs = mock(AiAssistantRunMapper.class);
    private final AiRunService aiRuns = mock(AiRunService.class);
    private final ImageService images = mock(ImageService.class);
    private final ArticleImageService service = new ArticleImageService(assistant, runs, aiRuns, images);

    @Test
    void startsForCurrentMemberAndHidesAnotherMembersJob() {
        when(assistant.enqueueArticleImageJob("me", "正文", "画面偏好", "gpt-6-astra", null)).thenReturn("ac1");
        AiAssistantRunEntity queued = run();
        queued.setMemberId("me");
        queued.setStatus("secure_queued");
        when(runs.selectById("ac1")).thenReturn(queued);
        assertThat(service.start("me", new ArticleImageRequest("正文", "画面偏好")).status()).isEqualTo("QUEUED");
        verify(assistant).enqueueArticleImageJob("me", "正文", "画面偏好", "gpt-6-astra", null);
        assertThatThrownBy(() -> service.get("another", "ac1")).isInstanceOf(NotFoundException.class);
        queued.setStatus("secure_running");
        assertThat(service.get("me", "ac1").status()).isEqualTo("RUNNING");
    }

    @Test
    void uploadsOnlyForCurrentClaimAndReturnsTheSameImageOnRetry() {
        AiAssistantRunEntity run = run();
        when(runs.selectById("ac1")).thenReturn(run);
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("linux2");
        when(aiRuns.authenticateRunner("token")).thenReturn(runner);
        byte[] png = png();
        MockMultipartFile file = new MockMultipartFile("image", "generated.png", "image/png", png);

        assertThatThrownBy(() -> service.upload("token", "ac1", "old-claim", file))
                .isInstanceOf(UnauthorizedException.class);
        verify(images, never()).store(any(), any(), any(), any());

        when(images.store(eq(png), eq("image/png"), eq("文章总览图.png"), any()))
                .thenReturn(new ImageItemDto("img1", "文章总览图.png", "1 KB", "今天", "总览图",
                        "/api/images/img1/raw", "#fff", "#eee"));
        when(runs.attachArticleImage("ac1", "linux2", "claim", "/api/images/img1/raw")).thenReturn(1);
        assertThat(service.upload("token", "ac1", "claim", file)).isEqualTo("/api/images/img1/raw");
        verify(runs).attachArticleImage("ac1", "linux2", "claim", "/api/images/img1/raw");

        run.setResponseText("/api/images/img1/raw");
        assertThat(service.upload("token", "ac1", "claim", file)).isEqualTo("/api/images/img1/raw");
        verify(images).store(eq(png), eq("image/png"), eq("文章总览图.png"), any());
    }

    @Test
    void rejectsNonPngAndRemovesAnOrphanWhenClaimChanges() {
        when(runs.selectById("ac1")).thenReturn(run());
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("linux2");
        when(aiRuns.authenticateRunner("token")).thenReturn(runner);
        assertThatThrownBy(() -> service.upload("token", "ac1", "claim",
                new MockMultipartFile("image", "fake.png", "image/png", new byte[32])))
                .isInstanceOf(BadRequestException.class);
        when(images.store(any(), any(), any(), any())).thenReturn(new ImageItemDto(
                "orphan", "x.png", "1 KB", "今天", "总览图", "/api/images/orphan/raw", "#fff", "#eee"));
        assertThatThrownBy(() -> service.upload("token", "ac1", "claim",
                new MockMultipartFile("image", "valid.png", "image/png", png())))
                .isInstanceOf(BadRequestException.class);
        verify(images).delete("orphan");
    }

    private static AiAssistantRunEntity run() {
        AiAssistantRunEntity run = new AiAssistantRunEntity();
        run.setId("ac1");
        run.setMemberId("me");
        run.setUsagePurpose(AiAssistantService.ARTICLE_IMAGE_PURPOSE);
        run.setStatus("secure_running");
        run.setRunnerId("linux2");
        run.setClaimToken("claim");
        return run;
    }

    private static byte[] png() {
        try {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
