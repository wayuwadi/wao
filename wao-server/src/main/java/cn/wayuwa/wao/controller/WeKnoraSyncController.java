package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 管理员个人拥有的同步配置；密钥不会返回前端或进入日志。 */
@RestController
@RequestMapping("/api/weknora-sync")
public class WeKnoraSyncController {
    private final WeKnoraSyncService sync;
    private final CurrentMember current;
    private final NotificationSettingsService permissions;
    public WeKnoraSyncController(WeKnoraSyncService sync,CurrentMember current,NotificationSettingsService permissions){this.sync=sync;this.current=current;this.permissions=permissions;}
    private String owner(HttpServletRequest request){String id=current.id(request);if(id==null || !permissions.canManageSystem(id))throw new UnauthorizedException("只有管理员可以维护知识同步");return id;}
    @GetMapping public ApiResponse<Map<String,Object>> get(HttpServletRequest request){return ApiResponse.ok(sync.overview(owner(request)));}
    @GetMapping("/progress") public ApiResponse<Map<String,Object>> progress(HttpServletRequest request){return ApiResponse.ok(sync.progress(owner(request)));}
    @GetMapping("/source") public ApiResponse<WeKnoraSourceService.Source> source(@RequestParam String key,HttpServletRequest request){return ApiResponse.ok(sync.source(owner(request),key));}
    @PutMapping public ApiResponse<Void> save(@RequestBody WeKnoraSyncService.Settings settings,HttpServletRequest request){sync.save(owner(request),settings);return ApiResponse.ok(null);}
    @PostMapping("/run") public ApiResponse<Map<String,String>> run(HttpServletRequest request){return ApiResponse.ok(Map.of("id",sync.enqueue("manual",owner(request))));}
}
