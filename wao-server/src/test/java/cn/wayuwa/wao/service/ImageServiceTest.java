package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.dto.FileSettingsDto;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

class ImageServiceTest {

    private final ImageBlobMapper mapper = mock(ImageBlobMapper.class);
    private final ContentService content = mock(ContentService.class);
    private final FileSettingsService settings = mock(FileSettingsService.class);
    private final ImageService service = new ImageService(mapper, content, settings);

    private void defaultLimit() {
        when(settings.get()).thenReturn(new FileSettingsDto(50, 10, true));
    }

    @Test
    void uploadStoresBytesAndReturnsRawUrl() {
        defaultLimit();
        MockMultipartFile file = new MockMultipartFile("file", "截图.png", "image/png", new byte[]{1, 2, 3});

        ImageItemDto dto = service.upload(file, "任务 · 整理证件盒");

        verify(mapper).insert(any(ImageBlobEntity.class));
        assertThat(dto.name()).isEqualTo("截图.png");
        assertThat(dto.from()).isEqualTo("任务 · 整理证件盒");
        assertThat(dto.url()).isEqualTo("/api/images/" + dto.id() + "/raw");
        assertThat(dto.size()).isEqualTo("3 B");
    }

    @Test
    void projectUploadKeepsProjectClassification() {
        defaultLimit();
        MockMultipartFile file = new MockMultipartFile("file", "截图.png", "image/png", new byte[]{1, 2, 3});

        ImageItemDto dto = service.upload(file, "项目 · WAO", "p1");

        ArgumentCaptor<ImageBlobEntity> saved = ArgumentCaptor.forClass(ImageBlobEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getProjectId()).isEqualTo("p1");
        assertThat(dto.projectId()).isEqualTo("p1");
    }

    @Test
    void uploadRejectsNonImageContentType() {
        defaultLimit();
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> service.upload(file, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("只支持");
    }

    @Test
    void uploadRejectsEmptyFile() {
        defaultLimit();
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(file, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void uploadRejectsOversizedImage() {
        defaultLimit();
        byte[] big = new byte[(int) ImageService.MAX_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);

        assertThatThrownBy(() -> service.upload(file, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void uploadStripsDirectoryFromFileName() {
        defaultLimit();
        MockMultipartFile file = new MockMultipartFile("file", "C:\\Users\\me\\a.png", "image/png", new byte[]{1});

        assertThat(service.upload(file, null).name()).isEqualTo("a.png");
    }

    @Test
    void listPutsUploadsBeforeSeedData() throws Exception {
        ImageBlobEntity uploaded = new ImageBlobEntity();
        uploaded.setId("img1");
        uploaded.setName("新图.png");
        uploaded.setByteSize(2048L);
        uploaded.setContentType("image/png");
        when(mapper.listMeta()).thenReturn(List.of(uploaded));
        when(content.images()).thenReturn(List.of(
                new ObjectMapper().readTree("{\"name\":\"演示图.png\",\"size\":\"1 MB\",\"url\":\"https://x/y.png\"}")));

        List<ImageItemDto> list = service.list();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).name()).isEqualTo("新图.png");
        assertThat(list.get(0).size()).isEqualTo("2 KB");
        assertThat(list.get(1).name()).isEqualTo("演示图.png");
        assertThat(list.get(1).id()).isNull();
    }

    @Test
    void rawRejectsMissingImage() {
        when(mapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.raw("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void storeWithDeduplicationReusesTheExistingCopyOfTheSameBytes() {
        ImageBlobEntity existing = new ImageBlobEntity();
        existing.setId("img-old");
        existing.setName("img_key.png");
        existing.setContentType("image/png");
        existing.setByteSize(3L);
        existing.setSource("飞书群聊 · 研发群");
        when(mapper.findByContentHash(anyString(), eq(3L))).thenReturn(existing);

        ImageItemDto dto = service.store(new byte[]{1, 2, 3}, "image/png", "img_key.png", "飞书群聊 · 研发群", true);

        assertThat(dto.id()).isEqualTo("img-old");
        assertThat(dto.url()).isEqualTo("/api/images/img-old/raw");
        verify(mapper, never()).insert(any(ImageBlobEntity.class));
    }

    @Test
    void storeWithDeduplicationStillInsertsWhenNothingMatches() {
        when(mapper.findByContentHash(anyString(), anyLong())).thenReturn(null);

        ImageItemDto dto = service.store(new byte[]{9, 9}, "image/png", "新图.png", "飞书群聊 · 研发群", true);

        ArgumentCaptor<ImageBlobEntity> saved = ArgumentCaptor.forClass(ImageBlobEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getContentHash()).hasSize(64);
        assertThat(saved.getValue().getDedupeKey()).isEqualTo(saved.getValue().getContentHash());
        assertThat(dto.name()).isEqualTo("新图.png");
    }

    @Test
    void concurrentDeduplicatedInsertReusesTheDatabaseUniqueKeyWinner() {
        when(mapper.findByContentHash(anyString(), eq(3L))).thenReturn(null);
        when(mapper.insert(any(ImageBlobEntity.class))).thenThrow(new DuplicateKeyException("dedupe race"));
        ImageBlobEntity winner = new ImageBlobEntity();
        winner.setId("img-winner");
        winner.setName("获胜图.png");
        winner.setContentType("image/png");
        winner.setByteSize(3L);
        when(mapper.findByDedupeKey(anyString())).thenReturn(winner);

        ImageItemDto dto = service.store(
                new byte[]{1, 2, 3}, "image/png", "候选图.png", "飞书群聊", true);

        assertThat(dto.id()).isEqualTo("img-winner");
        verify(mapper).findByDedupeKey(anyString());
    }

    @Test
    void uploadKeepsEveryCopySoDeletingOneNeverBreaksAnother() {
        defaultLimit();
        MockMultipartFile file = new MockMultipartFile("file", "截图.png", "image/png", new byte[]{1, 2, 3});

        service.upload(file, "任务 · 整理证件盒");

        // 用户主动上传不走去重：两处各自引用自己的那份，删掉一份不该让另一处变成死链。
        verify(mapper).insert(any(ImageBlobEntity.class));
        verify(mapper, never()).findByContentHash(anyString(), anyLong());
        ArgumentCaptor<ImageBlobEntity> saved = ArgumentCaptor.forClass(ImageBlobEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getDedupeKey()).isNull();
    }
}
