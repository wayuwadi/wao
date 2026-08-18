package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.PersonalMemoryEntity;
import cn.wayuwa.wao.mapper.PersonalMemoryMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class PersonalMemoryServiceTest {
    private final PersonalMemoryMapper mapper = mock(PersonalMemoryMapper.class);
    private final PersonalMemoryService service = new PersonalMemoryService(mapper);

    @Test
    void createsManualAndAiTextWithServerControlledOwnershipAndSource() {
        service.createManualText("wg", "晨跑偏好", "喜欢清晨安静地跑步", "preference");
        service.createAiText("f", "长期计划", "每周学习三次", "plan");

        ArgumentCaptor<PersonalMemoryEntity> inserted = ArgumentCaptor.forClass(PersonalMemoryEntity.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(PersonalMemoryEntity::getMemberId)
                .containsExactly("wg", "f");
        assertThat(inserted.getAllValues()).extracting(PersonalMemoryEntity::getCaptureMode)
                .containsExactly("manual", "ai");
    }

    @Test
    void storesRealImageBytesAndReturnsOnlyAttachmentMetadata() {
        byte[] bytes = "real-image".getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "旅行照.png", "image/png", bytes);

        var saved = service.upload("wg", "海边旅行", "2026 年夏天", "experience", file);

        ArgumentCaptor<PersonalMemoryEntity> inserted = ArgumentCaptor.forClass(PersonalMemoryEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getFileContent()).isEqualTo(bytes);
        assertThat(saved.mediaType()).isEqualTo("image");
        assertThat(saved.fileName()).isEqualTo("旅行照.png");
        assertThat(saved.downloadUrl()).contains(saved.id());
    }

    @Test
    void treatsAnotherMembersAttachmentIdAsMissing() {
        when(mapper.selectOwnedWithContent("mem-other", "wg")).thenReturn(null);

        assertThatThrownBy(() -> service.attachment("wg", "mem-other"))
                .isInstanceOf(NotFoundException.class).hasMessage("记忆不存在");
        verify(mapper).selectOwnedWithContent("mem-other", "wg");
    }

    @Test
    void mcpSearchReturnsOnlyRowsAlreadyScopedByMember() {
        PersonalMemoryEntity own = new PersonalMemoryEntity();
        own.setId("mem-own");
        own.setMemberId("wg");
        own.setTitle("长期规划");
        own.setContent("坚持记录个人生活");
        own.setCategory("plan");
        own.setCaptureMode("manual");
        own.setMediaType("text");
        own.setCreatedAt(java.time.LocalDateTime.now());
        own.setUpdatedAt(java.time.LocalDateTime.now());
        when(mapper.selectList(any())).thenReturn(java.util.List.of(own));

        String result = service.searchForMcp("wg", "长期规划", "https://wao.example");

        assertThat(result).contains("坚持记录个人生活", "https://wao.example/memory?entry=mem-own");
    }
}
