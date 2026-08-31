package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiActivityRow;
import cn.wayuwa.wao.dto.AiActivityDto;
import cn.wayuwa.wao.dto.AiActivityPageDto;
import cn.wayuwa.wao.mapper.AiActivityMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 统一 AI 活动分页；类型值是可扩展的稳定契约。 */
@Service
public class AiActivityService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> TYPES = Set.of(
            "ALL", "FEATURE_DEVELOPMENT", "AI_INSPECTION", "CHAT_REVIEW");
    private static final Set<String> STATUSES = Set.of("ALL", "SUCCEEDED", "FAILED");
    private final AiActivityMapper mapper;

    public AiActivityService(AiActivityMapper mapper) {
        this.mapper = mapper;
    }

    /** 按类型返回真实终态活动；页大小限制为 8～50。 */
    public AiActivityPageDto page(
            String requestedType, String requestedStatus, int requestedPage, int requestedSize) {
        String type = requestedType == null ? "ALL" : requestedType.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new BadRequestException("不支持的 AI 活动类型");
        String status = requestedStatus == null ? "ALL" : requestedStatus.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw new BadRequestException("不支持的 AI 活动结果");
        int page = Math.max(1, requestedPage);
        int size = Math.max(8, Math.min(50, requestedSize));
        long total = mapper.count(type, status);
        long offset = (long) (page - 1) * size;
        return new AiActivityPageDto(mapper.page(type, status, offset, size).stream().map(this::dto).toList(),
                total, page, size);
    }

    private AiActivityDto dto(AiActivityRow row) {
        return new AiActivityDto(row.getId(), row.getActivityType(), row.getTitle(), row.getStatus(),
                row.getSubtitle(), row.getCapabilityLabel(), row.getTargetId(), row.getResultPreview(),
                offset(row.getCreatedAt()), offset(row.getFinishedAt()));
    }

    private static java.time.OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
    }
}
