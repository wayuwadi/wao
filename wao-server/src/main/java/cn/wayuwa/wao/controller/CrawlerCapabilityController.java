package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CrawlerCapabilityDto;
import cn.wayuwa.wao.dto.SaveCrawlerCapabilityRequest;
import cn.wayuwa.wao.service.CrawlerCapabilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 爬虫能力源码资产接口。 */
@RestController
@RequestMapping("/api/crawler-capabilities")
public class CrawlerCapabilityController {
    private final CrawlerCapabilityService capabilities;
    private final CurrentMember currentMember;

    public CrawlerCapabilityController(CrawlerCapabilityService capabilities, CurrentMember currentMember) {
        this.capabilities = capabilities;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<CrawlerCapabilityDto>> list() {
        return ApiResponse.ok(capabilities.list());
    }

    @PostMapping
    public ApiResponse<CrawlerCapabilityDto> create(@Valid @RequestBody SaveCrawlerCapabilityRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(capabilities.create(request, currentMember.idOr(http, "unknown")));
    }

    @PutMapping("/{id}")
    public ApiResponse<CrawlerCapabilityDto> update(@PathVariable String id,
            @Valid @RequestBody SaveCrawlerCapabilityRequest request, HttpServletRequest http) {
        return ApiResponse.ok(capabilities.update(id, request, currentMember.idOr(http, "unknown")));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, @RequestParam int revision) {
        capabilities.delete(id, revision);
        return ApiResponse.ok(null);
    }
}
