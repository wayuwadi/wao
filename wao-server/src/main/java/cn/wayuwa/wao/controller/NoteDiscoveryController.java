package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.service.KnowledgeService;
import cn.wayuwa.wao.service.NoteDiscoveryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** 浏览器中的成员私有能力，不向旧工作区令牌增加权限。 */
@RestController
@RequestMapping("/api/note-discoveries")
public class NoteDiscoveryController {
    private final NoteDiscoveryService service;
    private final KnowledgeService knowledge;
    private final CurrentMember member;
    public NoteDiscoveryController(NoteDiscoveryService service, KnowledgeService knowledge, CurrentMember member) {
        this.service=service; this.knowledge=knowledge; this.member=member;
    }
    private String owner(HttpServletRequest request) {
        String id=member.id(request); if(id==null)throw new UnauthorizedException("请先登录"); return id;
    }
    @GetMapping("/current")
    public ApiResponse<Map<String,Object>> current(HttpServletRequest request) { return ApiResponse.ok(service.current(owner(request))); }
    @GetMapping("/saved")
    public ApiResponse<Map<String,Object>> saved(@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        return ApiResponse.ok(service.page(owner(request),true,cursor,limit));
    }
    @GetMapping("/legacy")
    public ApiResponse<Map<String,Object>> legacy(@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        return ApiResponse.ok(service.page(owner(request),false,cursor,limit));
    }
    @GetMapping("/preference")
    public ApiResponse<Map<String,Object>> preference(HttpServletRequest request) { return ApiResponse.ok(service.preference(owner(request))); }
    @PutMapping("/preference")
    public ApiResponse<Map<String,Object>> preference(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String owner=owner(request);
        if(body.size()!=2||!Set.of("enabled","expectedVersion").containsAll(body.keySet())||!(body.get("enabled") instanceof Boolean enabled)||!(body.get("expectedVersion") instanceof Number version)||version.longValue()<0||version.doubleValue()!=version.longValue())throw new BadRequestException("设置参数无效");
        return ApiResponse.ok(service.savePreference(owner,enabled,version.longValue()));
    }
    @GetMapping("/items/{id}")
    public ApiResponse<Map<String,Object>> item(@PathVariable String id,HttpServletRequest request) { return ApiResponse.ok(service.item(owner(request),id)); }
    @PatchMapping("/items/{id}/feedback")
    public ApiResponse<Map<String,Object>> feedback(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String owner=owner(request);
        if(!Set.of("expectedVersion","saved","dismissed","correction").containsAll(body.keySet())
                || !(body.get("expectedVersion") instanceof Number version) || version.longValue()<0
                || version.doubleValue()!=version.longValue() || body.size()<2)throw new BadRequestException("反馈参数无效");
        return ApiResponse.ok(service.feedback(owner,id,version.longValue(),body));
    }
    @GetMapping("/items/{id}/sources/{docId}")
    public ApiResponse<DocDto> source(@PathVariable String id,@PathVariable String docId,HttpServletRequest request) {
        service.requireSource(owner(request),id,docId); return ApiResponse.ok(knowledge.getDoc(docId));
    }
}
