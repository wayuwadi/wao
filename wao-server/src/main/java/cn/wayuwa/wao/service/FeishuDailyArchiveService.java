package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.FeishuChatSyncConfigDto;
import cn.wayuwa.wao.dto.FeishuDailyArchiveDto;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.mapper.FeishuDailyArchiveMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 上海午夜单日归档；数据库先永久领取，当日失败或崩溃均不自动再次抓取。 */
@Service
public class FeishuDailyArchiveService {
    public static final String JOB_ID = "feishu-daily-archive";
    public static final String CRON = "0 0 0 * * *";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final FeishuDailyArchiveMapper db;
    private final FeishuChatSyncService chatSync;
    private final TransactionTemplate transactions;

    public FeishuDailyArchiveService(FeishuDailyArchiveMapper db, FeishuChatSyncService chatSync,
            PlatformTransactionManager manager) {
        this.db = db; this.chatSync = chatSync;
        transactions = new TransactionTemplate(manager);
        // 领取必须先提交；外层事务的回滚不能使已发出的飞书读取被再次领取。
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 只读状态，不创建任务或发起任何飞书请求。 */
    public FeishuDailyArchiveDto state() {
        var job = db.job();
        var config = chatSync.config();
        var row = db.latestRun();
        FeishuDailyArchiveDto.Run run = null;
        if (row != null) {
            String[] parts = row.getResult().split("\\|", -1);
            run = new FeishuDailyArchiveDto.Run(java.time.LocalDate.parse(row.getRunTimeLabel()), parts[0],
                    row.getRunAt(), row.getDurationMs(), "FAILED".equals(parts[0]) && parts.length > 1 ? parts[1] : null,
                    "SUCCESS".equals(parts[0]) && parts.length > 1 ? parts[1] : null,
                    "SUCCESS".equals(parts[0]) && parts.length > 2 ? Integer.valueOf(parts[2]) : null);
        }
        boolean enabled = job != null && "正常".equals(job.getStatus());
        return new FeishuDailyArchiveDto(enabled, ZONE.getId(), CRON, enabled ? job.getNextFireAt() : null,
                configured(config), config.appId(), config.chatId(), config.chatName(), config.kbId(), run);
    }

    /** 保存唯一开关；首次启用等到下一个午夜，同状态保存不改变计划。 */
    public FeishuDailyArchiveDto configure(boolean enabled, boolean expectedEnabled) {
        configure(enabled, expectedEnabled, LocalDateTime.now(ZONE));
        return state();
    }

    void configure(boolean enabled, boolean expectedEnabled, LocalDateTime now) {
        if (enabled && !configured(chatSync.config()))
            throw new BadRequestException("请先配置飞书应用、群聊和归档知识库");
        transactions.executeWithoutResult(tx -> {
            db.ensureJob();
            var job = db.lockJob();
            if (expectedEnabled != "正常".equals(job.getStatus()))
                throw new ConflictException("每日归档开关已在其他窗口更新，请刷新后重试");
            if (enabled == "正常".equals(job.getStatus())) return;
            db.configure(enabled ? "正常" : "已暂停", enabled ? now.toLocalDate().plusDays(1).atStartOfDay() : null);
        });
    }

    /** 全局调度部署每天上海 00:00 执行；专用调度复用此入口。 */
    @Scheduled(cron = CRON, zone = "Asia/Shanghai")
    public void runMidnight() { runMidnight(LocalDateTime.now(ZONE)); }

    void runMidnight(LocalDateTime now) {
        // 仅午夜窗口，不在部署启动、白天或手动入口补扫遗漏日期。
        if (now.getHour() != 0 || now.getMinute() != 0) return;
        var day = now.toLocalDate().minusDays(1);
        String runId = "fda" + day.format(DateTimeFormatter.BASIC_ISO_DATE);
        boolean claimed = Boolean.TRUE.equals(transactions.execute(tx -> {
            var job = db.lockJob();
            if (job == null || !"正常".equals(job.getStatus()) || job.getNextFireAt() == null
                    || job.getNextFireAt().isAfter(now.toLocalDate().atStartOfDay())) return false;
            // 跳过错过的历史日期；无论领取成败，下一次只允许明天午夜。
            db.configure("正常", now.toLocalDate().plusDays(1).atStartOfDay());
            return db.claim(runId, day.toString(), now) == 1;
        }));
        if (!claimed) return;
        long start = System.nanoTime();
        String result;
        try {
            var config = chatSync.config();
            var archived = chatSync.archivePreviousDay(now.toLocalDate(), config.chatId(), config.kbId(), () -> {
                var job = db.job();
                if (job == null || !"正常".equals(job.getStatus())) throw new BadRequestException("ARCHIVE_DISABLED");
            });
            result = "SUCCESS|" + archived.document().id() + "|" + archived.messages().size();
        } catch (RuntimeException ex) {
            // 不保存外部响应、消息正文、凭据或原始异常；失败不触发重试。
            String code = ex instanceof FeishuApiException ? "FEISHU_API_FAILED"
                    : ex instanceof StaleChatArchiveException ? "STALE_ARCHIVE"
                    : ex instanceof BadRequestException ? ("ARCHIVE_DISABLED".equals(ex.getMessage())
                            ? "ARCHIVE_DISABLED" : "CONFIG_CHANGED_OR_INCOMPLETE") : "ARCHIVE_FAILED";
            result = "FAILED|" + code;
        }
        int duration = (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        String completedResult = result;
        transactions.executeWithoutResult(tx -> db.complete(runId, completedResult, duration));
    }

    private static boolean configured(FeishuChatSyncConfigDto c) {
        return c.appSecretSet() && has(c.appId()) && has(c.chatId()) && has(c.kbId());
    }
    private static boolean has(String value) { return value != null && !value.isBlank(); }
}
