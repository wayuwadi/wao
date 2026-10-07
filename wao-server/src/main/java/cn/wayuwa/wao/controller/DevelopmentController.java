package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.DevelopmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 当前成员的自主建设工作台；执行器不通过公开领取接口扩权。 */
@RestController
@RequestMapping("/api/development")
public class DevelopmentController {
    public record Create(String featureId) {}
    public record Retry(long version) {}
    public record Reply(long revision, String action, String reply) {}
    private final CurrentMember member;
    private final DevelopmentService service;
    public DevelopmentController(CurrentMember member,DevelopmentService service){this.member=member;this.service=service;}
    @GetMapping({"", "/overview"}) public ApiResponse<DevelopmentService.Overview> overview(HttpServletRequest req){return ApiResponse.ok(service.overview(member.id(req)));}
    @PutMapping("/policy") public ApiResponse<DevelopmentService.Policy> policy(@RequestBody DevelopmentService.Policy body,HttpServletRequest req){return ApiResponse.ok(service.savePolicy(member.id(req),body));}
    @PostMapping("/runs") public ApiResponse<DevelopmentService.Run> create(@RequestBody Create body,HttpServletRequest req){return ApiResponse.ok(service.create(member.id(req),body.featureId()));}
    @GetMapping("/runs/{id}") public ApiResponse<DevelopmentService.Run> get(@PathVariable String id,HttpServletRequest req){return ApiResponse.ok(service.get(member.id(req),id));}
    @PostMapping("/runs/{id}/retry") public ApiResponse<DevelopmentService.Run> retry(@PathVariable String id,@RequestBody Retry body,HttpServletRequest req){return ApiResponse.ok(service.retry(member.id(req),id,body.version()));}
    @PostMapping("/decisions/{id}") public ApiResponse<DevelopmentService.Run> decide(@PathVariable String id,@RequestBody Reply body,HttpServletRequest req){return ApiResponse.ok(service.decide(member.id(req),id,body.revision(),body.action(),body.reply()));}
}
