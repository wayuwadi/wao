package cn.wayuwa.wao.controller;

import java.time.LocalDate;
import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.HolidayEventDto;
import cn.wayuwa.wao.service.HolidayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 无需用户配置的内置节假日日历接口。 */
@RestController
@RequestMapping("/api/calendar/holidays")
public class HolidayController {

    private final HolidayService holidayService;

    public HolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    @GetMapping
    public ApiResponse<List<HolidayEventDto>> events(@RequestParam String from, @RequestParam String to) {
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
        return ApiResponse.ok(holidayService.events(start, end));
    }
}
