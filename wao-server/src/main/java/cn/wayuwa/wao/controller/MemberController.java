package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.dto.OrgDto;
import cn.wayuwa.wao.service.MemberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 组织与成员接口。 */
@RestController
@RequestMapping("/api")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/members")
    public ApiResponse<List<MemberDto>> members() {
        return ApiResponse.ok(memberService.list());
    }

    @GetMapping("/org")
    public ApiResponse<OrgDto> org() {
        return ApiResponse.ok(memberService.org());
    }
}
