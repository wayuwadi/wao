package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.PersonalPrincipleDto;
import cn.wayuwa.wao.dto.SavePersonalPrincipleRequest;
import cn.wayuwa.wao.dto.SavePrincipleCheckinRequest;
import cn.wayuwa.wao.service.PersonalPrincipleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 浏览器版多人准则和本人记录接口。 */
@RestController
@RequestMapping("/api/personal-principles")
public class PersonalPrincipleController {
    private final PersonalPrincipleService service;
    private final CurrentMember currentMember;
    public PersonalPrincipleController(PersonalPrincipleService service, CurrentMember currentMember) {
        this.service = service; this.currentMember = currentMember;
    }
    @GetMapping
    public ApiResponse<List<PersonalPrincipleDto>> list(@RequestParam(required = false) LocalDate date, HttpServletRequest http) {
        return ApiResponse.ok(service.list(member(http), date));
    }
    @PostMapping
    public ApiResponse<PersonalPrincipleDto> create(@Valid @RequestBody SavePersonalPrincipleRequest body, HttpServletRequest http) {
        return ApiResponse.ok(service.create(member(http), body));
    }
    @PutMapping("/{id}")
    public ApiResponse<PersonalPrincipleDto> update(@PathVariable String id, @Valid @RequestBody SavePersonalPrincipleRequest body, HttpServletRequest http) {
        return ApiResponse.ok(service.update(id, member(http), body));
    }
    @PutMapping("/{id}/consent")
    public ApiResponse<PersonalPrincipleDto> consent(@PathVariable String id, @RequestBody ConsentBody body, HttpServletRequest http) {
        return ApiResponse.ok(service.consent(id, member(http), body.consent()));
    }
    @PutMapping("/{id}/checkins/{date}")
    public ApiResponse<PersonalPrincipleDto> checkin(@PathVariable String id, @PathVariable LocalDate date,
            @Valid @RequestBody SavePrincipleCheckinRequest body, HttpServletRequest http) {
        return ApiResponse.ok(service.checkin(id, member(http), date, body.status(), body.note()));
    }
    @GetMapping("/{id}/revisions")
    public ApiResponse<List<String>> revisions(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(service.revisions(id, member(http)));
    }
    private String member(HttpServletRequest http) { return currentMember.idOr(http, "wg"); }
    public record ConsentBody(String consent) { }
}
