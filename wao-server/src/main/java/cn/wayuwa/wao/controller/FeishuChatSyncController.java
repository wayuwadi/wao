package cn.wayuwa.wao.controller;

import java.time.LocalDate;
import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.FeishuChatSyncConfigDto;
import cn.wayuwa.wao.dto.FeishuSenderDto;
import cn.wayuwa.wao.dto.SaveChatSyncConfigRequest;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuChat;
import cn.wayuwa.wao.service.FeishuChatSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 飞书群聊同步知识库的配置与手动触发接口。 */
@RestController
@RequestMapping("/api/feishu/chat-sync")
public class FeishuChatSyncController {

    private final FeishuChatSyncService service;

    public FeishuChatSyncController(FeishuChatSyncService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public ApiResponse<FeishuChatSyncConfigDto> config() {
        return ApiResponse.ok(service.config());
    }

    @PutMapping("/config")
    public ApiResponse<FeishuChatSyncConfigDto> saveConfig(@RequestBody SaveChatSyncConfigRequest request) {
        return ApiResponse.ok(service.saveConfig(request));
    }

    /** 机器人所在的群，供配置页选目标群。 */
    @GetMapping("/chats")
    public ApiResponse<List<FeishuChat>> chats() {
        return ApiResponse.ok(service.chats());
    }

    /** 群成员，供把发言人 ID 对上人名。 */
    @GetMapping("/members")
    public ApiResponse<List<FeishuSenderDto>> members(@RequestParam(required = false) String chatId) {
        return ApiResponse.ok(service.members(chatId));
    }

    /**
     * 手动同步一天或一段连续日期；批量时仍按天写入并覆盖各自当天的文档。
     *
     * @param day 形如 2026-07-25，留空即今天
     * @param startDay 批量同步起始日期，需与 endDay 同时提供
     * @param endDay 批量同步结束日期，需与 startDay 同时提供
     */
    @PostMapping("/run")
    public ApiResponse<String> run(@RequestParam(required = false) String day,
            @RequestParam(required = false) String startDay, @RequestParam(required = false) String endDay) {
        if (startDay != null || endDay != null) {
            if (startDay == null || startDay.isBlank() || endDay == null || endDay.isBlank()) {
                throw new IllegalArgumentException("批量同步请同时填写起始和结束日期");
            }
            return ApiResponse.ok(service.syncDays(LocalDate.parse(startDay), LocalDate.parse(endDay)));
        }
        return ApiResponse.ok(service.syncDay(day == null || day.isBlank() ? null : LocalDate.parse(day)));
    }
}
