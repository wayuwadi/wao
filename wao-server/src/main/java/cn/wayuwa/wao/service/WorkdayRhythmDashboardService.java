package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmChecklistItemEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmChecklistOccurrenceEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmDayEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmReminderDeliveryEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmSettingEntity;
import cn.wayuwa.wao.dto.CreateWorkdayRhythmDocumentRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.SaveWorkdayRhythmChecklistItemRequest;
import cn.wayuwa.wao.dto.SaveWorkdayRhythmFocusRequest;
import cn.wayuwa.wao.dto.SaveWorkdayRhythmSettingRequest;
import cn.wayuwa.wao.dto.WorkdayRhythmChecklistItemDto;
import cn.wayuwa.wao.dto.WorkdayRhythmDashboardDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistItemMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistOccurrenceMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmDayMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmReminderDeliveryMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmSettingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 今日节奏每日面板、必做清单与每日文档的主业务服务。 */
@Service
public class WorkdayRhythmDashboardService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final String DAY_KEY_PREFIX = "workday-rhythm-day-";
    private static final String ALTERNATE_WEEK_KEY = "alternate-work-week";
    private static final Set<String> CATEGORIES = Set.of("WORK", "LIFE");
    private static final Set<String> SCHEDULES = Set.of("DAILY", "WORKDAY", "WEEKDAYS");
    private static final Set<String> ITEM_TYPES = Set.of("CHECK", "COUNTDOWN");
    private static final Set<String> SYSTEM_KINDS = Set.of("PLAN", "CLOCK_IN", "CLOCK_OUT", "TIANREN");
    private static final int MIN_FOCUS_COUNT = 3;
    private static final int MAX_FOCUS_COUNT = 20;

    private final WorkdayRhythmSettingMapper settings;
    private final WorkdayRhythmChecklistItemMapper items;
    private final WorkdayRhythmChecklistOccurrenceMapper occurrences;
    private final WorkdayRhythmReminderDeliveryMapper deliveries;
    private final WorkdayRhythmDayMapper days;
    private final MemberPreferenceMapper preferences;
    private final DocMapper docs;
    private final KnowledgeService knowledge;
    private final ObjectMapper json;

    public WorkdayRhythmDashboardService(WorkdayRhythmSettingMapper settings,
            WorkdayRhythmChecklistItemMapper items, WorkdayRhythmChecklistOccurrenceMapper occurrences,
            WorkdayRhythmReminderDeliveryMapper deliveries, WorkdayRhythmDayMapper days,
            MemberPreferenceMapper preferences, DocMapper docs, KnowledgeService knowledge, ObjectMapper json) {
        this.settings = settings; this.items = items; this.occurrences = occurrences;
        this.deliveries = deliveries; this.days = days; this.preferences = preferences;
        this.docs = docs; this.knowledge = knowledge; this.json = json;
    }

    /** 读取当天面板，幂等补齐默认清单并固化当日实例。 */
    @Transactional
    public WorkdayRhythmDashboardDto dashboard(String memberId, LocalDate date) {
        requireDate(date);
        WorkdayRhythmSettingEntity setting = ensureSetting(memberId);
        ensureDefaults(memberId, setting);
        List<WorkdayRhythmChecklistItemEntity> templates = itemEntities(memberId);
        Workday day = workday(memberId, date, setting);
        List<WorkdayRhythmChecklistOccurrenceEntity> result = materialize(
                memberId, date, day.active(), templates);
        // 次日凌晨仍展示前一天未结束的跨午夜项。
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (date.equals(now.toLocalDate())) {
            materialize(memberId, date.minusDays(1),
                    workday(memberId, date.minusDays(1), setting).active(), templates).stream()
                    .filter(value -> Boolean.TRUE.equals(value.getDueNextDay()) && value.getCompletedAt() == null
                            && !dueAt(value).isBefore(now))
                    .forEach(result::add);
        }
        result.forEach(value -> refreshCountdown(value, now));
        result.sort(Comparator.comparing((WorkdayRhythmChecklistOccurrenceEntity value) ->
                value.getCompletedAt() != null).thenComparing(this::dueAt));
        List<WorkdayRhythmDashboardDto.Occurrence> dto = result.stream().map(value -> occurrenceDto(value, now)).toList();
        long completed = dto.stream().filter(value -> value.completedAt() != null).count();
        return new WorkdayRhythmDashboardDto(date, day.active(), day.override(), day.label(), dto,
                (int) completed, dto.size(), document(memberId, date, setting), settingDto(setting));
    }

    /** 读取成员的清单模板。 */
    @Transactional
    public List<WorkdayRhythmChecklistItemDto> checklistItems(String memberId) {
        WorkdayRhythmSettingEntity setting = ensureSetting(memberId);
        ensureDefaults(memberId, setting);
        return itemEntities(memberId).stream().map(this::itemDto).toList();
    }

    /** 新增一条自定义每日必做。 */
    public WorkdayRhythmChecklistItemDto createItem(String memberId, SaveWorkdayRhythmChecklistItemRequest request) {
        WorkdayRhythmChecklistItemEntity value = new WorkdayRhythmChecklistItemEntity();
        value.setId(id("wri")); value.setMemberId(memberId); value.setKind("CUSTOM"); value.setSystemKey(null);
        value.setCreatedAt(LocalDateTime.now(ZONE));
        apply(value, request); items.insert(value); return itemDto(value);
    }

    /** 修改清单模板；系统行为类型永不从请求体覆盖。 */
    @Transactional
    public WorkdayRhythmChecklistItemDto updateItem(String memberId, String id,
            SaveWorkdayRhythmChecklistItemRequest request) {
        WorkdayRhythmChecklistItemEntity value = ownedItem(memberId, id);
        if (!"CUSTOM".equals(value.getKind()) && StringUtils.hasText(request.itemType())
                && !"CHECK".equals(request.itemType())) {
            throw new BadRequestException("系统联动项只能使用普通打卡类型");
        }
        apply(value, request); items.updateById(value);
        syncTodayOccurrence(value, LocalDate.now(ZONE));
        return itemDto(value);
    }

    /** 删除自定义项采用软删除；系统项只能停用。 */
    public void deleteItem(String memberId, String id) {
        WorkdayRhythmChecklistItemEntity value = ownedItem(memberId, id);
        if (!"CUSTOM".equals(value.getKind())) throw new BadRequestException("系统联动项不能删除，可以在设置中停用");
        value.setActive(false); value.setDeletedAt(LocalDateTime.now(ZONE)); value.setUpdatedAt(LocalDateTime.now(ZONE));
        items.updateById(value);
    }

    /** 保存面板设置，必要时创建或停用卦象联动项。 */
    @Transactional
    public WorkdayRhythmDashboardDto.Settings saveSettings(String memberId, SaveWorkdayRhythmSettingRequest request) {
        String pattern = trim(request.documentTitlePattern());
        if (!StringUtils.hasText(pattern)) pattern = "{date} 每日工作记录";
        if (pattern.length() > 128) throw new BadRequestException("每日文档标题格式最多 128 字");
        boolean hasKb = StringUtils.hasText(request.documentKbId());
        boolean hasFolder = StringUtils.hasText(request.documentFolderId());
        if (hasKb != hasFolder) {
            throw new BadRequestException("每日文档的知识库和文件夹需要一起选择");
        }
        if (StringUtils.hasText(request.documentTemplateId()) && !hasFolder) {
            throw new BadRequestException("选择模板前请先选择每日文档目标");
        }
        if (hasKb) {
            knowledge.validateDailyRhythmTarget(request.documentKbId(), request.documentFolderId(),
                    request.documentTemplateId(), memberId);
        }
        WorkdayRhythmSettingEntity value = ensureSetting(memberId);
        value.setFollowAlternateWeek(request.followAlternateWeek());
        value.setDocumentKbId(blank(request.documentKbId())); value.setDocumentFolderId(blank(request.documentFolderId()));
        value.setDocumentTemplateId(blank(request.documentTemplateId())); value.setDocumentTitlePattern(pattern);
        boolean tianrenRequired = request.tianrenEnabled() && request.tianrenRequired();
        value.setTianrenEnabled(request.tianrenEnabled()); value.setTianrenRequired(tianrenRequired);
        value.setUpdatedAt(LocalDateTime.now(ZONE)); settings.updateById(value);
        ensureTianrenItem(memberId, tianrenRequired);
        return settingDto(value);
    }

    /** 保存当天工作日覆盖；null 恢复自动判断。 */
    @Transactional
    public WorkdayRhythmDashboardDto setWorkdayOverride(String memberId, LocalDate date, Boolean value) {
        ensureDay(memberId, date);
        days.updateOverride(memberId, date, value, LocalDateTime.now(ZONE));
        syncLegacyWorkday(memberId, date, value == null ? workday(memberId, date, ensureSetting(memberId)).active() : value);
        return dashboard(memberId, date);
    }

    /** 保存成员今日重点；默认三项并允许追加，至少三项非空时自动完成「制定今日计划」。 */
    @Transactional
    public WorkdayRhythmDashboardDto saveFocus(String memberId, LocalDate date, SaveWorkdayRhythmFocusRequest request) {
        if (request.focus() == null || request.focus().size() < MIN_FOCUS_COUNT
                || request.focus().size() > MAX_FOCUS_COUNT) {
            throw new BadRequestException("今日重点需要保留 3 到 20 个栏位");
        }
        List<String> focus = request.focus().stream().map(this::checkedFocus).toList();
        ObjectNode day = readDay(memberId, date);
        ArrayNode array = day.putArray("focus"); focus.forEach(array::add);
        preferences.upsert(memberId, DAY_KEY_PREFIX + date, write(day));
        if (focus.stream().filter(StringUtils::hasText).count() >= MIN_FOCUS_COUNT) {
            completeSystemItem(memberId, date, "PLAN");
        }
        return dashboard(memberId, date);
    }

    /** 成员手动完成或撤销当天项，完成时根据快照截止时间判定是否准时。 */
    @Transactional
    public WorkdayRhythmDashboardDto.Occurrence setCompleted(String memberId, String id, boolean completed) {
        WorkdayRhythmChecklistOccurrenceEntity value = occurrences.findOwned(id, memberId);
        if (value == null) throw new NotFoundException("今日必做项不存在");
        if (completed && "COUNTDOWN".equals(value.getItemType())) {
            throw new BadRequestException("倒计时项目需要计时结束后完成");
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        value.setCompletedAt(completed ? now : null);
        value.setCompletedOnTime(completed ? !now.isAfter(dueAt(value)) : null);
        value.setCompletionSource(completed ? "MANUAL" : null);
        if (!completed && "COUNTDOWN".equals(value.getItemType())) {
            value.setTimerStartedAt(null); value.setTimerElapsedSeconds(0);
        }
        value.setUpdatedAt(now); occurrences.updateById(value);
        return occurrenceDto(value, now);
    }

    /** 开始、暂停或重置倒计时，状态按成员和每日实例持久化。 */
    @Transactional
    public WorkdayRhythmDashboardDto.Occurrence updateTimer(String memberId, String id, String action) {
        WorkdayRhythmChecklistOccurrenceEntity value = occurrences.lockOwned(id, memberId);
        if (value == null) throw new NotFoundException("今日必做项不存在");
        if (!"COUNTDOWN".equals(value.getItemType())) throw new BadRequestException("这个项目不是倒计时类型");
        LocalDateTime now = LocalDateTime.now(ZONE);
        refreshCountdown(value, now);
        String normalized = trim(action).toUpperCase(java.util.Locale.ROOT);
        if ("RESET".equals(normalized)) {
            value.setTimerStartedAt(null); value.setTimerElapsedSeconds(0);
            value.setCompletedAt(null); value.setCompletedOnTime(null); value.setCompletionSource(null);
        } else if ("START".equals(normalized)) {
            if (value.getCompletedAt() == null && value.getTimerStartedAt() == null) value.setTimerStartedAt(now);
        } else if ("PAUSE".equals(normalized)) {
            pauseTimer(value, now);
        } else {
            throw new BadRequestException("倒计时操作只支持 START、PAUSE 或 RESET");
        }
        refreshCountdown(value, now);
        value.setUpdatedAt(now); occurrences.updateById(value);
        return occurrenceDto(value, now);
    }

    /** 系统联动动作成功后完成对应项，不会撤销既有完成事实。 */
    @Transactional
    public void completeSystemItem(String memberId, LocalDate date, String kind) {
        WorkdayRhythmSettingEntity setting = ensureSetting(memberId);
        ensureDefaults(memberId, setting);
        List<WorkdayRhythmChecklistItemEntity> templates = itemEntities(memberId);
        materialize(memberId, date, workday(memberId, date, setting).active(), templates).stream()
                .filter(value -> kind.equals(value.getKind()))
                .filter(value -> value.getCompletedAt() == null).findFirst().ifPresent(value -> {
                    LocalDateTime now = LocalDateTime.now(ZONE); value.setCompletedAt(now);
                    value.setCompletedOnTime(!now.isAfter(dueAt(value))); value.setCompletionSource("SYSTEM"); value.setUpdatedAt(now);
                    occurrences.updateById(value);
                });
    }

    /**
     * 用考勤事实同步上下班清单的完成时间；只覆盖或撤销此前同样来自考勤的值，绝不覆盖用户手动完成。
     *
     * @param memberId 成员 id
     * @param date 考勤日期
     * @param kind CLOCK_IN 或 CLOCK_OUT
     * @param completedAt 有打卡时为真实时间，无打卡时为 null
     */
    @Transactional
    public void syncAttendanceCompletion(String memberId, LocalDate date, String kind, LocalDateTime completedAt) {
        if (!Set.of("CLOCK_IN", "CLOCK_OUT").contains(kind)) throw new BadRequestException("考勤只能联动上下班项");
        WorkdayRhythmSettingEntity setting = ensureSetting(memberId);
        ensureDefaults(memberId, setting);
        List<WorkdayRhythmChecklistItemEntity> templates = itemEntities(memberId);
        materialize(memberId, date, workday(memberId, date, setting).active(), templates).stream()
                .filter(value -> kind.equals(value.getKind())).findFirst().ifPresent(value -> {
            if (value.getCompletedAt() != null && !"ATTENDANCE".equals(value.getCompletionSource())) return;
            value.setCompletedAt(completedAt);
            value.setCompletedOnTime(completedAt == null ? null : !completedAt.isAfter(dueAt(value)));
            value.setCompletionSource(completedAt == null ? null : "ATTENDANCE");
            value.setUpdatedAt(LocalDateTime.now(ZONE));
            occurrences.updateById(value);
        });
    }

    /** 返回某天结合大小周与手工覆盖后的工作日判断，供考勤统计与缺卡提示共用。 */
    public WorkdayInfo workdayInfo(String memberId, LocalDate date) {
        Workday value = workday(memberId, date, ensureSetting(memberId));
        return new WorkdayInfo(value.active(), value.label());
    }

    /** 首次输入时在所选知识库幂等创建当天唯一文档。 */
    @Transactional
    public DocDto createDocument(String memberId, LocalDate date, CreateWorkdayRhythmDocumentRequest request) {
        WorkdayRhythmSettingEntity setting = ensureSetting(memberId);
        if (!StringUtils.hasText(setting.getDocumentKbId()) || !StringUtils.hasText(setting.getDocumentFolderId())) {
            throw new BadRequestException("请先在「调整节奏」中选择每日文档的知识库和文件夹");
        }
        WorkdayRhythmDayEntity day = ensureDay(memberId, date);
        day = days.lock(memberId, date);
        if (StringUtils.hasText(day.getDocumentId())) {
            DocEntity existing = docs.selectById(day.getDocumentId());
            if (existing == null) throw new NotFoundException("当天文档绑定已损坏，请联系管理员");
            return knowledge.getDoc(existing.getId());
        }
        String id = stableDocumentId(memberId, date);
        String title = StringUtils.hasText(request.title()) ? request.title().trim() : documentTitle(setting, date);
        if (title.length() > 200) throw new BadRequestException("每日文档标题最多 200 字");
        DocDto document = knowledge.createDailyRhythmDoc(id, setting.getDocumentKbId(),
                setting.getDocumentFolderId(), setting.getDocumentTemplateId(), title, request.blocks(), memberId);
        days.bindDocument(memberId, date, document.id(), LocalDateTime.now(ZONE));
        return document;
    }

    private WorkdayRhythmSettingEntity ensureSetting(String memberId) {
        WorkdayRhythmSettingEntity value = settings.selectById(memberId);
        if (value != null) return value;
        LocalDateTime now = LocalDateTime.now(ZONE); value = new WorkdayRhythmSettingEntity();
        value.setMemberId(memberId); value.setFollowAlternateWeek(true); value.setDocumentTitlePattern("{date} 每日工作记录");
        value.setTianrenEnabled(true); value.setTianrenRequired(false); value.setCreatedAt(now); value.setUpdatedAt(now);
        try { settings.insert(value); } catch (DuplicateKeyException ignored) { return settings.selectById(memberId); }
        return value;
    }

    private void ensureDefaults(String memberId, WorkdayRhythmSettingEntity setting) {
        Map<String, WorkdayRhythmChecklistItemEntity> existing = new HashMap<>();
        items.selectList(new LambdaQueryWrapper<WorkdayRhythmChecklistItemEntity>()
                        .eq(WorkdayRhythmChecklistItemEntity::getMemberId, memberId)
                        .in(WorkdayRhythmChecklistItemEntity::getSystemKey, SYSTEM_KINDS))
                .forEach(item -> existing.put(item.getSystemKey(), item));
        ensureSystemItem(existing, memberId, "PLAN", "制定今日计划",
                LocalTime.of(7, 0), LocalTime.of(9, 0), false, 10);
        ensureSystemItem(existing, memberId, "CLOCK_IN", "上班打卡",
                LocalTime.of(7, 0), LocalTime.of(9, 0), false, 20);
        ensureSystemItem(existing, memberId, "CLOCK_OUT", "下班打卡",
                LocalTime.of(18, 0), LocalTime.of(7, 0), true, 30);
        WorkdayRhythmChecklistItemEntity tianren = ensureSystemItem(existing, memberId, "TIANREN", "每日一卦",
                LocalTime.of(7, 0), LocalTime.of(7, 0), true, 40);
        configureTianren(tianren, Boolean.TRUE.equals(setting.getTianrenRequired()));
    }

    private void ensureTianrenItem(String memberId, boolean required) {
        Map<String, WorkdayRhythmChecklistItemEntity> existing = new HashMap<>();
        WorkdayRhythmChecklistItemEntity stored = items.selectOne(
                new LambdaQueryWrapper<WorkdayRhythmChecklistItemEntity>()
                        .eq(WorkdayRhythmChecklistItemEntity::getMemberId, memberId)
                        .eq(WorkdayRhythmChecklistItemEntity::getSystemKey, "TIANREN").last("LIMIT 1"));
        if (stored != null) existing.put("TIANREN", stored);
        WorkdayRhythmChecklistItemEntity tianren = ensureSystemItem(existing, memberId, "TIANREN", "每日一卦",
                LocalTime.of(7, 0), LocalTime.of(7, 0), true, 40);
        configureTianren(tianren, required);
    }

    private void configureTianren(WorkdayRhythmChecklistItemEntity tianren, boolean required) {
        if (tianren != null && !Objects.equals(tianren.getActive(), required)) {
            tianren.setActive(required); tianren.setScheduleType("DAILY"); tianren.setCategory("LIFE");
            tianren.setUpdatedAt(LocalDateTime.now(ZONE)); items.updateById(tianren);
        }
    }

    private WorkdayRhythmChecklistItemEntity ensureSystemItem(
            Map<String, WorkdayRhythmChecklistItemEntity> existing, String memberId, String kind, String title,
            LocalTime start, LocalTime due, boolean nextDay, int order) {
        WorkdayRhythmChecklistItemEntity stored = existing.get(kind);
        if (stored != null) return stored;
        LocalDateTime now = LocalDateTime.now(ZONE); WorkdayRhythmChecklistItemEntity value = new WorkdayRhythmChecklistItemEntity();
        value.setId(id("wri")); value.setMemberId(memberId); value.setKind(kind); value.setSystemKey(kind);
        value.setCategory("WORK"); value.setTitle(title); value.setScheduleType("WORKDAY"); value.setWeekdaysJson("[]");
        value.setItemType("CHECK"); value.setDurationSeconds(null);
        value.setStartTime(start); value.setDueTime(due); value.setDueNextDay(nextDay); value.setActive(true); value.setOrd(order);
        value.setNotifyDirect(false); value.setNotifyChat(false); value.setNotifyWebhook(false);
        value.setReminderTime(null); value.setReminderNextDay(false); value.setCreatedAt(now); value.setUpdatedAt(now);
        try {
            items.insert(value);
        } catch (DuplicateKeyException ignored) {
            value = items.selectOne(new LambdaQueryWrapper<WorkdayRhythmChecklistItemEntity>()
                    .eq(WorkdayRhythmChecklistItemEntity::getMemberId, memberId)
                    .eq(WorkdayRhythmChecklistItemEntity::getSystemKey, kind).last("LIMIT 1"));
        }
        if (value != null) existing.put(kind, value);
        return value;
    }

    private List<WorkdayRhythmChecklistOccurrenceEntity> materialize(String memberId, LocalDate date,
            boolean workday, List<WorkdayRhythmChecklistItemEntity> templates) {
        List<WorkdayRhythmChecklistOccurrenceEntity> existing = occurrences(memberId, date);
        Map<String, WorkdayRhythmChecklistOccurrenceEntity> byItem = new HashMap<>();
        existing.forEach(value -> byItem.put(value.getItemId(), value));
        List<String> pendingIds = existing.stream().filter(value -> value.getCompletedAt() == null)
                .map(WorkdayRhythmChecklistOccurrenceEntity::getId).toList();
        Set<String> delivered = pendingIds.isEmpty() ? Set.of() : deliveries.selectList(
                        new LambdaQueryWrapper<WorkdayRhythmReminderDeliveryEntity>()
                                .in(WorkdayRhythmReminderDeliveryEntity::getOccurrenceId, pendingIds))
                .stream().map(WorkdayRhythmReminderDeliveryEntity::getOccurrenceId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        boolean raced = false;
        for (WorkdayRhythmChecklistItemEntity item : templates) {
            if (!applies(item, date, workday)) continue;
            WorkdayRhythmChecklistOccurrenceEntity occurrence = byItem.get(item.getId());
            if (occurrence == null) {
                WorkdayRhythmChecklistOccurrenceEntity created = snapshot(item, date);
                try {
                    occurrences.insert(created); existing.add(created); byItem.put(item.getId(), created);
                } catch (DuplicateKeyException ignored) {
                    raced = true;
                }
            } else if (occurrence.getCompletedAt() == null && !delivered.contains(occurrence.getId())
                    && !snapshotMatches(item, occurrence)) {
                copySnapshot(item, occurrence); occurrence.setUpdatedAt(LocalDateTime.now(ZONE));
                occurrences.updateById(occurrence);
            }
        }
        return raced ? occurrences(memberId, date) : existing;
    }

    private boolean snapshotMatches(WorkdayRhythmChecklistItemEntity item,
            WorkdayRhythmChecklistOccurrenceEntity occurrence) {
        return Objects.equals(item.getKind(), occurrence.getKind())
                && Objects.equals(item.getCategory(), occurrence.getCategory())
                && Objects.equals(item.getTitle(), occurrence.getTitle())
                && Objects.equals(item.getItemType(), occurrence.getItemType())
                && Objects.equals(item.getDurationSeconds(), occurrence.getDurationSeconds())
                && Objects.equals(item.getStartTime(), occurrence.getStartTime())
                && Objects.equals(item.getDueTime(), occurrence.getDueTime())
                && Objects.equals(item.getDueNextDay(), occurrence.getDueNextDay())
                && Objects.equals(item.getReminderTime(), occurrence.getReminderTime())
                && Objects.equals(item.getReminderNextDay(), occurrence.getReminderNextDay())
                && Objects.equals(item.getNotifyDirect(), occurrence.getNotifyDirect())
                && Objects.equals(item.getNotifyChat(), occurrence.getNotifyChat())
                && Objects.equals(item.getChatId(), occurrence.getChatId())
                && Objects.equals(item.getChatName(), occurrence.getChatName())
                && Objects.equals(item.getNotifyWebhook(), occurrence.getNotifyWebhook())
                && Objects.equals(item.getWebhookId(), occurrence.getWebhookId())
                && Objects.equals(item.getWebhookName(), occurrence.getWebhookName());
    }

    private void syncTodayOccurrence(WorkdayRhythmChecklistItemEntity item, LocalDate date) {
        WorkdayRhythmChecklistOccurrenceEntity value = occurrences.selectOne(
                new LambdaQueryWrapper<WorkdayRhythmChecklistOccurrenceEntity>()
                        .eq(WorkdayRhythmChecklistOccurrenceEntity::getItemId, item.getId())
                        .eq(WorkdayRhythmChecklistOccurrenceEntity::getOccurrenceDate, date).last("LIMIT 1"));
        if (value != null && value.getCompletedAt() == null && deliveries.selectCount(
                new LambdaQueryWrapper<cn.wayuwa.wao.domain.WorkdayRhythmReminderDeliveryEntity>()
                        .eq(cn.wayuwa.wao.domain.WorkdayRhythmReminderDeliveryEntity::getOccurrenceId, value.getId())) == 0) {
            if (!applies(item, date, workday(item.getMemberId(), date, ensureSetting(item.getMemberId())).active())) {
                occurrences.deleteById(value.getId());
                return;
            }
            copySnapshot(item, value); value.setUpdatedAt(LocalDateTime.now(ZONE)); occurrences.updateById(value);
        }
    }

    private boolean applies(WorkdayRhythmChecklistItemEntity item, LocalDate date, boolean workday) {
        if (!Boolean.TRUE.equals(item.getActive()) || item.getDeletedAt() != null) return false;
        if ("DAILY".equals(item.getScheduleType())) return true;
        if ("WORKDAY".equals(item.getScheduleType())) return workday;
        return weekdays(item.getWeekdaysJson()).contains(date.getDayOfWeek().getValue());
    }

    private Workday workday(String memberId, LocalDate date, WorkdayRhythmSettingEntity setting) {
        WorkdayRhythmDayEntity stored = days.find(memberId, date);
        if (stored != null && stored.getWorkdayOverride() != null) return new Workday(stored.getWorkdayOverride(), stored.getWorkdayOverride(), "当天手动覆盖");
        boolean weekday = date.getDayOfWeek().getValue() <= 5;
        if (!Boolean.TRUE.equals(setting.getFollowAlternateWeek()) || date.getDayOfWeek() != DayOfWeek.SUNDAY) {
            return new Workday(weekday, null, weekday ? "工作日" : "休息日");
        }
        try {
            JsonNode raw = json.readTree(preferences.find(memberId, ALTERNATE_WEEK_KEY));
            if (!raw.path("enabled").asBoolean(false)) return new Workday(false, null, "休息日");
            LocalDate anchor = LocalDate.parse(raw.path("anchorWeek").asText());
            String anchorType = raw.path("anchorType").asText();
            LocalDate targetWeek = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate anchorWeek = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long distance = ChronoUnit.WEEKS.between(anchorWeek, targetWeek);
            boolean alternate = Math.floorMod(distance, 2) == 1;
            boolean big = alternate ? "small".equals(anchorType) : "big".equals(anchorType);
            return new Workday(big, null, big ? "大周 · 周日上班" : "小周 · 双休");
        } catch (Exception ignored) {
            return new Workday(false, null, "休息日");
        }
    }

    private WorkdayRhythmDayEntity ensureDay(String memberId, LocalDate date) {
        WorkdayRhythmDayEntity value = days.find(memberId, date); if (value != null) return value;
        LocalDateTime now = LocalDateTime.now(ZONE); value = new WorkdayRhythmDayEntity();
        value.setMemberId(memberId); value.setRhythmDate(date); value.setCreatedAt(now); value.setUpdatedAt(now);
        try { days.insert(value); } catch (DuplicateKeyException ignored) { return days.find(memberId, date); }
        return value;
    }

    private List<WorkdayRhythmChecklistItemEntity> itemEntities(String memberId) {
        return items.selectList(new LambdaQueryWrapper<WorkdayRhythmChecklistItemEntity>()
                .eq(WorkdayRhythmChecklistItemEntity::getMemberId, memberId)
                .isNull(WorkdayRhythmChecklistItemEntity::getDeletedAt)
                .orderByAsc(WorkdayRhythmChecklistItemEntity::getOrd, WorkdayRhythmChecklistItemEntity::getCreatedAt));
    }

    private List<WorkdayRhythmChecklistOccurrenceEntity> occurrences(String memberId, LocalDate date) {
        return new ArrayList<>(occurrences.selectList(new LambdaQueryWrapper<WorkdayRhythmChecklistOccurrenceEntity>()
                .eq(WorkdayRhythmChecklistOccurrenceEntity::getMemberId, memberId)
                .eq(WorkdayRhythmChecklistOccurrenceEntity::getOccurrenceDate, date)));
    }

    private WorkdayRhythmChecklistOccurrenceEntity snapshot(WorkdayRhythmChecklistItemEntity item, LocalDate date) {
        WorkdayRhythmChecklistOccurrenceEntity value = new WorkdayRhythmChecklistOccurrenceEntity();
        value.setId(id("wro")); value.setItemId(item.getId()); value.setMemberId(item.getMemberId());
        value.setOccurrenceDate(date); copySnapshot(item, value);
        LocalDateTime now = LocalDateTime.now(ZONE); value.setCreatedAt(now); value.setUpdatedAt(now); return value;
    }

    private void copySnapshot(WorkdayRhythmChecklistItemEntity from, WorkdayRhythmChecklistOccurrenceEntity to) {
        boolean timerDefinitionChanged = !Objects.equals(itemType(from), itemType(to))
                || !Objects.equals(from.getDurationSeconds(), to.getDurationSeconds());
        to.setKind(from.getKind()); to.setCategory(from.getCategory()); to.setTitle(from.getTitle());
        to.setItemType(from.getItemType()); to.setDurationSeconds(from.getDurationSeconds());
        if (timerDefinitionChanged) { to.setTimerStartedAt(null); to.setTimerElapsedSeconds(0); }
        to.setStartTime(from.getStartTime()); to.setDueTime(from.getDueTime()); to.setDueNextDay(from.getDueNextDay());
        to.setReminderTime(from.getReminderTime()); to.setReminderNextDay(from.getReminderNextDay());
        to.setNotifyDirect(from.getNotifyDirect()); to.setNotifyChat(from.getNotifyChat()); to.setChatId(from.getChatId());
        to.setChatName(from.getChatName()); to.setNotifyWebhook(from.getNotifyWebhook());
        to.setWebhookId(from.getWebhookId()); to.setWebhookName(from.getWebhookName());
    }

    private void apply(WorkdayRhythmChecklistItemEntity value, SaveWorkdayRhythmChecklistItemRequest request) {
        String title = trim(request.title()); if (!StringUtils.hasText(title) || title.length() > 80) throw new BadRequestException("清单名称必填且最多 80 字");
        if (!CATEGORIES.contains(request.category())) throw new BadRequestException("请选择工作或生活分类");
        String itemType = StringUtils.hasText(request.itemType()) ? request.itemType() : "CHECK";
        if (!ITEM_TYPES.contains(itemType)) throw new BadRequestException("请选择普通打卡或倒计时类型");
        Integer durationSeconds = null;
        if ("COUNTDOWN".equals(itemType)) {
            if (request.durationMinutes() == null || request.durationMinutes() < 1 || request.durationMinutes() > 1440) {
                throw new BadRequestException("倒计时时长需要在 1 到 1440 分钟之间");
            }
            durationSeconds = request.durationMinutes() * 60;
        }
        if (!SCHEDULES.contains(request.scheduleType())) throw new BadRequestException("清单适用日规则不正确");
        List<Integer> weekdays = request.weekdays() == null ? List.of() : request.weekdays().stream().distinct().sorted().toList();
        if ("WEEKDAYS".equals(request.scheduleType()) && (weekdays.isEmpty() || weekdays.stream().anyMatch(day -> day < 1 || day > 7))) throw new BadRequestException("请至少选择一个适用星期");
        LocalTime start = time(request.startTime(), "开始时间"); LocalTime due = time(request.dueTime(), "截止时间");
        boolean anyNotify = request.notifyDirect() || request.notifyChat() || request.notifyWebhook();
        LocalTime reminder = StringUtils.hasText(request.reminderTime()) ? time(request.reminderTime(), "提醒时间") : null;
        if (anyNotify && reminder == null) throw new BadRequestException("开启外部提醒后请选择提醒时间");
        if (request.notifyChat() && !StringUtils.hasText(request.chatId())) throw new BadRequestException("请选择飞书群聊");
        if (request.notifyWebhook() && !StringUtils.hasText(request.webhookId())) throw new BadRequestException("请选择 Webhook 配置");
        value.setCategory(request.category()); value.setTitle(title); value.setItemType(itemType);
        value.setDurationSeconds(durationSeconds); value.setScheduleType(request.scheduleType());
        value.setWeekdaysJson(write(weekdays)); value.setStartTime(start); value.setDueTime(due); value.setDueNextDay(request.dueNextDay());
        value.setActive(request.active()); value.setOrd(Math.max(0, Math.min(request.order(), 999)));
        value.setNotifyDirect(request.notifyDirect()); value.setNotifyChat(request.notifyChat()); value.setChatId(blank(request.chatId())); value.setChatName(blank(request.chatName()));
        value.setNotifyWebhook(request.notifyWebhook()); value.setWebhookId(blank(request.webhookId())); value.setWebhookName(blank(request.webhookName()));
        value.setReminderTime(reminder); value.setReminderNextDay(request.reminderNextDay()); value.setUpdatedAt(LocalDateTime.now(ZONE));
    }

    private WorkdayRhythmDashboardDto.Occurrence occurrenceDto(WorkdayRhythmChecklistOccurrenceEntity value, LocalDateTime now) {
        int remaining = timerRemaining(value, now);
        String status = value.getCompletedAt() != null ? (Boolean.TRUE.equals(value.getCompletedOnTime()) ? "ON_TIME" : "COMPLETED_LATE")
                : now.isAfter(dueAt(value)) ? "LATE" : now.isBefore(startAt(value)) ? "UPCOMING" : "DUE";
        return new WorkdayRhythmDashboardDto.Occurrence(value.getId(), value.getItemId(), value.getOccurrenceDate(),
                value.getKind(), value.getCategory(), value.getTitle(), itemType(value), value.getDurationSeconds(),
                "COUNTDOWN".equals(itemType(value)) ? remaining : null, value.getTimerStartedAt() != null,
                minute(value.getStartTime()), minute(value.getDueTime()),
                Boolean.TRUE.equals(value.getDueNextDay()), value.getCompletedAt() == null ? null : value.getCompletedAt().toString(),
                value.getCompletedOnTime(), value.getCompletionSource(), status);
    }

    private WorkdayRhythmChecklistItemDto itemDto(WorkdayRhythmChecklistItemEntity value) {
        return new WorkdayRhythmChecklistItemDto(value.getId(), value.getKind(), value.getCategory(), value.getTitle(),
                itemType(value), value.getDurationSeconds() == null ? null : value.getDurationSeconds() / 60,
                value.getScheduleType(), weekdays(value.getWeekdaysJson()), minute(value.getStartTime()), minute(value.getDueTime()),
                Boolean.TRUE.equals(value.getDueNextDay()), Boolean.TRUE.equals(value.getActive()), value.getOrd() == null ? 0 : value.getOrd(),
                Boolean.TRUE.equals(value.getNotifyDirect()), Boolean.TRUE.equals(value.getNotifyChat()), value.getChatId(), value.getChatName(),
                Boolean.TRUE.equals(value.getNotifyWebhook()), value.getWebhookId(), value.getWebhookName(), minute(value.getReminderTime()),
                Boolean.TRUE.equals(value.getReminderNextDay()));
    }

    private WorkdayRhythmDashboardDto.Settings settingDto(WorkdayRhythmSettingEntity value) {
        return new WorkdayRhythmDashboardDto.Settings(Boolean.TRUE.equals(value.getFollowAlternateWeek()), value.getDocumentKbId(),
                value.getDocumentFolderId(), value.getDocumentTemplateId(), value.getDocumentTitlePattern(),
                Boolean.TRUE.equals(value.getTianrenEnabled()), Boolean.TRUE.equals(value.getTianrenRequired()));
    }

    private WorkdayRhythmDashboardDto.Document document(String memberId, LocalDate date, WorkdayRhythmSettingEntity setting) {
        WorkdayRhythmDayEntity day = days.find(memberId, date);
        if (day == null || !StringUtils.hasText(day.getDocumentId())) return new WorkdayRhythmDashboardDto.Document(null,
                StringUtils.hasText(setting.getDocumentKbId()) && StringUtils.hasText(setting.getDocumentFolderId()) ? "READY" : "UNCONFIGURED",
                setting.getDocumentKbId(), setting.getDocumentFolderId(), documentTitle(setting, date));
        DocEntity doc = docs.selectById(day.getDocumentId());
        if (doc == null) return new WorkdayRhythmDashboardDto.Document(day.getDocumentId(), "MISSING", null, null, null);
        return new WorkdayRhythmDashboardDto.Document(doc.getId(), doc.getDeletedAt() == null ? "CREATED" : "TRASHED",
                doc.getKbId(), doc.getFolderId(), doc.getTitle());
    }

    private ObjectNode readDay(String memberId, LocalDate date) {
        try {
            String raw = preferences.find(memberId, DAY_KEY_PREFIX + date);
            if (StringUtils.hasText(raw) && json.readTree(raw) instanceof ObjectNode object) return object;
        } catch (Exception ignored) { }
        ObjectNode value = json.createObjectNode(); value.put("version", 1); value.put("date", date.toString());
        value.put("workdayActive", workday(memberId, date, ensureSetting(memberId)).active());
        value.putArray("completed"); value.put("waterCount", 0); value.put("meditated", false);
        value.putObject("energyRecords"); value.putObject("energySnapshot"); value.putArray("focus"); return value;
    }

    private void syncLegacyWorkday(String memberId, LocalDate date, boolean active) {
        ObjectNode value = readDay(memberId, date); value.put("workdayActive", active);
        preferences.upsert(memberId, DAY_KEY_PREFIX + date, write(value));
    }

    private LocalDateTime startAt(WorkdayRhythmChecklistOccurrenceEntity value) { return value.getOccurrenceDate().atTime(value.getStartTime()); }
    private LocalDateTime dueAt(WorkdayRhythmChecklistOccurrenceEntity value) { return value.getOccurrenceDate().plusDays(Boolean.TRUE.equals(value.getDueNextDay()) ? 1 : 0).atTime(value.getDueTime()); }
    private String documentTitle(WorkdayRhythmSettingEntity value, LocalDate date) {
        String pattern = StringUtils.hasText(value.getDocumentTitlePattern()) ? value.getDocumentTitlePattern() : "{date} 每日工作记录";
        return pattern.replace("{date}", date.toString()).replace("{weekday}", "星期" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1));
    }
    private String stableDocumentId(String memberId, LocalDate date) { return "r" + UUID.nameUUIDFromBytes((memberId + ':' + date).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 31); }
    private WorkdayRhythmChecklistItemEntity ownedItem(String memberId, String id) { WorkdayRhythmChecklistItemEntity value = items.findOwned(id, memberId); if (value == null) throw new NotFoundException("清单项不存在或已删除"); return value; }
    private String checkedFocus(String value) { String result = trim(value); if (result.length() > 100) throw new BadRequestException("每个今日重点最多 100 字"); return result; }
    private void requireDate(LocalDate date) { if (date == null || Math.abs(ChronoUnit.DAYS.between(LocalDate.now(ZONE), date)) > 366) throw new BadRequestException("只能读写前后 366 天内的今日节奏"); }
    private LocalTime time(String value, String label) { try { return LocalTime.parse(value); } catch (Exception ex) { throw new BadRequestException("请填写有效的" + label); } }
    private List<Integer> weekdays(String raw) { try { return json.readValue(raw, new TypeReference<List<Integer>>() { }); } catch (Exception ignored) { return List.of(); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("今日节奏数据序列化失败", ex); } }
    private String minute(LocalTime value) { return value == null ? null : value.toString().substring(0, 5); }
    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String blank(String value) { String result = trim(value); return result.isEmpty() ? null : result; }
    private String itemType(WorkdayRhythmChecklistItemEntity value) { return StringUtils.hasText(value.getItemType()) ? value.getItemType() : "CHECK"; }
    private String itemType(WorkdayRhythmChecklistOccurrenceEntity value) { return StringUtils.hasText(value.getItemType()) ? value.getItemType() : "CHECK"; }
    private int timerRemaining(WorkdayRhythmChecklistOccurrenceEntity value, LocalDateTime now) {
        int duration = value.getDurationSeconds() == null ? 0 : value.getDurationSeconds();
        int elapsed = value.getTimerElapsedSeconds() == null ? 0 : value.getTimerElapsedSeconds();
        if (value.getTimerStartedAt() != null) elapsed += Math.max(0, (int) ChronoUnit.SECONDS.between(value.getTimerStartedAt(), now));
        return Math.max(0, duration - elapsed);
    }
    private void pauseTimer(WorkdayRhythmChecklistOccurrenceEntity value, LocalDateTime now) {
        if (value.getTimerStartedAt() == null) return;
        int elapsed = value.getTimerElapsedSeconds() == null ? 0 : value.getTimerElapsedSeconds();
        elapsed += Math.max(0, (int) ChronoUnit.SECONDS.between(value.getTimerStartedAt(), now));
        value.setTimerElapsedSeconds(Math.min(elapsed, value.getDurationSeconds()));
        value.setTimerStartedAt(null);
    }
    private void refreshCountdown(WorkdayRhythmChecklistOccurrenceEntity value, LocalDateTime now) {
        if (!"COUNTDOWN".equals(itemType(value)) || value.getCompletedAt() != null || timerRemaining(value, now) > 0) return;
        pauseTimer(value, now); value.setCompletedAt(now); value.setCompletedOnTime(!now.isAfter(dueAt(value)));
        value.setCompletionSource("SYSTEM");
        value.setUpdatedAt(now); occurrences.updateById(value);
    }
    private String id(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 32 - prefix.length()); }
    private record Workday(boolean active, Boolean override, String label) { }
    public record WorkdayInfo(boolean active, String label) { }
}
