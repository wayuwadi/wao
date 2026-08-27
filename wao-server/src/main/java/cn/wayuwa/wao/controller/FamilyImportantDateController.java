package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.FamilyImportantDateCalendarDto;
import cn.wayuwa.wao.dto.CalendarDateConversionDto;
import cn.wayuwa.wao.dto.FamilyImportantDateDto;
import cn.wayuwa.wao.dto.SaveFamilyImportantDateRequest;
import cn.wayuwa.wao.service.FamilyImportantDateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员维护日历提醒事项、转换阴阳历并获取年度事件的接口。 */
@RestController
@RequestMapping("/api/calendar/important-dates")
public class FamilyImportantDateController {
    private static final String FALLBACK_MEMBER = "wg";
    private final FamilyImportantDateService service;
    private final CurrentMember currentMember;

    public FamilyImportantDateController(FamilyImportantDateService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 返回维护列表、日期窗事件和飞书提醒通道状态。 */
    @GetMapping
    public ApiResponse<FamilyImportantDateCalendarDto> calendar(@RequestParam String from,
            @RequestParam String to, HttpServletRequest http) {
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

    /** 公历或农历输入发生变化时，返回两种历法的同一天。 */
    @GetMapping("/date-conversion")
    public ApiResponse<CalendarDateConversionDto> convertDate(
            @RequestParam(defaultValue = "SOLAR") String calendarType,
            @RequestParam(required = false) String solarDate,
            @RequestParam(required = false) Integer lunarYear,
            @RequestParam(required = false) Integer lunarMonth,
            @RequestParam(required = false) Integer lunarDay,
            @RequestParam(defaultValue = "false") boolean lunarLeapMonth) {
        if ("LUNAR".equalsIgnoreCase(calendarType)) {
            if (lunarYear == null || lunarMonth == null || lunarDay == null) {
                throw new BadRequestException("农历日期需要填写年、月、日");
            }
            return ApiResponse.ok(service.fromLunar(lunarYear, lunarMonth, lunarDay, lunarLeapMonth));
        }
        try {
            return ApiResponse.ok(service.fromSolar(LocalDate.parse(solarDate)));
        } catch (Exception exception) {
            throw new BadRequestException("公历日期格式应为 yyyy-MM-dd");
        }
    }

    /** 新建一个每年显示的日历提醒事项。 */
    @PostMapping
    public ApiResponse<FamilyImportantDateDto> create(@Valid @RequestBody SaveFamilyImportantDateRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(service.create(member(http), request));
    }

    /** 修改当前成员拥有的日历提醒事项。 */
    @PutMapping("/{id}")
    public ApiResponse<FamilyImportantDateDto> update(@PathVariable String id,
            @Valid @RequestBody SaveFamilyImportantDateRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.update(member(http), id, request));
    }

    /** 删除当前成员拥有的日历提醒事项。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        service.delete(member(http), id);
        return ApiResponse.ok(null);
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
