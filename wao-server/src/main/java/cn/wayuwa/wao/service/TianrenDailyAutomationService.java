package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.TianrenDailyReadingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 成员明确启用后，每日九点起卦并将专用解读入队；群投递仍由现有通知服务处理。 */
@Service
public class TianrenDailyAutomationService {
    private static final Logger log = LoggerFactory.getLogger(TianrenDailyAutomationService.class);
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> ACTIVE = Set.of("queued", "running", "provider_queued", "provider_running",
            "secure_queued", "secure_running", "web_search_queued", "web_search_running");
    private final TianrenReadingService readings;
    private final TianrenDailyReadingMapper readingMapper;
    private final MemberPreferenceMapper preferences;
    private final AiAssistantRunMapper runs;
    private final AiAssistantConversationService conversations;
    private final AiAssistantService assistant;
    private final WorkdayRhythmDashboardService rhythm;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public record AiStatus(boolean configured, String message, String runStatus, String runId) { }

    /** 只读取当前成员今日解读状态，刷新页面不会重复发起模型调用。 */
    public AiStatus status(String memberId) {
        var executor = assistant.tianrenStatus();
        var reading = readings.today(memberId);
        AiAssistantRunEntity latest = null;
        if (reading != null && StringUtils.hasText(reading.conversationId())) {
            latest = runs.selectOne(new LambdaQueryWrapper<AiAssistantRunEntity>()
                    .eq(AiAssistantRunEntity::getMemberId, memberId)
                    .eq(AiAssistantRunEntity::getConversationId, reading.conversationId())
                    .eq(AiAssistantRunEntity::getUsagePurpose, "TIANREN")
                    .orderByDesc(AiAssistantRunEntity::getCreatedAt).last("LIMIT 1"));
        }
        String message = executor.message();
        if (latest != null && ACTIVE.contains(latest.getStatus())) message = "secure_queued".equals(latest.getStatus())
                ? "本机 Codex 排队中，关闭页面后继续处理" : "本机 Codex 正在解读，关闭页面后继续处理";
        else if (latest != null && "failed".equals(latest.getStatus()))
            message = executor.configured() ? "上次解读失败；本机 Codex 已就绪，可保留关注点重试" : executor.message();
        return new AiStatus(executor.configured(), message, latest == null ? null : latest.getStatus(), latest == null ? null : latest.getId());
    }

    @Autowired
    public TianrenDailyAutomationService(TianrenReadingService readings, TianrenDailyReadingMapper readingMapper,
            MemberPreferenceMapper preferences, AiAssistantRunMapper runs,
            AiAssistantConversationService conversations, AiAssistantService assistant,
            WorkdayRhythmDashboardService rhythm, ObjectMapper json, PlatformTransactionManager manager) {
        this(readings, readingMapper, preferences, runs, conversations, assistant, rhythm, json, manager, Clock.system(ZONE));
    }

    TianrenDailyAutomationService(TianrenReadingService readings, TianrenDailyReadingMapper readingMapper,
            MemberPreferenceMapper preferences, AiAssistantRunMapper runs,
            AiAssistantConversationService conversations, AiAssistantService assistant,
            WorkdayRhythmDashboardService rhythm, ObjectMapper json, PlatformTransactionManager manager, Clock clock) {
        this.readings = readings; this.readingMapper = readingMapper; this.preferences = preferences;
        this.runs = runs; this.conversations = conversations; this.assistant = assistant;
        this.rhythm = rhythm; this.json = json; this.clock = clock.withZone(ZONE);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 九点后补齐当天缺失工作；服务重启也只恢复今天，不生成过去日期或重复发送卡片。 */
    public void runDue() {
        if (LocalTime.now(clock).isBefore(LocalTime.of(9, 0))) return;
        LocalDate date = LocalDate.now(clock);
        String after = "";
        while (true) {
            List<String> members = preferences.membersWithPreference(WorkdayRhythmNotificationService.CONFIG_KEY, after);
            for (String member : members) {
                if (!date.equals(LocalDate.now(clock))) return;
                try {
                    // 先提交卦象，即使 AI 离线或成员另有问答，九点的卦象也不会被回滚。
                    boolean cast = Boolean.TRUE.equals(transaction.execute(status -> {
                        readingMapper.lockMember(member);
                        if (!enabled(member)) return false;
                        if (readings.today(member) == null) {
                            readings.createToday(member);
                            rhythm.completeSystemItem(member, date, "TIANREN");
                        }
                        return true;
                    }));
                    if (cast) enqueue(member, null, true);
                } catch (RuntimeException ex) {
                    log.warn("每日九点起卦暂未完成，将保留当天存档并稍后检查：{}", ex.getClass().getSimpleName());
                }
            }
            if (members.size() < 50) return;
            after = members.get(members.size() - 1);
        }
    }

    /** 手动解读复用同一成员锁，先提交队列再等待结果，不持有数据库锁等待 AI。 */
    public void interpretStream(String memberId, String focus, SseEmitter emitter) {
        try {
            String runId = enqueue(memberId, focus, false);
            assistant.streamQueuedRun(runId, memberId, emitter);
        } catch (Exception ex) {
            try { emitter.send(SseEmitter.event().name("error").data(
                    ex.getMessage() == null ? "卦象解读暂不可用，请稍后重试" : ex.getMessage())); }
            catch (Exception ignored) { /* 浏览器可能已关闭。 */ }
            emitter.complete();
        }
    }

    String enqueue(String memberId, String focus, boolean automatic) {
        return transaction.execute(status -> {
            readingMapper.lockMember(memberId);
            if (automatic && !enabled(memberId)) return null;
            var reading = readings.today(memberId);
            if (reading == null) throw new BadRequestException("请先起出今天的一卦");
            if (StringUtils.hasText(reading.conversationId())) {
                if (automatic && StringUtils.hasText(conversations.latestAssistantReply(reading.conversationId(), memberId))) return null;
                List<AiAssistantRunEntity> history = runs.selectList(new LambdaQueryWrapper<AiAssistantRunEntity>()
                        .eq(AiAssistantRunEntity::getConversationId, reading.conversationId())
                        .eq(AiAssistantRunEntity::getMemberId, memberId)
                        .orderByDesc(AiAssistantRunEntity::getCreatedAt).last("LIMIT 3"));
                if (history.stream().anyMatch(run -> ACTIVE.contains(run.getStatus()))) {
                    if (automatic) return null;
                    throw new BadRequestException("今日卦象正在解读，请稍后查看");
                }
                if (automatic && !history.isEmpty()) {
                    var latest = history.get(0);
                    if (history.size() >= 3 || "succeeded".equals(latest.getStatus())) return null;
                    // 和普通问答运行记录使用同一服务端本地时间，失败后最多再尝试两次。
                    if (latest.getUpdatedAt() != null && latest.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(5))) return null;
                }
            }
            var prepared = readings.prepareInterpretation(memberId, focus);
            return assistant.enqueueTianren(prepared.request(), memberId, prepared.trustedContext());
        });
    }

    private boolean enabled(String memberId) {
        try {
            String raw = preferences.find(memberId, WorkdayRhythmNotificationService.CONFIG_KEY);
            if (raw == null) return false;
            var config = WorkdayNotificationTargets.resolve(json.readTree(raw).path("energyNotification"), "tianrenTarget");
            if (!config.path("enabled").isBoolean() || !config.path("enabled").asBoolean()
                    || !config.path("notifyTianren").isBoolean() || !config.path("notifyTianren").asBoolean()
                    || !config.path("autoTianrenAtNine").isBoolean() || !config.path("autoTianrenAtNine").asBoolean()) return false;
            String channel = config.path("channel").asText("CHAT");
            return Set.of("CHAT", "WEBHOOK").contains(channel)
                    && !config.path("WEBHOOK".equals(channel) ? "webhookId" : "chatId").asText("").isBlank();
        } catch (Exception ex) { return false; }
    }
}
