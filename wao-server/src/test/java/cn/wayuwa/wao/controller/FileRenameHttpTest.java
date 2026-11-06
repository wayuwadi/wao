package cn.wayuwa.wao.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.mapper.FileBlobMapper;
import cn.wayuwa.wao.service.DownloadTicketService;
import cn.wayuwa.wao.service.FileService;
import cn.wayuwa.wao.service.FileSettingsService;
import cn.wayuwa.wao.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FileRenameHttpTest {
    private final FileBlobMapper mapper = mock(FileBlobMapper.class);
    private final FileBlobEntity metadata = new FileBlobEntity();
    private MockMvc http;

    @BeforeEach
    void controller() {
        metadata.setId("file"); metadata.setName("original.pdf"); metadata.setDisplayName("最新名称");
        when(mapper.findMeta("file")).thenReturn(metadata);
        var member = mock(CurrentMember.class);
        when(member.id(any())).thenReturn("member");
        var controller = new FileController(new FileService(mapper, mock(FileSettingsService.class)), member,
                mock(DownloadTicketService.class), mock(AuthSessionManager.class), mock(ProjectService.class));
        http = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void staleExpectedNameReturns409AndNeverFallsBackToAnUnconditionalWrite() throws Exception {
        http.perform(patch("/api/files/file").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"本机草稿\",\"expectedDisplayName\":\"旧名称\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value("文件名称已被修改，请读取最新名称后重试"));
        verify(mapper).compareAndSetDisplayName("file", "本机草稿", "旧名称");
        verify(mapper, never()).updateDisplayName(anyString(), anyString());
    }

    @Test
    void explicitNullEnablesCasButAnOmittedFieldKeepsTheLegacyContract() throws Exception {
        http.perform(patch("/api/files/file").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"新名称\",\"expectedDisplayName\":null}"))
                .andExpect(status().isConflict());
        verify(mapper).compareAndSetDisplayName("file", "新名称", null);

        when(mapper.updateDisplayName("file", "新名称")).thenReturn(1);
        http.perform(patch("/api/files/file").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"新名称\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(mapper).updateDisplayName("file", "新名称");
    }

    @Test
    void aRetriedSuccessIsIdempotentAndDeletionStillReturns404() throws Exception {
        http.perform(patch("/api/files/file").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"最新名称\",\"expectedDisplayName\":\"旧名称\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("最新名称"));
        when(mapper.findMeta("file")).thenReturn(null);
        http.perform(patch("/api/files/file").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"草稿\",\"expectedDisplayName\":null}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void fieldValidationStillRejectsBlankAndOversizedNamesBeforeWriting() throws Exception {
        http.perform(patch("/api/files/file").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\" \"}"))
                .andExpect(status().isBadRequest());
        http.perform(patch("/api/files/file").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"新名称\",\"expectedDisplayName\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(mapper, never()).updateDisplayName(anyString(), anyString());
        verify(mapper, never()).compareAndSetDisplayName(anyString(), anyString(), any());
    }
}
