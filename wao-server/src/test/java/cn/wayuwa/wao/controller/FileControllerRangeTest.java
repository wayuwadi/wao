package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.service.DownloadTicketService;
import cn.wayuwa.wao.service.FileService;
import cn.wayuwa.wao.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class FileControllerRangeTest {
    private final FileService files = mock(FileService.class);
    private final FileController controller = new FileController(files, mock(CurrentMember.class),
            mock(DownloadTicketService.class), mock(AuthSessionManager.class), mock(ProjectService.class));

    @Test
    void byteRangeKeepsPartialResponseContractAndReadsOnlyRequestedBytes() {
        FileBlobEntity metadata = media();
        when(files.metadata("f1")).thenReturn(metadata);
        when(files.readRange(metadata, 10, 19)).thenReturn(new byte[10]);

        var response = controller.stream("f1", "bytes=10-19");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 10-19/100");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(10);
        assertThat(response.getBody()).hasSize(10);
        verify(files).readRange(metadata, 10, 19);
        verify(files, never()).raw("f1");
    }

    @Test
    void invalidRangeReturns416WithoutReadingFileBytes() {
        FileBlobEntity metadata = media();
        when(files.metadata("f1")).thenReturn(metadata);

        var response = controller.stream("f1", "bytes=100-");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */100");
        verify(files, never()).raw("f1");
    }

    private static FileBlobEntity media() {
        FileBlobEntity metadata = new FileBlobEntity();
        metadata.setId("f1");
        metadata.setName("clip.mp4");
        metadata.setContentType("video/mp4");
        metadata.setByteSize(100L);
        return metadata;
    }
}
