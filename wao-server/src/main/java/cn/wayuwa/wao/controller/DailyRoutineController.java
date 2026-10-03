package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.service.DailyRoutineService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Daily routines inside the existing browser Todo workspace. */
@RestController
@RequestMapping("/api/todo/routines")
public class DailyRoutineController {
    private final DailyRoutineService routines;
    private final CurrentMember currentMember;

    public DailyRoutineController(DailyRoutineService routines, CurrentMember currentMember) {
        this.routines = routines;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<DailyRoutineService.Range> range(@RequestParam LocalDate from,
            @RequestParam LocalDate to, HttpServletRequest http) {
        return ApiResponse.ok(routines.range(from, to, member(http)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DailyRoutineService.Template> get(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(routines.get(id, member(http)));
    }

    @PutMapping("/{id}/operations/{operationId}")
    public ApiResponse<DailyRoutineService.RuleResult> putRule(@PathVariable String id,
            @PathVariable String operationId, @RequestBody DailyRoutineService.RuleRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(routines.putRule(id, operationId, request,
                currentMember.accountId(http), member(http)));
    }

    @PutMapping("/{id}/occurrences/{date}/operations/{operationId}")
    public ApiResponse<DailyRoutineService.OccurrenceResult> putOccurrence(@PathVariable String id,
            @PathVariable LocalDate date, @PathVariable String operationId,
            @RequestBody DailyRoutineService.StatusRequest request, HttpServletRequest http) {
        return ApiResponse.ok(routines.putOccurrence(id, date, operationId, request,
                currentMember.accountId(http), member(http)));
    }

    private String member(HttpServletRequest http) {
        String id = currentMember.id(http);
        if (id == null || id.isBlank()) throw new NotFoundException("请先登录");
        return id;
    }
}
