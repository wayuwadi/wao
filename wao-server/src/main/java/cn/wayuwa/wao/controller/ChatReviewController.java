package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.ChatReviewConfigDto;
import cn.wayuwa.wao.dto.ChatReviewCreatedDto;
import cn.wayuwa.wao.dto.ChatReviewListDto;
import cn.wayuwa.wao.dto.ChatReviewRunDetailDto;
import cn.wayuwa.wao.service.ChatReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 共享“娃与娃聊天复盘”配置、异步运行、历史与重试接口。 */
@RestController
@RequestMapping("/api/chat-reviews")
public class ChatReviewController {

    private static final int MAX_SPEAKER_ID_CHARS = 128;

    private final ChatReviewService service;
    private final CurrentMember currentMember;

    public ChatReviewController(ChatReviewService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 读取唯一群聊与两位固定发言人配置。 */
    @GetMapping("/config")
    public ApiResponse<ChatReviewConfigDto> config() {
        return ApiResponse.ok(service.config());
    }

    /** 严格保存两个发言人 id；群聊始终来自现有唯一同步配置。 */
    @PutMapping("/config")
    public ApiResponse<ChatReviewConfigDto> saveConfig(@RequestBody JsonNode body) {
        requireObject(body, Set.of("speakerIds"), Set.of("speakerIds"));
        JsonNode ids = body.get("speakerIds");
        if (ids == null || !ids.isArray() || ids.size() != 2) {
            throw new BadRequestException("speakerIds 必须是包含两个发言人 id 的数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode id : ids) {
            if (!id.isTextual()) throw new BadRequestException("发言人 id 必须是字符串");
            String value = id.asText();
            String normalized = value.trim();
            if (normalized.codePointCount(0, normalized.length()) > MAX_SPEAKER_ID_CHARS) {
                throw new BadRequestException("发言人 id 最多 128 个字符");
            }
            values.add(value);
        }
        return ApiResponse.ok(service.saveConfig(values));
    }

    /** 严格校验参数后入队，不等待飞书或 Codex 完成。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ChatReviewCreatedDto> create(@RequestBody JsonNode body, HttpServletRequest http) {
        requireObject(body, Set.of("startDate", "endDate", "focus"), Set.of("startDate", "endDate"));
        LocalDate start = date(body.get("startDate"), "startDate");
        LocalDate end = date(body.get("endDate"), "endDate");
        String focus = optionalText(body.get("focus"), "focus");
        String runId = service.create(start, end, focus, currentMember.idOr(http, "unknown"));
        return ApiResponse.ok(new ChatReviewCreatedDto(runId));
    }

    /** 返回当前活动运行和双方共享的最近终态历史。 */
    @GetMapping
    public ApiResponse<ChatReviewListDto> list() {
        return ApiResponse.ok(service.list());
    }

    /** 返回持久化阶段、进度、报告和不含正文的来源。 */
    @GetMapping("/{id}")
    public ApiResponse<ChatReviewRunDetailDto> detail(@PathVariable String id) {
        return ApiResponse.ok(service.detail(id));
    }

    /** 从失败运行的原始参数新建一次全范围重试，旧记录保留。 */
    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ChatReviewCreatedDto> retry(@PathVariable String id, HttpServletRequest http) {
        requireNoBody(http);
        return ApiResponse.ok(new ChatReviewCreatedDto(
                service.retry(id, currentMember.idOr(http, "unknown"))));
    }

    /** 从成功报告复制群、两人、日期和重点快照，旧报告保留。 */
    @PostMapping("/{id}/rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ChatReviewCreatedDto> rerun(@PathVariable String id, HttpServletRequest http) {
        requireNoBody(http);
        return ApiResponse.ok(new ChatReviewCreatedDto(
                service.rerun(id, currentMember.idOr(http, "unknown"))));
    }

    private static void requireNoBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0) {
            throw new BadRequestException("该接口不接受请求体");
        }
        try {
            // Transfer-Encoding: chunked 时 Content-Length 是 -1，必须真正读取流才能
            // 区分纯空请求与任何非空字节（包括空白字符）。
            if (request.getInputStream().read() != -1) {
                throw new BadRequestException("该接口不接受请求体");
            }
        } catch (IOException exception) {
            throw new BadRequestException("请求体无法读取");
        }
    }

    private static void requireObject(JsonNode body, Set<String> allowed, Set<String> required) {
        if (body == null || !body.isObject()) throw new BadRequestException("请求体必须是 JSON 对象");
        Set<String> fields = new LinkedHashSet<>();
        Iterator<String> names = body.fieldNames();
        names.forEachRemaining(fields::add);
        Set<String> extra = new LinkedHashSet<>(fields);
        extra.removeAll(allowed);
        if (!extra.isEmpty()) throw new BadRequestException("请求体包含额外字段：" + String.join("、", extra));
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(fields);
        if (!missing.isEmpty()) throw new BadRequestException("请求体缺少字段：" + String.join("、", missing));
    }

    private static LocalDate date(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new BadRequestException(field + " 必须是 YYYY-MM-DD 日期");
        }
        try {
            return LocalDate.parse(value.asText());
        } catch (DateTimeParseException exception) {
            throw new BadRequestException(field + " 必须是有效的 YYYY-MM-DD 日期");
        }
    }

    private static String optionalText(JsonNode value, String field) {
        if (value == null) return null;
        if (!value.isTextual()) throw new BadRequestException(field + " 必须是字符串");
        return value.asText();
    }
}
