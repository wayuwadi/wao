package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CaptureVocabularyEntryDto;
import cn.wayuwa.wao.dto.CaptureVocabularyEntryRequest;
import cn.wayuwa.wao.dto.ReviewVocabularyEntryRequest;
import cn.wayuwa.wao.dto.UpdateVocabularyEntryRequest;
import cn.wayuwa.wao.dto.VocabularyEntryDto;
import cn.wayuwa.wao.service.VocabularyEntryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录成员的英语单词本接口。 */
@RestController
@RequestMapping("/api/vocabulary")
public class VocabularyEntryController {

    private static final String FALLBACK_MEMBER = "wg";
    private final VocabularyEntryService vocabulary;
    private final CurrentMember currentMember;

    public VocabularyEntryController(VocabularyEntryService vocabulary, CurrentMember currentMember) {
        this.vocabulary = vocabulary;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<VocabularyEntryDto>> list(HttpServletRequest http) {
        return ApiResponse.ok(vocabulary.list(member(http)));
    }

    @PostMapping
    public ApiResponse<CaptureVocabularyEntryDto> capture(
            @Valid @RequestBody CaptureVocabularyEntryRequest request, HttpServletRequest http) {
        return ApiResponse.ok(vocabulary.capture(request, member(http)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<VocabularyEntryDto> update(@PathVariable String id,
            @Valid @RequestBody UpdateVocabularyEntryRequest request, HttpServletRequest http) {
        return ApiResponse.ok(vocabulary.update(id, request, member(http)));
    }

    @PostMapping("/{id}/reviews")
    public ApiResponse<VocabularyEntryDto> review(@PathVariable String id,
            @Valid @RequestBody ReviewVocabularyEntryRequest request, HttpServletRequest http) {
        return ApiResponse.ok(vocabulary.review(id, request.state(), member(http)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        vocabulary.delete(id, member(http));
        return ApiResponse.ok(null);
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
