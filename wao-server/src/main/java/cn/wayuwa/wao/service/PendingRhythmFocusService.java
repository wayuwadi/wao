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

/** 只提醒成员昨日三个目标中的未完成项，不聚合历史或个人任务。 */
@Service
public class PendingRhythmFocusService {
    static final String PREFIX = "workday-rhythm-day-";
    private final MemberPreferenceMapper preferences;
    private final ObjectMapper json;

    public PendingRhythmFocusService(MemberPreferenceMapper preferences, ObjectMapper json) {
        this.preferences = preferences;
        this.json = json;
    }

    /** 按上海自然日读取昨日三个目标中的未完成项；更早存档不进入今日提醒。 */
    public PendingRhythmFocusDto pending(String memberId, LocalDate today) {
        List<PendingRhythmFocusDto.Item> items = new ArrayList<>();
        LocalDate date = today.minusDays(1);
        String raw = preferences.find(memberId, PREFIX + date);
        if (raw == null) return new PendingRhythmFocusDto(today, List.of(), 0);
        try {
            ObjectNode day = read(raw, date);
            for (int index = 0; index < Math.min(3, day.path("focus").size()); index++) {
                String title = day.path("focus").get(index).textValue();
                if (!title.isBlank() && !completed(day, index)) {
                    items.add(new PendingRhythmFocusDto.Item(date, index, title));
                }
            }
        } catch (RuntimeException error) {
            return new PendingRhythmFocusDto(today, List.of(), 1);
        }
        return new PendingRhythmFocusDto(today, List.copyOf(items), 0);
    }

    /** 只更新原存档中指定重点的完成状态，保留补给、计划等其他字段并检测并发修改。 */
    public void complete(String memberId, LocalDate date, LocalDate today, int index,
            String expectedTitle, boolean completed) {
        if (!date.equals(today.minusDays(1)) || index < 0 || index >= 3) {
            throw new BadRequestException("只能更新昨日三个目标中的有效重点，请刷新后重试");
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
