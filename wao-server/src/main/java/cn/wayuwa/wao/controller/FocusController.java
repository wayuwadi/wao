package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.FocusService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/focus")
public class FocusController {
    private final FocusService focus;
    private final CurrentMember current;
    public FocusController(FocusService focus,CurrentMember current){this.focus=focus;this.current=current;}
    @GetMapping public ApiResponse<FocusService.State> state(HttpServletRequest http){return ApiResponse.ok(focus.state(current.id(http)));}
    @PutMapping("/{id}") public ApiResponse<FocusService.Session> start(@PathVariable String id,@RequestBody FocusService.Start request,HttpServletRequest http){return ApiResponse.ok(focus.start(id,request,current.id(http)));}
    @PostMapping("/{id}") public ApiResponse<FocusService.Session> change(@PathVariable String id,@RequestBody FocusService.Change request,HttpServletRequest http){return ApiResponse.ok(focus.change(id,request,current.id(http)));}
}
