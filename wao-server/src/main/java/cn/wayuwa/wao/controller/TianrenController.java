package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.TianrenInterpretRequest;
import cn.wayuwa.wao.dto.TianrenReadingDto;
import cn.wayuwa.wao.dto.TianrenShareTokenDto;
import cn.wayuwa.wao.service.AiAssistantService;
import cn.wayuwa.wao.service.TianrenReadingService;
import cn.wayuwa.wao.service.WorkdayRhythmDashboardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** “天人一卦”每日卦象、历史与专用 AI 解读接口。 */
@RestController
@RequestMapping("/api/tianren/readings")
public class TianrenController {

    private static final String FALLBACK_MEMBER = "wg";

    private final TianrenReadingService readings;
    private final AiAssistantService assistant;
    private final CurrentMember currentMember;
    private final WorkdayRhythmDashboardService rhythm;

    public TianrenController(TianrenReadingService readings, AiAssistantService assistant,
            CurrentMember currentMember, WorkdayRhythmDashboardService rhythm) {
        this.readings = readings;
        this.assistant = assistant;
        this.currentMember = currentMember;
        this.rhythm = rhythm;
    }

    /** 读取今天已经起出的卦；未起卦时 data 为 null。 */
    @GetMapping("/today")
    public ApiResponse<TianrenReadingDto> today(HttpServletRequest http) {
        return ApiResponse.ok(readings.today(member(http)));
    }

    /** 主动起出今天唯一的一卦；重复调用仍返回同一卦。 */
    @PostMapping("/today")
    public ApiResponse<TianrenReadingDto> createToday(HttpServletRequest http) {
        String memberId = member(http);
        TianrenReadingDto reading = readings.createToday(memberId);
        rhythm.completeSystemItem(memberId, LocalDate.now(ZoneId.of("Asia/Shanghai")), "TIANREN");
        return ApiResponse.ok(reading);
    }

    /** 按日期倒序返回当前成员最近的每日卦象。 */
    @GetMapping
    public ApiResponse<List<TianrenReadingDto>> history(
            @RequestParam(defaultValue = "14") int limit, HttpServletRequest http) {
        return ApiResponse.ok(readings.history(member(http), limit));
    }

    /** 为当前成员拥有的卦象生成或返回同一个公开分享凭据。 */
    @PostMapping("/{id}/share")
    public ApiResponse<TianrenShareTokenDto> share(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(new TianrenShareTokenDto(readings.createShareToken(member(http), id)));
    }

    /** 用既有本地 Codex 队列解读服务端保存的今日卦象。 */
    @PostMapping(value = "/today/interpret/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter interpret(@Valid @RequestBody TianrenInterpretRequest request, HttpServletRequest http) {
        String memberId = member(http);
        TianrenReadingService.PreparedInterpretation prepared =
                readings.prepareInterpretation(memberId, request.focus());
        SseEmitter emitter = new SseEmitter(310_000L);
        Thread worker = new Thread(
                () -> assistant.chatStreamForTianren(
                        prepared.request(), memberId, prepared.trustedContext(), emitter),
                "wao-tianren-stream");
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
