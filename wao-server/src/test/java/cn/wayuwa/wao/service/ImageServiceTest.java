package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ImageServiceTest {

    private final ImageBlobMapper mapper = mock(ImageBlobMapper.class);
    private final ContentService content = mock(ContentService.class);
    private final ImageService service = new ImageService(mapper, content);

    @Test
    void uploadStoresBytesAndReturnsRawUrl() {
        MockMultipartFile file = new MockMultipartFile("file", "截图.png", "image/png", new byte[]{1, 2, 3});

        ImageItemDto dto = service.upload(file, "任务 · 整理证件盒");

        verify(mapper).insert(any(ImageBlobEntity.class));
        assertThat(dto.name()).isEqualTo("截图.png");
        assertThat(dto.from()).isEqualTo("任务 · 整理证件盒");
        assertThat(dto.url()).isEqualTo("/api/images/" + dto.id() + "/raw");
        assertThat(dto.size()).isEqualTo("3 B");
    }

    @Test
    void uploadRejectsNonImageContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> service.upload(file, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("只支持");
    }

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(file, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void uploadRejectsOversizedImage() {
        byte[] big = new byte[(int) ImageService.MAX_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);

        assertThatThrownBy(() -> service.upload(file, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void uploadStripsDirectoryFromFileName() {
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
}
