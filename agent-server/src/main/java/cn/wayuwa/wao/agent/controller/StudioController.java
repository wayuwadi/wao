package cn.wayuwa.wao.agent.controller;

import cn.wayuwa.wao.agent.auth.AgentSessionManager;
import cn.wayuwa.wao.agent.ai.ProfileInitializer;
import cn.wayuwa.wao.agent.common.ApiResponse;
import cn.wayuwa.wao.agent.common.UnauthorizedException;
import cn.wayuwa.wao.agent.dto.RecommendRequest;
import cn.wayuwa.wao.agent.dto.InitializeProfileRequest;
import cn.wayuwa.wao.agent.dto.CreateManualTopicRequest;
import cn.wayuwa.wao.agent.dto.StudioDto;
import cn.wayuwa.wao.agent.dto.TopicSuggestionDto;
import cn.wayuwa.wao.agent.dto.WorkspaceProfileDto;
import cn.wayuwa.wao.agent.hotspot.HotspotSourceStatus;
import cn.wayuwa.wao.agent.service.TopicService;
import cn.wayuwa.wao.agent.service.WorkspaceProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 选题中心：热点、账号定位与选题推荐。 */
@RestController
@RequestMapping("/api/agent/studio")
public class StudioController {
    private final AgentSessionManager sessions;
    private final TopicService topics;
    private final WorkspaceProfileService profiles;
    private final ProfileInitializer profileInitializer;

    public StudioController(AgentSessionManager sessions, TopicService topics,
                            WorkspaceProfileService profiles, ProfileInitializer profileInitializer) {
        this.sessions = sessions;
        this.topics = topics;
        this.profiles = profiles;
        this.profileInitializer = profileInitializer;
    }

    @GetMapping
    public ApiResponse<StudioDto> studio(HttpServletRequest request) {
        return ApiResponse.ok(topics.studio(accountId(request)));
    }

    /** 手动刷新热点，返回每个来源的采集结果，失败的来源会带上原因。 */
    @PostMapping("/hotspots/refresh")
    public ApiResponse<List<HotspotSourceStatus>> refresh(HttpServletRequest request) {
        accountId(request);
        return ApiResponse.ok(topics.refreshHotspots());
    }

    @PostMapping("/profile")
    public ApiResponse<WorkspaceProfileDto> saveProfile(@Valid @RequestBody WorkspaceProfileDto body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(profiles.save(accountId(request), body));
    }

    @PostMapping("/profile/initialize")
    public ApiResponse<WorkspaceProfileDto> initializeProfile(
            @Valid @RequestBody InitializeProfileRequest body, HttpServletRequest request) {
        String accountId = accountId(request);
        return ApiResponse.ok(profiles.save(accountId, profileInitializer.initialize(body.needs())));
    }

    @PostMapping("/recommend")
    public ApiResponse<List<TopicSuggestionDto>> recommend(@Valid @RequestBody RecommendRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(topics.recommend(accountId(request), body));
    }

    @PostMapping("/topics")
    public ApiResponse<TopicSuggestionDto> addTopic(@Valid @RequestBody CreateManualTopicRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(topics.add(accountId(request), body));
    }

    @PostMapping("/suggestions/{suggestionId}/mark")
    public ApiResponse<Void> mark(@PathVariable String suggestionId, @RequestParam String status,
                                  HttpServletRequest request) {
        topics.mark(accountId(request), suggestionId, status);
        return ApiResponse.ok(null);
    }

    private String accountId(HttpServletRequest request) {
        String accountId = sessions.currentAccountId(request);
        if (accountId == null) throw new UnauthorizedException("WAO 登录已过期，请重新登录后进入 Agent");
        return accountId;
    }
}
