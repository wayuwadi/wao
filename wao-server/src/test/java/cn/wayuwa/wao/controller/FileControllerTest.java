package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.dto.RenameFileRequest;
import cn.wayuwa.wao.service.DownloadTicketService;
import cn.wayuwa.wao.service.FileService;
import cn.wayuwa.wao.service.ProjectService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class FileControllerTest {
    @Test
    void publicPreviewRequiresAnUnconsumedTicket() {
        var tickets = mock(DownloadTicketService.class);
        var previewController = new FileController(files, currentMember, tickets, mock(AuthSessionManager.class), projects);
        assertThatThrownBy(() -> previewController.publicPreview("doc", "bad"))
                .isInstanceOf(cn.wayuwa.wao.common.UnauthorizedException.class);
        org.mockito.Mockito.verifyNoInteractions(files);
        when(tickets.consume("doc", "valid")).thenReturn(true);
        previewController.publicPreview("doc", "valid");
        verify(files).preview("doc");
    }
    private final FileService files = mock(FileService.class);
    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final FileController controller = new FileController(files, currentMember,
            mock(DownloadTicketService.class), mock(AuthSessionManager.class), projects);

    @Test
    void streamReturnsRequestedAudioRangeForSeeking() {
        FileBlobEntity entity = audio("雨夜.mp3", "audio/mpeg", "0123456789");
        when(files.raw("song-1")).thenReturn(entity);

        var response = controller.stream("song-1", "bytes=2-5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("2345");
    }

    @Test
    void streamUnderstandsSuffixRangeAndRejectsOutOfBoundsRange() {
        when(files.raw("song-1")).thenReturn(audio("雨夜.mp3", "application/octet-stream", "0123456789"));

        var suffix = controller.stream("song-1", "bytes=-3");
        var invalid = controller.stream("song-1", "bytes=99-");

        assertThat(new String(suffix.getBody(), StandardCharsets.UTF_8)).isEqualTo("789");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(invalid.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
    }

    @Test
    void streamAllowsVideoForAuthenticatedDocumentPlayback() {
        when(files.raw("video-1")).thenReturn(audio("演示.mp4", "video/mp4", "video-bytes"));

        var response = controller.stream("video-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("video/mp4");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
    }

    @Test
    void streamRefusesNonAudioFiles() {
        when(files.raw("doc-1")).thenReturn(audio("说明.html", "text/html", "<h1>no</h1>"));

        assertThatThrownBy(() -> controller.stream("doc-1", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不是可播放的音频");
    }

    @Test
    void renameRequiresProjectAccessBeforeChangingDisplayName() {
        FileBlobEntity entity = audio("architecture.html", "text/html", "<h1>architecture</h1>");
        entity.setProjectId("p1");
        when(files.metadata("file-1")).thenReturn(entity);
        when(currentMember.id(null)).thenReturn("wg");

        controller.rename("file-1", new RenameFileRequest("产品架构"), null);

        verify(projects).requireAccess("p1", "wg");
        verify(files).renameDisplayName("file-1", "产品架构");
    }

    private static FileBlobEntity audio(String name, String contentType, String body) {
        FileBlobEntity entity = new FileBlobEntity();
        entity.setName(name);
        entity.setContentType(contentType);
        entity.setContent(body.getBytes(StandardCharsets.UTF_8));
        return entity;
    }
    @Test
    void replacementChecksProjectAccessBeforeWritingContent() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var file = new org.springframework.mock.web.MockMultipartFile("file", "new.pdf", "application/pdf", "new".getBytes());
        var entity = new FileBlobEntity(); entity.setProjectId("p1");
        when(files.metadata("f1")).thenReturn(entity);
        when(currentMember.id(request)).thenReturn("member");
        controller.replace("f1", file, request);
        var order = org.mockito.Mockito.inOrder(projects, files);
        order.verify(projects).requireAccess("p1", "member");
        order.verify(files).replace("f1", file);
        org.mockito.Mockito.reset(files);
        when(files.metadata("f1")).thenReturn(entity);
        org.mockito.Mockito.doThrow(new cn.wayuwa.wao.common.NotFoundException("项目不可见"))
                .when(projects).requireAccess("p1", "member");
        assertThatThrownBy(() -> controller.replace("f1", file, request)).hasMessageContaining("项目不可见");
        org.mockito.Mockito.verify(files, org.mockito.Mockito.never()).replace("f1", file);
    }

}
