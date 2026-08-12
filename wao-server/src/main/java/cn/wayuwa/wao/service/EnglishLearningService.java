package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.EnglishLearningProfileEntity;
import cn.wayuwa.wao.domain.EnglishLearningSessionEntity;
import cn.wayuwa.wao.dto.EnglishLearningOverviewDto;
import cn.wayuwa.wao.dto.EnglishLearningProfileDto;
import cn.wayuwa.wao.dto.EnglishLearningSessionDto;
import cn.wayuwa.wao.dto.SaveEnglishLearningProfileRequest;
import cn.wayuwa.wao.dto.SaveEnglishLearningSessionRequest;
import cn.wayuwa.wao.mapper.EnglishLearningProfileMapper;
import cn.wayuwa.wao.mapper.EnglishLearningSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 成员私有的英语训练目标、每日过程记录和连续性统计。 */
@Service
public class EnglishLearningService {
    private static final ZoneId WAO_ZONE = ZoneId.of("Asia/Shanghai");
    private final EnglishLearningProfileMapper profiles;
    private final EnglishLearningSessionMapper sessions;

    public EnglishLearningService(EnglishLearningProfileMapper profiles,
            EnglishLearningSessionMapper sessions) {
        this.profiles = profiles;
        this.sessions = sessions;
    }

    /**
     * 读取当前成员的训练首页，数据仅来自该成员已经保存的事实记录。
     *
     * @param memberId 当前成员
     * @param selectedDate 要读取的日期；为空取上海时区今天
     * @return 设置、当日记录、累计统计和最近记录
     */
    public EnglishLearningOverviewDto overview(String memberId, LocalDate selectedDate) {
        requireMember(memberId);
        LocalDate today = LocalDate.now(WAO_ZONE);
        LocalDate date = selectedDate == null ? today : selectedDate;
        if (date.isAfter(today)) throw new BadRequestException("不能查看未来的训练记录");

        EnglishLearningProfileEntity profile = findProfile(memberId);
        EnglishLearningSessionEntity selected = findSession(memberId, date);
        List<EnglishLearningSessionEntity> history = sessions.selectList(
                new LambdaQueryWrapper<EnglishLearningSessionEntity>()
                        .eq(EnglishLearningSessionEntity::getMemberId, memberId)
                        .orderByDesc(EnglishLearningSessionEntity::getStudyDate));
        List<EnglishLearningSessionEntity> completed = history.stream()
                .filter(item -> item.getCompletedAt() != null).toList();
        int totalMinutes = history.stream().mapToInt(item -> safe(item.getPracticeMinutes())).sum();
        int currentStreak = currentStreak(completed, today);
        List<EnglishLearningSessionDto> recent = history.stream().limit(14).map(this::view).toList();
        return new EnglishLearningOverviewDto(
                profile == null ? null : view(profile), date, selected == null ? null : view(selected),
                completed.size(), currentStreak, totalMinutes, recent);
    }

    /** 保存或更新当前成员的英语学习目标与每日投入。 */
    @Transactional
    public EnglishLearningProfileDto saveProfile(String memberId, SaveEnglishLearningProfileRequest request) {
        requireMember(memberId);
        LocalDate today = LocalDate.now(WAO_ZONE);
        if (request.startDate().isAfter(today)) throw new BadRequestException("开始日期不能晚于今天");
        LocalDateTime now = LocalDateTime.now(WAO_ZONE);
        EnglishLearningProfileEntity row = findProfile(memberId);
        boolean creating = row == null;
        if (creating) {
            row = new EnglishLearningProfileEntity();
            row.setId(id("ep"));
            row.setMemberId(memberId);
            row.setCreatedAt(now);
        }
        row.setGoal(request.goal().trim());
        row.setDailyTargetMinutes(request.dailyTargetMinutes());
        row.setStartDate(request.startDate());
        row.setUpdatedAt(now);
        if (creating) {
            try {
                profiles.insert(row);
            } catch (DuplicateKeyException exception) {
                EnglishLearningProfileEntity raced = findProfile(memberId);
                if (raced == null) throw exception;
                raced.setGoal(row.getGoal());
                raced.setDailyTargetMinutes(row.getDailyTargetMinutes());
                raced.setStartDate(row.getStartDate());
                raced.setUpdatedAt(now);
                profiles.updateById(raced);
                row = raced;
            }
        } else {
            profiles.updateById(row);
        }
        return view(row);
    }

    /**
     * 保存当前成员某一天的训练进度；重复保存更新同一行，完成时间首次闭环时写入。
     *
     * @param memberId 当前成员
     * @param date 训练日期
     * @param request 听说读写事实与复盘
     * @return 保存后的日记录
     */
    @Transactional
    public EnglishLearningSessionDto saveSession(String memberId, LocalDate date,
            SaveEnglishLearningSessionRequest request) {
        requireMember(memberId);
        LocalDate today = LocalDate.now(WAO_ZONE);
        if (date == null || date.isAfter(today)) throw new BadRequestException("训练日期不能晚于今天");
        if (findProfile(memberId) == null) throw new BadRequestException("请先设置英语学习目标");
        EnglishLearningSessionEntity row = findSession(memberId, date);
        boolean remainsCompleted = request.completed() || row != null && row.getCompletedAt() != null;
        if (remainsCompleted && (!request.listeningDone() || !request.speakingDone()
                || !request.readingDone() || !request.writingDone() || request.confidence() == null)) {
            throw new BadRequestException("完成今日训练前，请完成听说读写并记录信心自评");
        }

        LocalDateTime now = LocalDateTime.now(WAO_ZONE);
        boolean creating = row == null;
        if (creating) {
            row = new EnglishLearningSessionEntity();
            row.setId(id("es"));
            row.setMemberId(memberId);
            row.setStudyDate(date);
            row.setCreatedAt(now);
        }
        row.setLessonId(request.lessonId().trim());
        row.setListeningDone(request.listeningDone());
        row.setSpeakingDone(request.speakingDone());
        row.setReadingDone(request.readingDone());
        row.setWritingDone(request.writingDone());
        row.setPracticeMinutes(request.practiceMinutes());
        row.setConfidence(request.confidence());
        row.setWritingText(trim(request.writingText()));
        row.setReflection(trim(request.reflection()));
        row.setNextFocus(trim(request.nextFocus()));
        if (request.completed() && row.getCompletedAt() == null) row.setCompletedAt(now);
        row.setUpdatedAt(now);

        if (creating) {
            try {
                sessions.insert(row);
            } catch (DuplicateKeyException exception) {
                EnglishLearningSessionEntity raced = findSession(memberId, date);
                if (raced == null) throw exception;
                copyProgress(row, raced);
                sessions.updateById(raced);
                row = raced;
            }
        } else {
            sessions.updateById(row);
        }
        return view(row);
    }

    private EnglishLearningProfileEntity findProfile(String memberId) {
        return profiles.selectOne(new LambdaQueryWrapper<EnglishLearningProfileEntity>()
                .eq(EnglishLearningProfileEntity::getMemberId, memberId).last("LIMIT 1"));
    }

    private EnglishLearningSessionEntity findSession(String memberId, LocalDate date) {
        return sessions.selectOne(new LambdaQueryWrapper<EnglishLearningSessionEntity>()
                .eq(EnglishLearningSessionEntity::getMemberId, memberId)
                .eq(EnglishLearningSessionEntity::getStudyDate, date).last("LIMIT 1"));
    }

    private int currentStreak(List<EnglishLearningSessionEntity> completed, LocalDate today) {
        if (completed.isEmpty()) return 0;
        LocalDate latest = completed.get(0).getStudyDate();
        if (ChronoUnit.DAYS.between(latest, today) > 1) return 0;
        int streak = 0;
        LocalDate expected = latest;
        for (EnglishLearningSessionEntity session : completed) {
            if (session.getStudyDate().equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (session.getStudyDate().isBefore(expected)) {
                break;
            }
        }
        return streak;
    }

    private EnglishLearningProfileDto view(EnglishLearningProfileEntity row) {
        return new EnglishLearningProfileDto(row.getId(), row.getGoal(), row.getDailyTargetMinutes(),
                row.getStartDate(), row.getUpdatedAt());
    }

    private EnglishLearningSessionDto view(EnglishLearningSessionEntity row) {
        return new EnglishLearningSessionDto(row.getId(), row.getStudyDate(), row.getLessonId(),
                Boolean.TRUE.equals(row.getListeningDone()), Boolean.TRUE.equals(row.getSpeakingDone()),
                Boolean.TRUE.equals(row.getReadingDone()), Boolean.TRUE.equals(row.getWritingDone()),
                safe(row.getPracticeMinutes()), row.getConfidence(), trim(row.getWritingText()),
                trim(row.getReflection()), trim(row.getNextFocus()), row.getCompletedAt(), row.getUpdatedAt());
    }

    private void copyProgress(EnglishLearningSessionEntity source, EnglishLearningSessionEntity target) {
        target.setLessonId(source.getLessonId());
        target.setListeningDone(source.getListeningDone());
        target.setSpeakingDone(source.getSpeakingDone());
        target.setReadingDone(source.getReadingDone());
        target.setWritingDone(source.getWritingDone());
        target.setPracticeMinutes(source.getPracticeMinutes());
        target.setConfidence(source.getConfidence());
        target.setWritingText(source.getWritingText());
        target.setReflection(source.getReflection());
        target.setNextFocus(source.getNextFocus());
        target.setCompletedAt(source.getCompletedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    private static int safe(Integer value) { return value == null ? 0 : value; }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static void requireMember(String memberId) {
        if (memberId == null || memberId.isBlank()) throw new BadRequestException("无法识别当前成员");
    }
}
