package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AttendanceConfigRequest;
import cn.wayuwa.wao.dto.AttendanceDayRequest;
import cn.wayuwa.wao.dto.AttendanceDto;
import cn.wayuwa.wao.service.AttendanceService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/** 当前成员自己的考勤 API。 */
@RestController @RequestMapping("/api/attendance") public class AttendanceController {
    private static final String FALLBACK_OWNER="wg"; private final AttendanceService attendance; private final CurrentMember current;
    public AttendanceController(AttendanceService attendance,CurrentMember current){this.attendance=attendance;this.current=current;}
    @GetMapping public ApiResponse<AttendanceDto> list(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,HttpServletRequest http){return ApiResponse.ok(attendance.attendance(member(http),from,to));}
    @PutMapping("/config") public ApiResponse<AttendanceDto.Config> config(@RequestBody AttendanceConfigRequest request,HttpServletRequest http){return ApiResponse.ok(attendance.saveConfig(member(http),request));}
    @PostMapping("/sync") public ApiResponse<AttendanceDto> sync(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,HttpServletRequest http){return ApiResponse.ok(attendance.sync(member(http),from,to));}
    @PutMapping("/days/{date}") public ApiResponse<AttendanceDto.Day> day(@PathVariable @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,@RequestBody AttendanceDayRequest request,HttpServletRequest http){return ApiResponse.ok(attendance.saveManual(member(http),date,request));}
    @DeleteMapping("/days/{date}/manual-override") public ApiResponse<AttendanceDto.Day> clear(@PathVariable @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,HttpServletRequest http){return ApiResponse.ok(attendance.clearManual(member(http),date));}
    private String member(HttpServletRequest http){return current.idOr(http,FALLBACK_OWNER);}
}
