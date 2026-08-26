package cn.wayuwa.wao.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.SkillDtos;
import cn.wayuwa.wao.service.SkillMarketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 游客可用的 Skill 市场、详情与审核版本下载。 */
@RestController
@RequestMapping("/api/public")
public class PublicSkillController {
    private final SkillMarketService market; private final CurrentMember currentMember;
    public PublicSkillController(SkillMarketService market, CurrentMember currentMember) { this.market=market; this.currentMember=currentMember; }

    @GetMapping("/skill-categories")
    public ApiResponse<List<SkillDtos.Category>> categories() { return ApiResponse.ok(market.categories()); }

    @GetMapping("/skills")
    public ApiResponse<SkillDtos.Page> skills(@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String category, @RequestParam(required=false) String platform,
            @RequestParam(defaultValue="newest") String sort, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="24") int pageSize) {
        return ApiResponse.ok(market.market(keyword,category,platform,sort,page,pageSize));
    }

    @GetMapping("/skills/{handle}/{slug}")
    public ApiResponse<SkillDtos.Detail> detail(@PathVariable String handle, @PathVariable String slug,
            HttpServletRequest request) {
        return ApiResponse.ok(market.detail(handle,slug,currentMember.id(request)));
    }

    @GetMapping("/skills/{handle}/{slug}/files")
    public ApiResponse<SkillDtos.FilePreview> file(@PathVariable String handle, @PathVariable String slug,
            @RequestParam(required=false) String version, @RequestParam String path, HttpServletRequest request) {
        return ApiResponse.ok(market.filePreview(handle,slug,version,path,clientKey(request)));
    }

    @GetMapping("/skills/{handle}/{slug}/download")
    public ResponseEntity<byte[]> download(@PathVariable String handle, @PathVariable String slug,
            @RequestParam(required=false) String version, HttpServletRequest request) {
        SkillMarketService.Download file=market.download(handle,slug,version,clientKey(request));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Skill-Sha256",file.sha256()).contentLength(file.content().length).body(file.content());
    }

    private static String clientKey(HttpServletRequest request) {
        String real=request.getHeader("X-Real-IP");
        return real==null||real.isBlank()?request.getRemoteAddr():real.trim();
    }
}
