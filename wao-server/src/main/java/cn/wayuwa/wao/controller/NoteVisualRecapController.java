package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.NoteVisualRecapService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/docs/notes/{docId}/visual-recap")
public class NoteVisualRecapController {
    private final CurrentMember members;
    private final NoteVisualRecapService service;
    public NoteVisualRecapController(CurrentMember members,NoteVisualRecapService service){this.members=members;this.service=service;}
    public record Input(int expectedRev,String requestId){}
    @GetMapping public ApiResponse<Map<String,Object>> get(@PathVariable String docId,HttpServletRequest request){
        request.setAttribute("wao.private-note-asset",true);
        return ApiResponse.ok(service.latest(members.id(request),docId));
    }
    @PostMapping public ApiResponse<Map<String,Object>> start(@PathVariable String docId,@RequestBody Input input,HttpServletRequest request){
        request.setAttribute("wao.private-note-asset",true);
        return ApiResponse.ok(service.start(members.id(request),docId,input.expectedRev(),input.requestId()));
    }
}
