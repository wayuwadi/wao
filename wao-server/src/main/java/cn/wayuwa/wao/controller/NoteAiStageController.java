package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
/** 已保存小记的有版本单项手动整理。 */
@RestController
@RequestMapping("/api/docs/notes/{docId}/ai-stages")
public class NoteAiStageController {
 private final CurrentMember member; private final NoteAiManagedService managed;
 public NoteAiStageController(CurrentMember member,NoteAiManagedService managed){this.member=member;this.managed=managed;}
 public record Start(Integer expectedRev,String requestId) { }
 @PostMapping("/{stage}") public ApiResponse<NoteAiWorkflowService.Status> start(@PathVariable String docId,@PathVariable String stage,@RequestBody Start request,HttpServletRequest http){return ApiResponse.ok(managed.manual(member.id(http),docId,stage,request.expectedRev(),request.requestId()));}
}
