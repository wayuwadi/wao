package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.NoteAiCompanionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 浏览器私人伴读接口，不自动扩展旧 MCP 令牌权限。 */
@RestController
@RequestMapping("/api/docs/notes")
public class NoteAiCompanionController {
    private final CurrentMember member;private final NoteAiCompanionService companion;
    public NoteAiCompanionController(CurrentMember member,NoteAiCompanionService companion){this.member=member;this.companion=companion;}
    public record Preference(String mode,long expectedVersion){}
    public record Action(Integer expectedRev){}
    @GetMapping("/ai-companion") public ApiResponse<Map<String,Object>> overview(HttpServletRequest http){return ApiResponse.ok(companion.overview(member.id(http)));}
    @PutMapping("/ai-companion/preference") public ApiResponse<Map<String,Object>> preference(@RequestBody Preference input,HttpServletRequest http){return ApiResponse.ok(companion.preference(member.id(http),input.mode(),input.expectedVersion()));}
    @GetMapping("/ai-receipts") public ApiResponse<List<Map<String,Object>>> receipts(HttpServletRequest http){return ApiResponse.ok(companion.receipts(member.id(http)));}
    @PostMapping("/ai-transfer") public ApiResponse<?> transfer(@RequestBody NoteAiCompanionService.TransferInput input,HttpServletRequest http){return ApiResponse.ok(companion.transfer(member.id(http),input));}
    @GetMapping("/{docId}/ai-results") public ApiResponse<Map<String,Object>> results(@PathVariable String docId,@RequestParam(required=false) String cursor,HttpServletRequest http){return ApiResponse.ok(companion.results(member.id(http),docId,cursor));}
    @PostMapping("/ai-artifacts/{id}/{action}") public ApiResponse<?> action(@PathVariable String id,@PathVariable String action,@RequestBody Action input,HttpServletRequest http){return ApiResponse.ok(companion.action(member.id(http),id,action,input.expectedRev()));}
    @PostMapping("/{docId}/ai-cancel") public ApiResponse<?> cancel(@PathVariable String docId,@RequestBody Action input,HttpServletRequest http){companion.cancel(member.id(http),docId,input.expectedRev());return ApiResponse.ok(Map.of("requested",true));}
    @GetMapping("/{docId}/ai-conversation") public ApiResponse<Map<String,Object>> conversation(@PathVariable String docId,HttpServletRequest http){return ApiResponse.ok(companion.conversation(member.id(http),docId));}
    @PostMapping("/{docId}/ai-conversation") public ApiResponse<Map<String,Object>> chat(@PathVariable String docId,@RequestBody NoteAiCompanionService.ChatInput input,HttpServletRequest http){return ApiResponse.ok(companion.chat(member.id(http),docId,input));}
}
