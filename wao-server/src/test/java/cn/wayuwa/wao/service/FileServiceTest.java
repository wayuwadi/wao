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

    @Test
    void renamePersistsTrimmedDisplayNameWithoutChangingOriginalName() {
        FileBlobEntity metadata = new FileBlobEntity();
        metadata.setId("f1"); metadata.setName("architecture.html"); metadata.setDisplayName("产品架构");
        metadata.setContentType("text/html"); metadata.setByteSize(10L);
        when(mapper.updateDisplayName("f1", "产品架构")).thenReturn(1);
        when(mapper.findMeta("f1")).thenReturn(metadata);

        var item = service.renameDisplayName("f1", "  产品架构  ");

        assertThat(item.name()).isEqualTo("architecture.html");
        assertThat(item.displayName()).isEqualTo("产品架构");
        verify(mapper).updateDisplayName("f1", "产品架构");
    }

    @Test
    void renameRejectsBlankOrOversizedDisplayName() {
        assertThatThrownBy(() -> service.renameDisplayName("f1", "  "))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("1～255");
        assertThatThrownBy(() -> service.renameDisplayName("f1", "x".repeat(256)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("1～255");
    }
    @Test
    void replacementKeepsIdentityAndClassificationAndOnlyWritesContentColumns() {
        defaultLimit();
        var metadata = new FileBlobEntity();
        metadata.setId("f1"); metadata.setName("new.pdf"); metadata.setDisplayName("保留名称");
        metadata.setProjectId("p1"); metadata.setCreatedBy("wg"); metadata.setByteSize(3L);
        when(mapper.findMeta("f1")).thenReturn(metadata);
        var bytes = "new".getBytes();
        when(mapper.replaceContent("f1", "new.pdf", "application/pdf", 3L, bytes)).thenReturn(1);
        var result = service.replace("f1", new MockMultipartFile("file", "../new.pdf", "application/pdf", bytes));
        assertThat(result.id()).isEqualTo("f1");
        assertThat(result.projectId()).isEqualTo("p1");
        assertThat(result.displayName()).isEqualTo("保留名称");
        assertThat(result.link()).isEqualTo("/api/files/f1/download");
        verify(mapper).replaceContent("f1", "new.pdf", "application/pdf", 3L, bytes);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(FileBlobEntity.class));
    }

    @Test
    void replacementValidationAndReadFailuresNeverWriteOrDeleteOldContent() throws Exception {
        defaultLimit();
        when(mapper.findMeta("f1")).thenReturn(new FileBlobEntity());
        var broken = mock(org.springframework.web.multipart.MultipartFile.class);
        when(broken.isEmpty()).thenReturn(false);
        when(broken.getSize()).thenReturn(10L);
        when(broken.getBytes()).thenThrow(new java.io.IOException("读取失败"));
        assertThatThrownBy(() -> service.replace("f1", broken)).hasMessageContaining("读取上传文件失败");
        assertThatThrownBy(() -> service.replace("f1", new MockMultipartFile("file", new byte[0])))
                .isInstanceOf(BadRequestException.class);
        when(settings.get()).thenReturn(new FileSettingsDto(1, 10, true));
        assertThatThrownBy(() -> service.replace("f1", new MockMultipartFile("file", new byte[1024 * 1024 + 1])))
                .hasMessageContaining("1MB");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).replaceContent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), any(byte[].class));
    }

    @Test
    void replacementOfMissingOrConcurrentlyDeletedFileReturnsNotFound() {
        defaultLimit();
        var file = new MockMultipartFile("file", "new.pdf", "application/pdf", "new".getBytes());
        assertThatThrownBy(() -> service.replace("gone", file))
                .isInstanceOf(cn.wayuwa.wao.common.NotFoundException.class);
        when(mapper.findMeta("gone")).thenReturn(new FileBlobEntity());
        assertThatThrownBy(() -> service.replace("gone", file))
                .isInstanceOf(cn.wayuwa.wao.common.NotFoundException.class);
    }

}
