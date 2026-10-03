package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.service.TodoDayService;
import cn.wayuwa.wao.service.TodoOperationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The private daily workspace inside the existing browser Todo feature. */
@RestController
public class TodoController {
    private final TodoDayService days;
    private final TodoOperationService operations;
    private final CurrentMember currentMember;
    private final ObjectMapper json;

    public TodoController(TodoDayService days, TodoOperationService operations,
            CurrentMember currentMember, ObjectMapper json) {
        this.days = days;
        this.operations = operations;
        this.currentMember = currentMember;
        this.json = json;
    }

    @GetMapping("/api/todo/days/{date}")
    public ApiResponse<TodoDayService.Day> day(@PathVariable LocalDate date, HttpServletRequest http) {
        return ApiResponse.ok(days.day(date, member(http)));
    }

    @GetMapping("/api/todo/days")
    public ApiResponse<TodoDayService.Month> month(@RequestParam LocalDate from,
            @RequestParam LocalDate to, HttpServletRequest http) {
        return ApiResponse.ok(days.month(from, to, member(http)));
    }

    /** Requires every schedule key so JSON omission cannot accidentally clear an existing value. */
    @PutMapping("/api/tasks/{id}/schedule-operations/{operationId}")
    public ApiResponse<TodoOperationService.ScheduleResult> schedule(@PathVariable String id,
            @PathVariable String operationId, @RequestBody JsonNode body, HttpServletRequest http) {
        for (String key : new String[]{"expectedEditVersion", "start", "startAt", "endAt", "schedulePeriod"}) {
            if (!body.has(key)) throw new BadRequestException("排时请求缺少 " + key);
        }
        TodoOperationService.ScheduleRequest request = json.convertValue(body, TodoOperationService.ScheduleRequest.class);
        return ApiResponse.ok(operations.schedule(id, operationId, request,
                currentMember.accountId(http), member(http)));
    }

    @PutMapping("/api/todo/note-operations/{operationId}")
    public ApiResponse<TodoOperationService.NoteResult> note(@PathVariable String operationId,
            @RequestBody TodoOperationService.NoteRequest request, HttpServletRequest http) {
        return ApiResponse.ok(operations.note(operationId, request, currentMember.accountId(http), member(http)));
    }

    @GetMapping("/api/todo/operations/{operationId}")
    public ApiResponse<TodoOperationService.Receipt> operation(@PathVariable String operationId,
            HttpServletRequest http) {
        return ApiResponse.ok(operations.receipt(operationId, currentMember.accountId(http), member(http)));
    }

    private String member(HttpServletRequest http) {
        String member = currentMember.id(http);
        if (member == null || member.isBlank()) throw new cn.wayuwa.wao.common.NotFoundException("请先登录");
        return member;
    }
}
