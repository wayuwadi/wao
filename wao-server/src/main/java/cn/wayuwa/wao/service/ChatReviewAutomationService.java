package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.ChatReviewAutoConfigEntity;
import cn.wayuwa.wao.dto.ChatReviewAutoConfigDto;
import cn.wayuwa.wao.mapper.ChatReviewAutoConfigMapper;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 自动聊天复盘的配置、跨节点到期领取与真实运行触发。 */
@Service
public class ChatReviewAutomationService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MIN_INTERVAL_SECONDS = 300;
    private static final int MAX_INTERVAL_SECONDS = 7 * 24 * 60 * 60;

    private final ChatReviewAutoConfigMapper mapper;
    private final ChatReviewService reviews;
    private final AiAssistantService assistant;

    public ChatReviewAutomationService(ChatReviewAutoConfigMapper mapper,
            ChatReviewService reviews, AiAssistantService assistant) {
        this.mapper = mapper;
        this.reviews = reviews;
        this.assistant = assistant;
    }

    /** 返回全局唯一策略；migration 保证配置行始终存在。 */
    public ChatReviewAutoConfigDto config() {
        ChatReviewAutoConfigEntity row = require();
        return new ChatReviewAutoConfigDto(Boolean.TRUE.equals(row.getEnabled()), row.getIntervalSeconds(),
                row.getRangeKind(), row.getRangeValue(), row.getFocusText(), row.getAiCapabilityId(),
                offset(row.getNextFireAt()), row.getLastRunId(), offset(row.getUpdatedAt()));
    }

    /** 保存启停、间隔、消息范围和 AI 能力；所有字段作为一份策略原子替换。 */
    public ChatReviewAutoConfigDto save(boolean enabled, int intervalSeconds, String rangeKind,
            int rangeValue, String focus, String aiCapabilityId, String memberId) {
        if (intervalSeconds < MIN_INTERVAL_SECONDS || intervalSeconds > MAX_INTERVAL_SECONDS) {
            throw new BadRequestException("自动复盘间隔可选 5 分钟～7 天");
        }
        String kind = StringUtils.hasText(rangeKind) ? rangeKind.trim().toUpperCase() : "";
        if (!("MESSAGES".equals(kind) && rangeValue >= 5 && rangeValue <= 100)
                && !("HOURS".equals(kind) && rangeValue >= 1 && rangeValue <= 168)) {
            throw new BadRequestException("最近消息可选 5～100 条，最近时间可选 1～168 小时");
        }
        String normalizedFocus = normalizeFocus(focus);
        String capability = assistant.validateCapability(aiCapabilityId);
        if (mapper.save(enabled, intervalSeconds, kind, rangeValue, normalizedFocus, capability,
                StringUtils.hasText(memberId) ? memberId.trim() : "unknown") != 1) {
            throw new IllegalStateException("自动复盘配置不存在");
        }
        return config();
    }

    /** 若计划点已到，跨节点只触发一次并记录真实复盘运行；没有到期返回 false。 */
    public boolean triggerDue() {
        if (mapper.claimDue() != 1) return false;
        ChatReviewAutoConfigEntity row = require();
        String runId = reviews.createAutomatic(row.getRangeKind(), row.getRangeValue(),
                row.getFocusText(), row.getAiCapabilityId());
        mapper.recordRun(runId);
        return true;
    }

    private ChatReviewAutoConfigEntity require() {
        ChatReviewAutoConfigEntity row = mapper.selectById(1);
        if (row == null) throw new IllegalStateException("自动复盘配置尚未初始化");
        return row;
    }

    private static String normalizeFocus(String focus) {
        if (!StringUtils.hasText(focus)) return null;
        String value = focus.trim();
        if (value.codePointCount(0, value.length()) > 1000) {
            throw new BadRequestException("本次重点最多 1000 字");
        }
        return value;
    }

    private static java.time.OffsetDateTime offset(java.time.LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
    }
}
