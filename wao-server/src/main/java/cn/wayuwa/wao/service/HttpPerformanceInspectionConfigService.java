package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.dto.HttpPerformanceInspectionConfigDto;
import cn.wayuwa.wao.dto.SaveHttpPerformanceInspectionConfigRequest;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HTTP 性能巡检配置；业务参数进 system_setting，触发计划同步到 automation_job。 */
@Service
public class HttpPerformanceInspectionConfigService {

    public static final String JOB_ID = "job-http-performance-ai-run";
    private static final String DEFAULT_FREQUENCY = "DAILY";
    private static final String DEFAULT_TIME = "06:30";
    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final int DEFAULT_TOP_N = 10;
    private static final int DEFAULT_MAX_ENDPOINTS = 10;
    private static final Set<String> FREQUENCIES = Set.of("DAILY", "WEEKDAYS", "WEEKLY");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SettingService settings;
    private final AutomationJobMapper jobs;
    private final FeishuWebhookConfigService webhooks;

    public HttpPerformanceInspectionConfigService(SettingService settings, AutomationJobMapper jobs,
            FeishuWebhookConfigService webhooks) {
        this.settings = settings;
        this.jobs = jobs;
        this.webhooks = webhooks;
    }

    /** 返回与真实 automation_job 状态一致的巡检设置。 */
    public HttpPerformanceInspectionConfigDto get() {
        AutomationJobEntity job = requiredJob();
        String frequency = normalizedFrequency(settings.get(
                SettingService.HTTP_INSPECTION_FREQUENCY, DEFAULT_FREQUENCY));
        String time = normalizedTime(settings.get(SettingService.HTTP_INSPECTION_TIME, DEFAULT_TIME));
        int topN = bounded(SettingService.HTTP_INSPECTION_TOP_N, DEFAULT_TOP_N, 1, 20);
        int maxEndpoints = Math.min(topN, bounded(SettingService.HTTP_INSPECTION_MAX_ENDPOINTS,
                DEFAULT_MAX_ENDPOINTS, 1, 20));
        return new HttpPerformanceInspectionConfigDto(
                !"已暂停".equals(job.getStatus()), frequency, time,
                bounded(SettingService.HTTP_INSPECTION_WINDOW_HOURS, DEFAULT_WINDOW_HOURS, 1, 168),
                topN, maxEndpoints,
                trimToNull(settings.get(SettingService.HTTP_INSPECTION_NOTIFICATION_WEBHOOK_ID)));
    }

    /** 原子保存参数并立即更新下一次 Cron 触发时间。 */
    @Transactional
    public HttpPerformanceInspectionConfigDto save(SaveHttpPerformanceInspectionConfigRequest request) {
        String frequency = requireFrequency(request.frequency());
        String time = requireTime(request.time());
        if (request.windowHours() < 1 || request.windowHours() > 168
                || request.topN() < 1 || request.topN() > 20
                || request.maxEndpointsPerRun() < 1 || request.maxEndpointsPerRun() > 20) {
            throw new BadRequestException("巡检指标窗口或接口数量超出允许范围");
        }
        if (request.maxEndpointsPerRun() > request.topN()) {
            throw new BadRequestException("单次最多治理接口数不能超过 Top N 候选数");
        }
        String webhookId = trimToNull(request.notificationWebhookId());
        if (webhookId != null) webhooks.enabledTarget(webhookId);

        AutomationJobEntity job = requiredJob();
        String cron = cron(frequency, time);
        job.setScheduleLabel(label(frequency, time));
        job.setCronExpr(cron);
        job.setStatus(request.enabled() ? "正常" : "已暂停");
        job.setNextFireAt(CronExpression.parse(cron).next(LocalDateTime.now(ZONE)));
        jobs.updateById(job);

        settings.put(SettingService.HTTP_INSPECTION_FREQUENCY, frequency);
        settings.put(SettingService.HTTP_INSPECTION_TIME, time);
        settings.put(SettingService.HTTP_INSPECTION_WINDOW_HOURS, String.valueOf(request.windowHours()));
        settings.put(SettingService.HTTP_INSPECTION_TOP_N, String.valueOf(request.topN()));
        settings.put(SettingService.HTTP_INSPECTION_MAX_ENDPOINTS,
                String.valueOf(request.maxEndpointsPerRun()));
        settings.put(SettingService.HTTP_INSPECTION_NOTIFICATION_WEBHOOK_ID,
                webhookId == null ? "" : webhookId);
        return get();
    }

    private AutomationJobEntity requiredJob() {
        AutomationJobEntity job = jobs.selectById(JOB_ID);
        if (job == null) throw new NotFoundException("HTTP 性能巡检自动任务不存在");
        return job;
    }

    private String requireFrequency(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        if (!FREQUENCIES.contains(value)) {
            throw new BadRequestException("巡检频率只支持每天、工作日或每周一");
        }
        return value;
    }

    private String normalizedFrequency(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        return FREQUENCIES.contains(value) ? value : DEFAULT_FREQUENCY;
    }

    private String requireTime(String raw) {
        try {
            return LocalTime.parse(raw == null ? "" : raw.trim()).withSecond(0).withNano(0).toString();
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("巡检时间必须是 HH:mm");
        }
    }

    private String normalizedTime(String raw) {
        try {
            return requireTime(raw);
        } catch (BadRequestException exception) {
            return DEFAULT_TIME;
        }
    }

    private static String cron(String frequency, String time) {
        LocalTime value = LocalTime.parse(time);
        String prefix = "0 " + value.getMinute() + " " + value.getHour() + " * * ";
        return prefix + switch (frequency) {
            case "WEEKDAYS" -> "MON-FRI";
            case "WEEKLY" -> "MON";
            default -> "*";
        };
    }

    private static String label(String frequency, String time) {
        String prefix = switch (frequency) {
            case "WEEKDAYS" -> "工作日 ";
            case "WEEKLY" -> "每周一 ";
            default -> "每日 ";
        };
        return prefix + time;
    }

    private int bounded(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(settings.get(key, String.valueOf(fallback)));
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
