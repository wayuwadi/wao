package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
    void s3UploadAndReadKeepPrivateDownloadContractWithoutMysqlBody() {
        defaultLimit();
        BinaryObjectStore objects = mock(BinaryObjectStore.class);
        when(objects.writesEnabled()).thenReturn(true);
        FileService s3 = new FileService(mapper, settings, objects);
        byte[] bytes = "file-body".getBytes();

        var item = s3.upload(new MockMultipartFile("file", "a.pdf", "application/pdf", bytes), "wg");

        ArgumentCaptor<FileBlobEntity> saved = ArgumentCaptor.forClass(FileBlobEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getContent()).isNull();
        assertThat(saved.getValue().getObjectKey()).startsWith("files/" + item.id() + "/");
        verify(objects).put(saved.getValue().getObjectKey(), bytes, "application/pdf");
        when(mapper.findMeta(item.id())).thenReturn(saved.getValue());
        when(objects.get(saved.getValue().getObjectKey())).thenReturn(bytes);
        assertThat(s3.raw(item.id()).getContent()).isEqualTo(bytes);
        verify(mapper, never()).selectById(item.id());
        assertThat(item.link()).isEqualTo("/api/files/" + item.id() + "/download");
    }

    @Test
    void s3ReadFallsBackToRetainedMysqlBytesOnlyWhenObjectReadFails() {
        BinaryObjectStore objects = mock(BinaryObjectStore.class);
        FileService s3 = new FileService(mapper, settings, objects);
        FileBlobEntity metadata = new FileBlobEntity();
        metadata.setId("f1");
        metadata.setObjectKey("files/f1/hash");
        FileBlobEntity retained = new FileBlobEntity();
        retained.setId("f1");
        retained.setContent("retained".getBytes());
        when(mapper.findMeta("f1")).thenReturn(metadata);
        when(mapper.selectById("f1")).thenReturn(retained);
        when(objects.get(metadata.getObjectKey())).thenThrow(new IllegalStateException("offline"));

        assertThat(s3.raw("f1").getContent()).isEqualTo("retained".getBytes());
        retained.setContent(null);
        assertThatThrownBy(() -> s3.raw("f1"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void mediaRangeUsesS3PartialReadWithoutLoadingTheMysqlBlob() {
        BinaryObjectStore objects = mock(BinaryObjectStore.class);
        FileService s3 = new FileService(mapper, settings, objects);
        FileBlobEntity metadata = new FileBlobEntity();
        metadata.setId("f1");
        metadata.setByteSize(100L);
        metadata.setObjectKey("files/f1/immutable-hash");
        when(objects.getRange(metadata.getObjectKey(), 10, 19)).thenReturn(new byte[10]);

        assertThat(s3.readRange(metadata, 10, 19)).hasSize(10);

        verify(objects).getRange(metadata.getObjectKey(), 10, 19);
        verify(objects, never()).get(metadata.getObjectKey());
        verify(mapper, never()).selectById("f1");
    }

    @Test
    void mediaRangeFallsBackToRetainedMysqlCopyIfS3IsUnavailable() {
        BinaryObjectStore objects = mock(BinaryObjectStore.class);
        FileService s3 = new FileService(mapper, settings, objects);
        FileBlobEntity metadata = new FileBlobEntity();
        metadata.setId("f1");
        metadata.setByteSize(5L);
        metadata.setObjectKey("files/f1/immutable-hash");
        FileBlobEntity stored = new FileBlobEntity();
        stored.setId("f1");
        stored.setByteSize(5L);
        stored.setObjectKey(metadata.getObjectKey());
        stored.setContent("12345".getBytes());
        when(mapper.findMeta("f1")).thenReturn(metadata);
        when(mapper.selectById("f1")).thenReturn(stored);
        when(objects.getRange(metadata.getObjectKey(), 1, 3)).thenThrow(new IllegalStateException("offline"));
        when(objects.get(metadata.getObjectKey())).thenThrow(new IllegalStateException("offline"));

        assertThat(s3.readRange(metadata, 1, 3)).isEqualTo("234".getBytes());
    }

    @Test
    void dualWriteRetainsMysqlCopyIfObjectUploadFails() {
        defaultLimit();
        BinaryObjectStore objects = mock(BinaryObjectStore.class);
        when(objects.writesEnabled()).thenReturn(true);
        when(objects.retainMysqlCopy()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("offline"))
                .when(objects).put(org.mockito.ArgumentMatchers.anyString(), any(byte[].class),
                        org.mockito.ArgumentMatchers.anyString());
        FileService dual = new FileService(mapper, settings, objects);

        dual.upload(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {8}), "wg");

        ArgumentCaptor<FileBlobEntity> saved = ArgumentCaptor.forClass(FileBlobEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getContent()).containsExactly(8);
        assertThat(saved.getValue().getObjectKey()).isNull();
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
    void rejectsFileAboveTwoHundredMbBeforeReadingItsBody() throws Exception {
        when(settings.get()).thenReturn(new FileSettingsDto(200, 10, true));
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(200L * 1024 * 1024 + 1);

        assertThatThrownBy(() -> service.upload(file, "wg"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("200MB");
        verify(file, never()).getBytes();
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

        when(mapper.findMeta(item.id())).thenReturn(saved.getValue());
        when(mapper.selectById(item.id())).thenReturn(saved.getValue());
        assertThat(service.preview(item.id()).content()).contains("# 验收", "内容");
    }

    @Test
    void previewRejectsNonMarkdownAndInvalidUtf8() {
        FileBlobEntity text = new FileBlobEntity();
        text.setId("f1"); text.setName("说明.txt"); text.setContentType("text/plain"); text.setContent("ok".getBytes());
        when(mapper.findMeta("f1")).thenReturn(text);
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
        String hash = ImageService.sha256(bytes);
        when(mapper.replaceContent("f1", "new.pdf", "application/pdf", 3L, bytes, null, hash)).thenReturn(1);
        var result = service.replace("f1", new MockMultipartFile("file", "../new.pdf", "application/pdf", bytes));
        assertThat(result.id()).isEqualTo("f1");
        assertThat(result.projectId()).isEqualTo("p1");
        assertThat(result.displayName()).isEqualTo("保留名称");
        assertThat(result.link()).isEqualTo("/api/files/f1/download");
        verify(mapper).replaceContent("f1", "new.pdf", "application/pdf", 3L, bytes, null, hash);
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
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
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
