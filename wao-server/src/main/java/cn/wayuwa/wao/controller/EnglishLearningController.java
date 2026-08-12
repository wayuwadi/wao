package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.EnglishLearningOverviewDto;
import cn.wayuwa.wao.dto.EnglishLearningProfileDto;
import cn.wayuwa.wao.dto.EnglishLearningSessionDto;
import cn.wayuwa.wao.dto.SaveEnglishLearningProfileRequest;
import cn.wayuwa.wao.dto.SaveEnglishLearningSessionRequest;
import cn.wayuwa.wao.service.EnglishLearningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员的英语训练目标、过程记录与复盘接口。 */
@RestController
@RequestMapping("/api/english-learning")
public class EnglishLearningController {
    private final EnglishLearningService service;
    private final CurrentMember currentMember;

    public EnglishLearningController(EnglishLearningService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 读取某日训练与累计进度。 */
    @GetMapping
    public ApiResponse<EnglishLearningOverviewDto> overview(
            @RequestParam(required = false) LocalDate date, HttpServletRequest request) {
        return ApiResponse.ok(service.overview(currentMember.idOr(request, "wg"), date));
    }

    /** 保存成员自己的学习目标。 */
    @PutMapping("/profile")
    public ApiResponse<EnglishLearningProfileDto> saveProfile(
            @Valid @RequestBody SaveEnglishLearningProfileRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.saveProfile(currentMember.idOr(request, "wg"), body));
    }

    /** 保存指定日期的训练进度和复盘。 */
    @PutMapping("/sessions/{date}")
    public ApiResponse<EnglishLearningSessionDto> saveSession(@PathVariable LocalDate date,
            @Valid @RequestBody SaveEnglishLearningSessionRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.saveSession(currentMember.idOr(request, "wg"), date, body));
    }
}
