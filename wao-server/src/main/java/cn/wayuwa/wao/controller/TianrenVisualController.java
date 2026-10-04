package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.TianrenSettingsService;
import cn.wayuwa.wao.service.TianrenVisualService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 本人卦象的设置、图意状态和私有图片；所有入口要求成员身份。 */
@RestController
@RequestMapping("/api/tianren")
public class TianrenVisualController {
    private final CurrentMember member;
    private final TianrenSettingsService settings;
    private final TianrenVisualService visuals;
    public TianrenVisualController(CurrentMember member,TianrenSettingsService settings,TianrenVisualService visuals){this.member=member;this.settings=settings;this.visuals=visuals;}
    @GetMapping("/settings") public ApiResponse<TianrenSettingsService.View> settings(HttpServletRequest http){return ApiResponse.ok(settings.get(member.id(http)));}
    @PutMapping("/settings") public ApiResponse<TianrenSettingsService.View> save(@RequestBody TianrenSettingsService.Save input,HttpServletRequest http){return ApiResponse.ok(settings.save(member.id(http),input));}
    @GetMapping("/readings/{id}/visual") public ApiResponse<TianrenVisualService.View> visual(@PathVariable String id,HttpServletRequest http){return ApiResponse.ok(visuals.get(member.id(http),id));}
    @PostMapping("/readings/{id}/visual/image") public ApiResponse<TianrenVisualService.View> start(@PathVariable String id,@RequestBody TianrenVisualService.ImageRequest input,HttpServletRequest http){return ApiResponse.ok(visuals.start(member.id(http),id,input));}
    @GetMapping("/readings/{id}/visual/images/{attempt}/raw") public ResponseEntity<byte[]> raw(@PathVariable String id,@PathVariable String attempt,HttpServletRequest http){return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(visuals.raw(member.id(http),id,attempt));}
}
