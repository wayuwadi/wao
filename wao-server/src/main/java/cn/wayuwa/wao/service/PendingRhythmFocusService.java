package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.PendingRhythmFocusDto;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 从成员原始日存档接续未完成重点；不创建重复的今日事项。 */
@Service
public class PendingRhythmFocusService {
    static final String PREFIX = "workday-rhythm-day-";
    private final MemberPreferenceMapper preferences;
    private final ObjectMapper json;

    public PendingRhythmFocusService(MemberPreferenceMapper preferences, ObjectMapper json) {
        this.preferences = preferences;
        this.json = json;
    }

    /** 读取该成员今日之前所有存档的未完成重点，包括间隔数日未打开的事项。 */
    public PendingRhythmFocusDto pending(String memberId, LocalDate today) {
        List<PendingRhythmFocusDto.Item> items = new ArrayList<>();
        int skipped = 0;
        for (var entry : preferences.findRange(memberId, PREFIX, PREFIX + today.minusDays(1))) {
            try {
                LocalDate date = LocalDate.parse(entry.getPrefKey().substring(PREFIX.length()));
                if (!date.isBefore(today)) continue;
                ObjectNode day = read(entry.getPrefValue(), date);
                for (int index = 0; index < day.path("focus").size(); index++) {
                    String title = day.path("focus").get(index).textValue();
                    if (!title.isBlank() && !completed(day, index)) {
                        items.add(new PendingRhythmFocusDto.Item(date, index, title));
                    }
                }
            } catch (RuntimeException error) {
                skipped++;
            }
        }
        return new PendingRhythmFocusDto(today, List.copyOf(items), skipped);
    }

    /** 只更新原存档中指定重点的完成状态，保留补给、计划等其他字段并检测并发修改。 */
    public void complete(String memberId, LocalDate date, LocalDate today, int index,
            String expectedTitle, boolean completed) {
        if (!date.isBefore(today) || index < 0 || index >= 20) {
            throw new BadRequestException("只能更新今日之前的有效重点");
        }
        String key = PREFIX + date;
        String raw = preferences.find(memberId, key);
        ObjectNode day = read(raw, date);
        JsonNode item = day.path("focus").path(index);
        if (!item.isTextual() || item.asText().isBlank() || !item.asText().equals(expectedTitle)) {
            throw new ConflictException("原重点已变化，请刷新未完成事项后重试");
        }
        if (completed(day, index) == completed) return;
        ArrayNode states = json.createArrayNode();
        for (int i = 0; i < day.path("focus").size(); i++) states.add(i == index ? completed : completed(day, i));
        day.set("focusCompleted", states);
        if (preferences.compareAndSet(memberId, key, raw, day.toString()) != 1) {
            throw new ConflictException("原记录刚刚被修改，请刷新未完成事项后重试");
        }
    }

    private boolean completed(ObjectNode day, int index) {
        JsonNode state = day.path("focusCompleted").path(index);
        return state.isBoolean() && state.booleanValue();
    }

    private ObjectNode read(String raw, LocalDate date) {
        try {
            JsonNode day = json.readTree(raw);
            if (!(day instanceof ObjectNode object) || !day.path("version").isIntegralNumber()
                    || day.path("version").intValue() != 1 || !date.toString().equals(day.path("date").asText())
                    || !day.path("focus").isArray() || day.path("focus").size() > 20) {
                throw new IllegalArgumentException();
            }
            for (JsonNode title : day.path("focus")) {
                if (!title.isTextual() || title.textValue().length() > 100) throw new IllegalArgumentException();
            }
            JsonNode states = day.path("focusCompleted");
            if (!states.isMissingNode()) {
                if (!states.isArray()) throw new IllegalArgumentException();
                for (JsonNode state : states) if (!state.isBoolean()) throw new IllegalArgumentException();
            }
            return object;
        } catch (Exception error) {
            throw new BadRequestException("原重点存档不存在或无法读取，请刷新后重试");
        }
    }
}
