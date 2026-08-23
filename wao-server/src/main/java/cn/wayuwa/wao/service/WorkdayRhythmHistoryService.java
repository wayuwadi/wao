package cn.wayuwa.wao.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.MemberPreferenceEntity;
import cn.wayuwa.wao.dto.WorkdayRhythmHistoryDayDto;
import cn.wayuwa.wao.dto.WorkdayRhythmHistoryDto;
import cn.wayuwa.wao.dto.WorkdayRhythmHistoryEnergyDto;
import cn.wayuwa.wao.dto.WorkdayRhythmHistoryChecklistDto;
import cn.wayuwa.wao.domain.WorkdayRhythmChecklistOccurrenceEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmDayEntity;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistOccurrenceMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmDayMapper;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 从当前成员已经落库的每日偏好中生成有界的今日节奏历史视图。 */
@Service
public class WorkdayRhythmHistoryService {

    static final String CONFIG_KEY = "workday-rhythm-config-v1";
    static final String DAY_KEY_PREFIX = "workday-rhythm-day-";
    private static final int MAX_RANGE_DAYS = 366;

    private final MemberPreferenceMapper preferences;
    private final ObjectMapper json;
    private final WorkdayRhythmChecklistOccurrenceMapper occurrences;
    private final WorkdayRhythmDayMapper days;
    private final DocMapper docs;

    public WorkdayRhythmHistoryService(MemberPreferenceMapper preferences, ObjectMapper json,
            WorkdayRhythmChecklistOccurrenceMapper occurrences, WorkdayRhythmDayMapper days, DocMapper docs) {
        this.preferences = preferences;
        this.json = json;
        this.occurrences = occurrences;
        this.days = days;
        this.docs = docs;
    }

    /**
     * 查询当前成员指定日期范围内已经保存的每日目标与精力项目执行次数。
     *
     * @param memberId 当前登录成员 id
     * @param from 起始日期（含）
     * @param to 结束日期（含）
     * @return 按日期倒序排列的真实存档；损坏条目只计数、不伪造成空记录
     */
    public WorkdayRhythmHistoryDto history(String memberId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        Map<String, EnergyMeta> currentItems = currentEnergyItems(memberId);
        String fromKey = DAY_KEY_PREFIX + from;
        String toKey = DAY_KEY_PREFIX + to;
        List<WorkdayRhythmHistoryDayDto> items = new ArrayList<>();
        Map<LocalDate, List<WorkdayRhythmChecklistOccurrenceEntity>> checklistByDate = new LinkedHashMap<>();
        occurrences.selectList(new LambdaQueryWrapper<WorkdayRhythmChecklistOccurrenceEntity>()
                        .eq(WorkdayRhythmChecklistOccurrenceEntity::getMemberId, memberId)
                        .between(WorkdayRhythmChecklistOccurrenceEntity::getOccurrenceDate, from, to)
                        .orderByAsc(WorkdayRhythmChecklistOccurrenceEntity::getCreatedAt))
                .forEach(value -> checklistByDate.computeIfAbsent(value.getOccurrenceDate(), ignored -> new ArrayList<>()).add(value));
        Map<LocalDate, WorkdayRhythmDayEntity> rhythmDays = new LinkedHashMap<>();
        days.findRange(memberId, from, to).forEach(value -> rhythmDays.put(value.getRhythmDate(), value));
        Set<LocalDate> materializedDates = new LinkedHashSet<>();
        int skipped = 0;
        for (MemberPreferenceEntity entry : preferences.findRange(memberId, fromKey, toKey)) {
            try {
                LocalDate date = LocalDate.parse(entry.getPrefKey().substring(DAY_KEY_PREFIX.length()));
                JsonNode day = json.readTree(entry.getPrefValue());
                if (!validDay(date, day)) {
                    skipped++;
                    continue;
                }
                items.add(toDto(date, day, currentItems, checklistByDate.getOrDefault(date, List.of()), rhythmDays.get(date)));
                materializedDates.add(date);
            } catch (Exception ex) {
                skipped++;
            }
        }
        Set<LocalDate> relationalDates = new LinkedHashSet<>(checklistByDate.keySet());
        relationalDates.addAll(rhythmDays.keySet());
        relationalDates.stream().filter(date -> !materializedDates.contains(date)).forEach(date ->
                items.add(toRelationalDto(date, checklistByDate.getOrDefault(date, List.of()), rhythmDays.get(date))));
        items.sort((left, right) -> right.date().compareTo(left.date()));
        return new WorkdayRhythmHistoryDto(from, to, List.copyOf(items), skipped);
    }

    private boolean validDay(LocalDate date, JsonNode day) {
        if (day == null || !day.isObject()
                || !day.path("version").isIntegralNumber() || day.path("version").asInt() != 1
                || !day.path("date").isTextual() || !date.toString().equals(day.path("date").asText())
                || !day.path("workdayActive").isBoolean() || !validFocus(day.path("focus"))) {
            return false;
        }
        JsonNode records = day.path("energyRecords");
        boolean recordsValid = records.isObject() && validEnergyRecords(records);
        boolean legacyValid = day.path("waterCount").isIntegralNumber()
                && countInRange(day.path("waterCount").asInt()) && day.path("meditated").isBoolean();
        if (!recordsValid && !legacyValid) return false;
        JsonNode snapshot = day.path("energySnapshot");
        return snapshot.isMissingNode() || (snapshot.isObject() && validEnergySnapshot(snapshot));
    }

    private boolean validFocus(JsonNode focus) {
        if (!focus.isArray() || focus.size() > 3) return false;
        for (JsonNode value : focus) {
            if (!value.isTextual() || value.asText().length() > 100) return false;
        }
        return true;
    }

    private boolean validEnergyRecords(JsonNode records) {
        var fields = records.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getKey().matches("^[A-Za-z0-9_-]{1,40}$")
                    || !field.getValue().isIntegralNumber()
                    || !countInRange(field.getValue().asInt())) return false;
        }
        return true;
    }

    private boolean validEnergySnapshot(JsonNode snapshot) {
        var fields = snapshot.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode value = field.getValue();
            String title = value.path("title").asText("").trim();
            String mode = value.path("mode").asText("");
            if (!field.getKey().matches("^[A-Za-z0-9_-]{1,40}$") || !value.isObject()
                    || !StringUtils.hasText(title) || title.length() > 20
                    || !("COUNT".equals(mode) || "DAILY_ONCE".equals(mode))) return false;
        }
        return true;
    }

    private boolean countInRange(int value) {
        return value >= 0 && value <= 99;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BadRequestException("请选择正确的历史起止日期");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new BadRequestException("单次最多查询 366 天今日节奏历史");
        }
    }

    private WorkdayRhythmHistoryDayDto toDto(LocalDate date, JsonNode day,
            Map<String, EnergyMeta> currentItems, List<WorkdayRhythmChecklistOccurrenceEntity> checklist,
            WorkdayRhythmDayEntity rhythmDay) {
        List<String> focus = new ArrayList<>();
        if (day.path("focus").isArray()) {
            for (JsonNode value : day.path("focus")) {
                String text = value.asText("").trim();
                if (StringUtils.hasText(text) && focus.size() < 3) {
                    focus.add(text.substring(0, Math.min(text.length(), 100)));
                }
            }
        }

        Map<String, Integer> counts = persistedCounts(day);
        Map<String, EnergyMeta> snapshots = snapshots(day);
        // 项目集合必须来自当天快照或当天已保存的计数键；当前配置只用来给旧记录补名称。
        // 否则后来新增的项目会被补成历史“0 次”，看起来像一条其实不存在的执行记录。
        Set<String> ids = new LinkedHashSet<>(snapshots.keySet());
        ids.addAll(counts.keySet());
        List<WorkdayRhythmHistoryEnergyDto> energyItems = ids.stream().map(id -> {
            EnergyMeta meta = snapshots.getOrDefault(id,
                    currentItems.getOrDefault(id, fallbackMeta(id)));
            return new WorkdayRhythmHistoryEnergyDto(id, meta.title(), meta.mode(),
                    counts.getOrDefault(id, 0));
        }).toList();
        List<WorkdayRhythmHistoryChecklistDto> checklistDto = checklist.stream().map(value ->
                new WorkdayRhythmHistoryChecklistDto(value.getTitle(), value.getCategory(),
                        value.getCompletedAt() == null ? null : value.getCompletedAt().toString(),
                        value.getCompletedOnTime())).toList();
        DocEntity document = rhythmDay == null || !StringUtils.hasText(rhythmDay.getDocumentId())
                ? null : docs.selectById(rhythmDay.getDocumentId());
        return new WorkdayRhythmHistoryDayDto(date, day.path("workdayActive").asBoolean(false),
                List.copyOf(focus), energyItems, checklistDto, document == null ? null : document.getId(),
                document == null ? null : document.getKbId(), document == null ? null : document.getTitle());
    }

    private WorkdayRhythmHistoryDayDto toRelationalDto(LocalDate date,
            List<WorkdayRhythmChecklistOccurrenceEntity> checklist, WorkdayRhythmDayEntity rhythmDay) {
        List<WorkdayRhythmHistoryChecklistDto> checklistDto = checklist.stream().map(value ->
                new WorkdayRhythmHistoryChecklistDto(value.getTitle(), value.getCategory(),
                        value.getCompletedAt() == null ? null : value.getCompletedAt().toString(),
                        value.getCompletedOnTime())).toList();
        DocEntity document = rhythmDay == null || !StringUtils.hasText(rhythmDay.getDocumentId())
                ? null : docs.selectById(rhythmDay.getDocumentId());
        boolean workdayActive = rhythmDay != null && rhythmDay.getWorkdayOverride() != null
                ? rhythmDay.getWorkdayOverride()
                : checklist.stream().anyMatch(value -> "WORK".equals(value.getCategory()));
        return new WorkdayRhythmHistoryDayDto(date, workdayActive, List.of(), List.of(), checklistDto,
                document == null ? null : document.getId(), document == null ? null : document.getKbId(),
                document == null ? null : document.getTitle());
    }

    private Map<String, EnergyMeta> currentEnergyItems(String memberId) {
        LinkedHashMap<String, EnergyMeta> result = new LinkedHashMap<>();
        try {
            String raw = preferences.find(memberId, CONFIG_KEY);
            if (StringUtils.hasText(raw)) {
                JsonNode items = json.readTree(raw).path("energyItems");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        addMeta(result, item.path("id").asText(), item.path("title").asText(),
                                item.path("mode").asText());
                    }
                }
            }
        } catch (Exception ignored) {
            // 损坏配置不应阻断历史；仍可使用每日快照和兼容默认项展示真实次数。
        }
        if (result.isEmpty()) {
            result.put("WATER", new EnergyMeta("喝水", "COUNT"));
            result.put("MEDITATION", new EnergyMeta("至少冥想一次", "DAILY_ONCE"));
        }
        return result;
    }

    private Map<String, EnergyMeta> snapshots(JsonNode day) {
        LinkedHashMap<String, EnergyMeta> result = new LinkedHashMap<>();
        JsonNode snapshot = day.path("energySnapshot");
        if (snapshot.isObject()) {
            snapshot.fields().forEachRemaining(field -> addMeta(result, field.getKey(),
                    field.getValue().path("title").asText(), field.getValue().path("mode").asText()));
        }
        return result;
    }

    private void addMeta(Map<String, EnergyMeta> target, String id, String title, String mode) {
        if (id.matches("^[A-Za-z0-9_-]{1,40}$") && StringUtils.hasText(title)
                && title.trim().length() <= 20 && ("COUNT".equals(mode) || "DAILY_ONCE".equals(mode))) {
            target.put(id, new EnergyMeta(title.trim(), mode));
        }
    }

    private Map<String, Integer> persistedCounts(JsonNode day) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        JsonNode records = day.path("energyRecords");
        if (records.isObject()) {
            records.fields().forEachRemaining(field -> {
                if (field.getKey().matches("^[A-Za-z0-9_-]{1,40}$") && field.getValue().isIntegralNumber()) {
                    result.put(field.getKey(), clamp(field.getValue().asInt()));
                }
            });
        }
        if (day.has("waterCount")) {
            result.putIfAbsent("WATER", clamp(day.path("waterCount").asInt(0)));
        }
        if (day.has("meditated")) {
            result.putIfAbsent("MEDITATION", day.path("meditated").asBoolean(false) ? 1 : 0);
        }
        return result;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(value, 99));
    }

    private EnergyMeta fallbackMeta(String id) {
        if ("WATER".equals(id)) return new EnergyMeta("喝水", "COUNT");
        if ("MEDITATION".equals(id)) return new EnergyMeta("至少冥想一次", "DAILY_ONCE");
        // 没有当天快照时无法知道已删除项目原先的点击机制；按次数展示，不把 1 次猜成“每天一次”。
        return new EnergyMeta("已移除项目", "COUNT");
    }

    private record EnergyMeta(String title, String mode) { }
}
