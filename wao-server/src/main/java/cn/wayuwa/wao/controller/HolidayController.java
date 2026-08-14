package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.HolidayCalendarDto;
import cn.wayuwa.wao.dto.HolidayReminderDto;
import cn.wayuwa.wao.dto.HolidaySettingsDto;
import cn.wayuwa.wao.dto.SaveHolidayReminderRequest;
import cn.wayuwa.wao.dto.SaveHolidaySettingsRequest;
import cn.wayuwa.wao.service.HolidayCalendarService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员的节日展示范围、来源与个人提醒接口。 */
@RestController
@RequestMapping("/api/calendar/holidays")
public class HolidayController {
    private static final String FALLBACK_MEMBER = "wg";
    private final HolidayCalendarService service;
    private final CurrentMember currentMember;

    public HolidayController(HolidayCalendarService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 返回日期窗内按当前成员选择过滤后的事件和年度来源状态。 */
    @GetMapping
    public ApiResponse<HolidayCalendarDto> events(@RequestParam String from, @RequestParam String to,
            HttpServletRequest http) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(from);
            end = LocalDate.parse(to);
        } catch (Exception exception) {
            throw new BadRequestException("日期格式应为 yyyy-MM-dd");
        }
        if (end.isBefore(start)) throw new BadRequestException("to 不能早于 from");
        if (end.isAfter(start.plusDays(370))) throw new BadRequestException("查询区间过长");
        return ApiResponse.ok(service.calendar(member(http), start, end));
    }

    /** 读取当前成员主动选择的国际节日类别和提醒通道状态。 */
    @GetMapping("/settings")
    public ApiResponse<HolidaySettingsDto> settings(HttpServletRequest http) {
        return ApiResponse.ok(service.settings(member(http)));
    }

    /** 保存国际节日选择；没有选择时只保留中国节日。 */
    @PutMapping("/settings")
    public ApiResponse<HolidaySettingsDto> saveSettings(
            @Valid @RequestBody SaveHolidaySettingsRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.saveSettings(member(http), request));
    }

    /** 创建、修改或关闭一个节日的年度个人提醒。 */
    @PutMapping("/{holidayCode}/reminder")
    public ApiResponse<HolidayReminderDto> saveReminder(@PathVariable String holidayCode,
            @RequestBody SaveHolidayReminderRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.saveReminder(member(http), holidayCode, request));
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
