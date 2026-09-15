package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 共享同步中心：所有已登录成员可查看并触发，只有管理员可改连接设置；密钥不会返回前端或进入日志。 */
@RestController
@RequestMapping("/api/weknora-sync")
public class WeKnoraSyncController {
    private final WeKnoraSyncService sync;
    private final CurrentMember current;
    private final NotificationSettingsService permissions;
    public WeKnoraSyncController(WeKnoraSyncService sync,CurrentMember current,NotificationSettingsService permissions){this.sync=sync;this.current=current;this.permissions=permissions;}
    private String member(HttpServletRequest request){String id=current.id(request);if(id==null)throw new UnauthorizedException("请先登录后使用知识同步");return id;}
    private String manager(HttpServletRequest request){String id=member(request);if(!permissions.canManageSystem(id))throw new UnauthorizedException("只有管理员可以修改知识同步设置");return id;}
    @GetMapping public ApiResponse<Map<String,Object>> get(HttpServletRequest request){return ApiResponse.ok(sync.overview(member(request)));}
    @GetMapping("/progress") public ApiResponse<Map<String,Object>> progress(HttpServletRequest request){return ApiResponse.ok(sync.progress(member(request)));}
    @GetMapping("/source") public ApiResponse<WeKnoraSourceService.Source> source(@RequestParam String key,HttpServletRequest request){return ApiResponse.ok(sync.source(member(request),key));}
    @PutMapping public ApiResponse<Void> save(@RequestBody WeKnoraSyncService.Settings settings,HttpServletRequest request){sync.save(manager(request),settings);return ApiResponse.ok(null);}
    @PostMapping("/run") public ApiResponse<Map<String,String>> run(HttpServletRequest request){return ApiResponse.ok(Map.of("id",sync.enqueue("manual",member(request))));}
}
