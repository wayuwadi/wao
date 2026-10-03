package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.NoteOriginalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
/** 当前文档只读原文与私人保存前输入；通用 MCP 令牌不开放这些接口。 */
@RestController
@RequestMapping("/api/docs/notes")
public class NoteOriginalController {
 private final NoteOriginalService sources;private final CurrentMember member;
 public NoteOriginalController(NoteOriginalService sources,CurrentMember member){this.sources=sources;this.member=member;}
 @GetMapping("/{id}/originals") public ApiResponse<NoteOriginalService.Originals> originals(@PathVariable String id,HttpServletRequest http){return ApiResponse.ok(sources.originals(member.id(http),id));}
 @GetMapping("/{id}/originals/{snapshot}") public ApiResponse<NoteOriginalService.Snapshot> snapshot(@PathVariable String id,@PathVariable String snapshot,HttpServletRequest http){return ApiResponse.ok(sources.get(member.id(http),id,snapshot));}
 public record FrozenInput(String snapshotId,String overviewImageUrl) {}
 @PostMapping("/ai-inputs") public ApiResponse<FrozenInput> freeze(@RequestBody NoteOriginalService.Input input,HttpServletRequest http){String actor=member.id(http);String snapshot=sources.freeze(actor,input);return ApiResponse.ok(new FrozenInput(snapshot,sources.cover(actor,input.docId())));}
 @PostMapping("/ai-inputs/{request}/results") public ApiResponse<Void> result(@PathVariable String request,@RequestBody NoteOriginalService.DraftResult result,HttpServletRequest http){sources.draftResult(member.id(http),request,result);return ApiResponse.ok(null);}
}
