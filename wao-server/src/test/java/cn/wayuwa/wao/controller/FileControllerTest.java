package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.service.DownloadTicketService;
import cn.wayuwa.wao.service.FileService;
import cn.wayuwa.wao.service.ProjectService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class FileControllerTest {
    private final FileService files = mock(FileService.class);
    private final FileController controller = new FileController(files, mock(CurrentMember.class),
            mock(DownloadTicketService.class), mock(AuthSessionManager.class), mock(ProjectService.class));

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
    void streamRefusesNonAudioFiles() {
        when(files.raw("doc-1")).thenReturn(audio("说明.html", "text/html", "<h1>no</h1>"));

        assertThatThrownBy(() -> controller.stream("doc-1", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不是可播放的音频");
    }

    private static FileBlobEntity audio(String name, String contentType, String body) {
        FileBlobEntity entity = new FileBlobEntity();
        entity.setName(name);
        entity.setContentType(contentType);
        entity.setContent(body.getBytes(StandardCharsets.UTF_8));
        return entity;
    }
}
