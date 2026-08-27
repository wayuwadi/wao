package cn.wayuwa.wao.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.dto.ChatReviewConfigDto;
import cn.wayuwa.wao.dto.ChatReviewAutoConfigDto;
import cn.wayuwa.wao.dto.ChatReviewListDto;
import cn.wayuwa.wao.dto.ChatReviewRunDetailDto;
import cn.wayuwa.wao.dto.ChatReviewRunSummaryDto;
import cn.wayuwa.wao.dto.ChatReviewSpeakerDto;
import cn.wayuwa.wao.service.ChatReviewService;
import cn.wayuwa.wao.service.ChatReviewAutomationService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatReviewController.class)
@Import(GlobalExceptionHandler.class)
class ChatReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ChatReviewService service;
    @MockBean private ChatReviewAutomationService automation;
    @MockBean private CurrentMember currentMember;

    @BeforeEach
    void currentSessionMember() {
        when(currentMember.idOr(any(), eq("unknown"))).thenReturn("wd");
    }

    @Test
    void automationReturnsThePersistedPolicy() throws Exception {
        when(automation.config()).thenReturn(new ChatReviewAutoConfigDto(
                true, 3600, "MESSAGES", 40, "只看分歧", "aa-deepseek",
                OffsetDateTime.parse("2026-08-27T13:00:00+08:00"), "cr-auto",
                OffsetDateTime.parse("2026-08-27T12:00:00+08:00")));

        mockMvc.perform(get("/api/chat-reviews/automation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.aiCapabilityId").value("aa-deepseek"));
    }

    @Test
    void createReturns202ImmediatelyWithPersistedRunId() throws Exception {
        when(service.create(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), "交付", "wd"))
                .thenReturn("cr123");

        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-08-01","endDate":"2026-08-07","focus":"交付"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value("cr123"));
        verify(service).create(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), "交付", "wd");
    }

    @Test
    void createsSharedChatReviewWithStrictRangeInput() throws Exception {
        when(service.createShared("MESSAGES", 20, "结论", "wd", null)).thenReturn("cr-shared");

        mockMvc.perform(post("/api/chat-reviews/shared")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rangeKind":"MESSAGES","rangeValue":20,"focus":"结论"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value("cr-shared"));
        verify(service).createShared("MESSAGES", 20, "结论", "wd", null);

        mockMvc.perform(post("/api/chat-reviews/shared")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rangeKind":"HOURS","rangeValue":3,"unknown":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("额外字段")));
    }

    @Test
    void omittedFocusIsAcceptedButExplicitNullAndOtherTypesAreRejected() throws Exception {
        when(service.create(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), null, "wd"))
                .thenReturn("cr-no-focus");

        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-07\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value("cr-no-focus"));
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-07\",\"focus\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("focus 必须是字符串"));
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-07\",\"focus\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("focus 必须是字符串"));
        verify(service).create(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), null, "wd");
    }

    @Test
    void malformedJsonUsesStableBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002))
                .andExpect(jsonPath("$.message").value("请求体不是合法 JSON"));
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    void trailingJsonRootIsRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-08-01","endDate":"2026-08-07"}{"focus":"伪造"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002))
                .andExpect(jsonPath("$.message").value("请求体不是合法 JSON"));
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    void duplicateCreateFieldsAreRejectedBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-08-01","startDate":"2026-08-02",
                                 "endDate":"2026-08-07"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002))
                .andExpect(jsonPath("$.message").value("请求体不是合法 JSON"));
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-08-01","endDate":"2026-08-07",
                                 "focus":"交付","focus":"安全"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002))
                .andExpect(jsonPath("$.message").value("请求体不是合法 JSON"));
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    void createRejectsMissingAndAdditionalFieldsBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-08-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少字段")));
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-08-01","endDate":"2026-08-07","memberId":"wg"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("额外字段")));
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    void configBodyAllowsOnlyTwoTextualSpeakerIds() throws Exception {
        ChatReviewConfigDto configured = new ChatReviewConfigDto(true, true, "oc_chat", "研发群",
                List.of(new ChatReviewSpeakerDto("ou_ge", "娃哥"),
                        new ChatReviewSpeakerDto("ou_di", "娃弟")),
                List.of(new ChatReviewSpeakerDto("ou_ge", "娃哥"),
                        new ChatReviewSpeakerDto("ou_di", "娃弟")), "已配置");
        when(service.saveConfig(List.of("ou_ge", "ou_di"))).thenReturn(configured);

        mockMvc.perform(put("/api/chat-reviews/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speakerIds\":[\"ou_ge\",\"ou_di\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.selectedSpeakers[1].id").value("ou_di"));
        verify(service).saveConfig(List.of("ou_ge", "ou_di"));
    }

    @Test
    void configRejectsExtraFieldsAndNonTextualIds() throws Exception {
        mockMvc.perform(put("/api/chat-reviews/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speakerIds\":[\"ou_ge\",\"ou_di\"],\"chatId\":\"other\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("额外字段")));
        mockMvc.perform(put("/api/chat-reviews/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speakerIds\":[\"ou_ge\",42]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("发言人 id 必须是字符串"));
        verify(service, never()).saveConfig(any());
    }

    @Test
    void configRejectsSpeakerIdsThatCannotFitTheSnapshotColumn() throws Exception {
        String oversizedId = "x".repeat(129);

        mockMvc.perform(put("/api/chat-reviews/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speakerIds\":[\"" + oversizedId + "\",\"ou_di\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("发言人 id 最多 128 个字符"));
        verify(service, never()).saveConfig(any());
    }

    @Test
    void readsSharedConfigActiveHistoryAndDetail() throws Exception {
        LocalDate day = LocalDate.of(2026, 8, 18);
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 19, 9, 0, 0, 0, ZoneOffset.ofHours(8));
        ChatReviewSpeakerDto first = new ChatReviewSpeakerDto("ou_ge", "娃哥");
        ChatReviewSpeakerDto second = new ChatReviewSpeakerDto("ou_di", "娃弟");
        ChatReviewConfigDto config = new ChatReviewConfigDto(true, true, "oc_chat", "研发群",
                List.of(first, second), List.of(first, second), "已配置");
        ChatReviewRunSummaryDto summary = new ChatReviewRunSummaryDto(
                "cr1", "FEISHU_ARCHIVE", null, null, day, day, null, "FAILED", 0, 1,
                null, "SYNCING", day, "飞书超时", 0, "wg", "娃哥", null, null, now, now, now);
        ChatReviewRunDetailDto detail = new ChatReviewRunDetailDto(
                "cr1", "FEISHU_ARCHIVE", null, null, day, day, null, "FAILED", 0, 1,
                null, "SYNCING", day, "飞书超时", 0, "wg", "娃哥", null, null, now, now, now,
                "oc_chat", "研发群", List.of(first, second), null, List.of(), null);
        when(service.config()).thenReturn(config);
        when(service.list()).thenReturn(new ChatReviewListDto(summary, List.of(summary)));
        when(service.detail("cr1")).thenReturn(detail);

        mockMvc.perform(get("/api/chat-reviews/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedSpeakers[0].id").value("ou_ge"));
        mockMvc.perform(get("/api/chat-reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeRun.id").value("cr1"))
                .andExpect(jsonPath("$.data.history[0].createdById").value("wg"));
        mockMvc.perform(get("/api/chat-reviews/cr1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedStage").value("SYNCING"))
                .andExpect(jsonPath("$.data.failedDate").value("2026-08-18"));
    }

    @Test
    void retryReturns202WithANewRunIdForCurrentSessionMember() throws Exception {
        when(service.retry("cr-failed", "wd")).thenReturn("cr-retry");

        mockMvc.perform(post("/api/chat-reviews/cr-failed/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value("cr-retry"));
        verify(service).retry("cr-failed", "wd");
    }

    @Test
    void retryAndRerunRejectAnyRequestBody() throws Exception {
        mockMvc.perform(post("/api/chat-reviews/cr-failed/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该接口不接受请求体"));
        mockMvc.perform(post("/api/chat-reviews/cr-succeeded/rerun")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该接口不接受请求体"));
        mockMvc.perform(post("/api/chat-reviews/cr-failed/retry")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(" \t\r\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该接口不接受请求体"));
        mockMvc.perform(post("/api/chat-reviews/cr-succeeded/rerun")
                        .contentType("application/x-wao-unsupported")
                        .content("anything"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该接口不接受请求体"));
        verify(service, never()).retry(any(), any());
        verify(service, never()).rerun(any(), any());
    }

    @Test
    void retryRejectsWhitespaceWhenContentLengthIsUnknown() {
        ChatReviewController controller = new ChatReviewController(service, currentMember);
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(" \t\r\n".getBytes(StandardCharsets.UTF_8));
        request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> controller.retry("cr-failed", request))
                .isInstanceOf(cn.wayuwa.wao.common.BadRequestException.class)
                .hasMessage("该接口不接受请求体");
        verify(service, never()).retry(any(), any());
    }

    @Test
    void unsupportedContentTypeOnJsonEndpointUses415EnvelopeInsteadOf500() throws Exception {
        mockMvc.perform(post("/api/chat-reviews")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(41500))
                .andExpect(jsonPath("$.message").value("不支持该请求 Content-Type"));
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    void rerunReturns202WithANewSnapshotRunIdForCurrentSessionMember() throws Exception {
        when(service.rerun("cr-succeeded", "wd")).thenReturn("cr-rerun");

        mockMvc.perform(post("/api/chat-reviews/cr-succeeded/rerun"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value("cr-rerun"));
        verify(service).rerun("cr-succeeded", "wd");
    }
}
