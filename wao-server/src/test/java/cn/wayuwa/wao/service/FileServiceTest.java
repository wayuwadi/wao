package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.dto.FileSettingsDto;
import cn.wayuwa.wao.mapper.FileBlobMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FileServiceTest {
    private final FileBlobMapper mapper = mock(FileBlobMapper.class);
    private final FileSettingsService settings = mock(FileSettingsService.class);
    private final FileService service = new FileService(mapper, settings);

    private void defaultLimit() {
        when(settings.get()).thenReturn(new FileSettingsDto(50, 10, true));
    }

    @Test
    void uploadPersistsAndReturnsDirectDownloadLink() {
        defaultLimit();
        when(mapper.insert(any(FileBlobEntity.class))).thenReturn(1);
        var file = new MockMultipartFile("file", "../验收.pdf", "application/pdf", "ok".getBytes());

        var item = service.upload(file, "wg");

        assertThat(item.name()).isEqualTo("验收.pdf");
        assertThat(item.contentType()).isEqualTo("application/pdf");
        assertThat(item.link()).endsWith("/download");
        assertThat(item.icon()).isEqualTo("ri-file-pdf-2-line");
        assertThat(item.createdAt()).isNotBlank();
        verify(mapper).insert(any(FileBlobEntity.class));
    }

    @Test
    void emptyFileIsRejected() {
        defaultLimit();
        var file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> service.upload(file, "wg")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void configuredUploadLimitIsEnforcedByTheServer() {
        when(settings.get()).thenReturn(new FileSettingsDto(1, 10, true));
        var file = new MockMultipartFile("file", "large.bin", "application/octet-stream",
                new byte[1024 * 1024 + 1]);

        assertThatThrownBy(() -> service.upload(file, "wg"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1MB");
    }

    @Test
    void projectUploadKeepsProjectClassificationAndMarkdownPreviewUsesUtf8() {
        defaultLimit();
        var file = new MockMultipartFile("file", "报告.md", "text/markdown", "# 验收\n内容".getBytes());

        var item = service.upload(file, "wg", "p1");
        ArgumentCaptor<FileBlobEntity> saved = ArgumentCaptor.forClass(FileBlobEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getProjectId()).isEqualTo("p1");
        assertThat(item.projectId()).isEqualTo("p1");

        when(mapper.selectById(item.id())).thenReturn(saved.getValue());
        assertThat(service.preview(item.id()).content()).contains("# 验收", "内容");
    }

    @Test
    void previewRejectsNonMarkdownAndInvalidUtf8() {
        FileBlobEntity text = new FileBlobEntity();
        text.setId("f1"); text.setName("说明.txt"); text.setContentType("text/plain"); text.setContent("ok".getBytes());
        when(mapper.selectById("f1")).thenReturn(text);
        assertThatThrownBy(() -> service.preview("f1")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Markdown");

        text.setName("说明.md"); text.setContentType("text/markdown"); text.setContent(new byte[] {(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> service.preview("f1")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("UTF-8");
    }
}
