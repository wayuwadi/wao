package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.NoteAiSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
/** 浏览器成员的小记 AI 原子配置接口。 */
@RestController
@RequestMapping("/api/docs/notes/ai-settings")
public class NoteAiSettingsController {
 private final NoteAiSettingsService settings; private final CurrentMember member;
 public NoteAiSettingsController(NoteAiSettingsService settings,CurrentMember member){this.settings=settings;this.member=member;}
 @GetMapping public ApiResponse<NoteAiSettingsService.View> get(HttpServletRequest http){return ApiResponse.ok(settings.get(member.id(http)));}
 @PutMapping public ApiResponse<NoteAiSettingsService.View> put(@RequestBody NoteAiSettingsService.Save request,HttpServletRequest http){return ApiResponse.ok(settings.save(member.id(http),request));}
}
