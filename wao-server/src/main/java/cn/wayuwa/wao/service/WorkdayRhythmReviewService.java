package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.WorkdayRhythmReviewDto;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/** 独立保存每日复盘，避免计划及宠物补给的整日快照覆盖复盘。 */
@Service
public class WorkdayRhythmReviewService {
    public static final String PREFIX = "workday-rhythm-review-";
    private final MemberPreferenceMapper preferences;
    private final ObjectMapper json;

    public WorkdayRhythmReviewService(MemberPreferenceMapper preferences, ObjectMapper json) {
        this.preferences = preferences;
        this.json = json;
    }

    /** 读取本人指定日期的复盘；不存在时返回空记录，不创建存档。 */
    public WorkdayRhythmReviewDto get(String memberId, LocalDate date) {
        validateDate(date);
        String raw = preferences.find(memberId, PREFIX + date);
        return raw == null ? new WorkdayRhythmReviewDto(0, List.of()) : parse(raw, json);
    }

    /** 按读取版本保存本人复盘；并发变化拒绝覆盖，相同内容重试保持幂等。 */
    public WorkdayRhythmReviewDto save(String memberId, LocalDate date, WorkdayRhythmReviewDto request) {
        validateDate(date);
        validate(request);
        String key = PREFIX + date;
        String raw = preferences.find(memberId, key);
        WorkdayRhythmReviewDto current = raw == null ? new WorkdayRhythmReviewDto(0, List.of()) : parse(raw, json);
        if (current.entries().equals(request.entries())) return current;
        if (current.revision() != request.revision()) throw conflict();
        var next = new WorkdayRhythmReviewDto(current.revision() + 1, List.copyOf(request.entries()));
        String value;
        try { value = json.writeValueAsString(next); }
        catch (Exception error) { throw new IllegalStateException("复盘序列化失败", error); }
        int changed = raw == null ? preferences.insertIfAbsent(memberId, key, value)
                : preferences.compareAndSet(memberId, key, raw, value);
        if (changed != 1) throw conflict();
        return next;
    }

    static WorkdayRhythmReviewDto parse(String raw, ObjectMapper json) {
        try {
            var node = json.readTree(raw);
            if (node == null || !node.isObject() || !node.path("revision").isIntegralNumber()
                    || node.path("revision").asLong() < 1 || !node.path("entries").isArray()) throw new IllegalArgumentException();
            for (var entry : node.path("entries")) if (!entry.isTextual()) throw new IllegalArgumentException();
            var result = json.treeToValue(node, WorkdayRhythmReviewDto.class);
            validate(result);
            return result;
        } catch (Exception error) { throw new BadRequestException("复盘存档无法读取，请保留草稿并联系维护人员"); }
    }

    private static void validate(WorkdayRhythmReviewDto value) {
        if (value == null || value.revision() < 0 || value.revision() == Long.MAX_VALUE
                || value.entries() == null || value.entries().size() > 20
                || value.entries().stream().anyMatch(entry -> entry == null || entry.length() > 1000)) {
            throw new BadRequestException("复盘最多 20 条，每条最多 1000 字，版本必须有效");
        }
    }

    private void validateDate(LocalDate date) {
        if (date == null || date.getYear() < 2000 || date.getYear() > 2100) throw new BadRequestException("请选择有效的复盘日期");
    }

    private ConflictException conflict() {
        return new ConflictException("复盘已在其他页面更新，请读取最新版本并核对后保存，当前草稿仍保留");
    }
}
