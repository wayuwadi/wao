package cn.wayuwa.wao.controller;

import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.SkillDtos;
import cn.wayuwa.wao.dto.SkillRequests;
import cn.wayuwa.wao.service.SkillMarketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Skill 管理员审核队列。 */
@RestController
@RequestMapping("/api/admin/skill-reviews")
public class SkillReviewController {
    private static final String FALLBACK_ADMIN="wg";
    private final SkillMarketService market; private final CurrentMember currentMember;
    public SkillReviewController(SkillMarketService market,CurrentMember currentMember){this.market=market;this.currentMember=currentMember;}
    @GetMapping public ApiResponse<List<SkillDtos.ReviewItem>> list(HttpServletRequest request){return ApiResponse.ok(market.reviews(member(request)));}
    @PostMapping("/{versionId}/approve") public ApiResponse<SkillDtos.Version> approve(@PathVariable String versionId,HttpServletRequest request){return ApiResponse.ok(market.approve(member(request),versionId));}
    @PostMapping("/{versionId}/reject") public ApiResponse<SkillDtos.Version> reject(@PathVariable String versionId,@RequestBody SkillRequests.Reject body,HttpServletRequest request){return ApiResponse.ok(market.reject(member(request),versionId,body==null?null:body.note()));}
    private String member(HttpServletRequest request){return currentMember.idOr(request,FALLBACK_ADMIN);}
}
