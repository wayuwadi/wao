package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.RoutineService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/routines")
public class RoutineController {
    private final RoutineService routines;private final CurrentMember current;
    public RoutineController(RoutineService routines,CurrentMember current){this.routines=routines;this.current=current;}
    @GetMapping public ApiResponse<RoutineService.State> state(@RequestParam(required=false) String month,HttpServletRequest http){return ApiResponse.ok(routines.state(current.id(http),month));}
    @PutMapping("/{id}") public ApiResponse<RoutineService.Item> save(@PathVariable String id,@RequestBody RoutineService.Item body,HttpServletRequest http){return ApiResponse.ok(routines.save(current.id(http),id,body));}
    @PutMapping("/{id}/checkins/{date}") public ApiResponse<RoutineService.Log> checkin(@PathVariable String id,@PathVariable String date,@RequestBody RoutineService.Checkin body,HttpServletRequest http){return ApiResponse.ok(routines.checkin(current.id(http),id,date,body));}
}
