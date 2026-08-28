package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.MemberLoginUsageRow;
import cn.wayuwa.wao.domain.NotificationEventRow;
import cn.wayuwa.wao.dto.MemberLoginUsageDto;
import cn.wayuwa.wao.dto.NotificationCenterDto;
import cn.wayuwa.wao.dto.NotificationEventDto;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.NotificationCenterMapper;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

/** 聚合真实系统动态、登录频率并维护成员级已读水位。 */
@Service
public class NotificationCenterService {
    static final String READ_AT_KEY = "notification-center-read-at";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int EVENT_LIMIT = 30;

    private final NotificationCenterMapper center;
    private final MemberPreferenceMapper preferences;

    public NotificationCenterService(NotificationCenterMapper center, MemberPreferenceMapper preferences) {
        this.center = center;
        this.preferences = preferences;
    }

    /** 返回当前成员的最近事件、团队登录频率与未读数量。 */
    public NotificationCenterDto get(String memberId) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        OffsetDateTime readAt = readAt(memberId);
        LocalDateTime unreadSince = (readAt == null ? now.minusDays(7) : readAt)
                .atZoneSameInstant(ZONE).toLocalDateTime();
        return new NotificationCenterDto(
                center.recentEvents(EVENT_LIMIT).stream().map(this::eventDto).toList(),
                center.loginUsage(now.minusDays(7).toLocalDateTime(), now.minusDays(30).toLocalDateTime())
                        .stream().map(this::usageDto).toList(),
                center.countEventsSince(unreadSince), readAt);
    }

    /** 将当前成员的已读水位推进到服务端当前时刻，并返回该水位。 */
    public OffsetDateTime markRead(String memberId) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        preferences.upsert(memberId, READ_AT_KEY, now.toString());
        return now;
    }

    private OffsetDateTime readAt(String memberId) {
        String value = preferences.find(memberId, READ_AT_KEY);
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private NotificationEventDto eventDto(NotificationEventRow row) {
        return new NotificationEventDto(row.getEventId(), row.getEventType(), row.getTitle(), row.getDetail(),
                row.getActorName(), offset(row.getOccurredAt()), row.getTargetPath(), row.getSeverity());
    }

    private MemberLoginUsageDto usageDto(MemberLoginUsageRow row) {
        return new MemberLoginUsageDto(row.getMemberId(), row.getMemberName(), row.getColor(),
                row.getCount7Days(), row.getCount30Days(), offset(row.getLastLoginAt()));
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
    }
}
