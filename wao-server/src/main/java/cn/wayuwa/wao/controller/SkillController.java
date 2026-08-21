package cn.wayuwa.wao.controller;

import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.SkillDtos;
import cn.wayuwa.wao.dto.SkillRequests;
import cn.wayuwa.wao.service.SkillMarketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 已登录成员的 Skill 发布、版本管理与收藏接口。 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private static final String FALLBACK_MEMBER="wg";
    private final SkillMarketService market; private final CurrentMember currentMember;
    public SkillController(SkillMarketService market, CurrentMember currentMember) { this.market=market; this.currentMember=currentMember; }

    @GetMapping("/publisher/me")
    public ApiResponse<SkillDtos.PublisherProfile> publisher(HttpServletRequest request) { return ApiResponse.ok(market.myPublisher(member(request))); }
    @PutMapping("/publisher/me")
    public ApiResponse<SkillDtos.PublisherProfile> savePublisher(@RequestBody SkillRequests.PublisherProfile body,HttpServletRequest request) { return ApiResponse.ok(market.savePublisher(member(request),body)); }
    @GetMapping("/mine")
    public ApiResponse<List<SkillDtos.MySkill>> mine(HttpServletRequest request) { return ApiResponse.ok(market.mine(member(request))); }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SkillDtos.MySkill> createDraft(@RequestPart("metadata") SkillRequests.Draft metadata,
            @RequestPart(value="archive",required=false) MultipartFile archive,
            @RequestPart(value="files",required=false) List<MultipartFile> files,
            @RequestParam(value="paths",required=false) List<String> paths,HttpServletRequest request) {
        if(archive!=null&&!archive.isEmpty())return ApiResponse.ok(market.createZipDraft(member(request),metadata,archive));
        if(files!=null&&!files.isEmpty())return ApiResponse.ok(market.createFolderDraft(member(request),metadata,files,paths));
        throw new BadRequestException("请上传 ZIP 或选择 Skill 文件夹");
    }

    @PatchMapping("/{skillId}/versions/{versionId}")
    public ApiResponse<SkillDtos.Version> patch(@PathVariable String skillId,@PathVariable String versionId,
            @RequestBody SkillRequests.DraftPatch body,HttpServletRequest request){return ApiResponse.ok(market.patchDraft(member(request),skillId,versionId,body));}
    @PostMapping("/{skillId}/versions/{versionId}/submit")
    public ApiResponse<SkillDtos.Version> submit(@PathVariable String skillId,@PathVariable String versionId,HttpServletRequest request){return ApiResponse.ok(market.submit(member(request),skillId,versionId));}
    @DeleteMapping("/{skillId}/versions/{versionId}")
    public ApiResponse<Void> deleteDraft(@PathVariable String skillId,@PathVariable String versionId,HttpServletRequest request){market.deleteDraft(member(request),skillId,versionId);return ApiResponse.ok(null);}
    @PutMapping("/{skillId}/listed")
    public ApiResponse<Void> listed(@PathVariable String skillId,@RequestParam boolean value,HttpServletRequest request){market.setListed(member(request),skillId,value);return ApiResponse.ok(null);}
    @PutMapping("/{skillId}/favorite")
    public ApiResponse<Void> favorite(@PathVariable String skillId,@RequestParam boolean value,HttpServletRequest request){market.favorite(member(request),skillId,value);return ApiResponse.ok(null);}
    private String member(HttpServletRequest request){return currentMember.idOr(request,FALLBACK_MEMBER);}
}
