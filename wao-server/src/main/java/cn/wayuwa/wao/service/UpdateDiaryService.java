package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.ProductUpdateRow;
import cn.wayuwa.wao.dto.FunctionIterationInput;
import cn.wayuwa.wao.dto.UpdateDiaryPageDto;
import cn.wayuwa.wao.mapper.ProductUpdateMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 从已验收成功的功能提交生成面向用户的 WAO 更新日记。 */
@Service
public class UpdateDiaryService {
    private static final Pattern CONVENTIONAL_PREFIX =
            Pattern.compile("^(feat|fix|perf)\\(([^)]+)\\)!?:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGE_SUFFIX = Pattern.compile("\\s*\\(#\\d+\\)$");
    private static final Set<String> ENGINEERING_SCOPES = Set.of(
            "backup", "build", "ci", "deploy", "dev", "docs", "jenkins", "redis", "test");

    private final ProductUpdateMapper updates;

    public UpdateDiaryService(ProductUpdateMapper updates) {
        this.updates = updates;
    }

    /** 保存本次成功运行的具体 Git 产品提交；工程维护、测试和文档提交不会成为用户日记。 */
    public void recordVerifiedCommits(String runId, List<FunctionIterationInput> inputs) {
        if (inputs == null) return;
        for (FunctionIterationInput input : inputs.stream().limit(50).toList()) {
            if (input == null || !StringUtils.hasText(input.commitSha())
                    || !StringUtils.hasText(input.subject())) continue;
            var match = CONVENTIONAL_PREFIX.matcher(input.subject().trim());
            if (!match.find()) continue;
            String scope = match.group(2).trim().toLowerCase(java.util.Locale.ROOT);
            if (!StringUtils.hasText(scope) || ENGINEERING_SCOPES.contains(scope)) continue;
            LocalDateTime committedAt = committedAt(input.committedAt());
            if (committedAt == null) continue;
            updates.insertIgnore(runId, limit(input.commitSha().trim(), 64), limit(scope, 64),
                    limit(input.subject().trim(), 255), committedAt);
        }
    }

    /** 按有更新的日期倒序分页；只公开功能、新增、修复和性能结果，不返回任务或运行详情。 */
    public UpdateDiaryPageDto page(int requestedPage, int requestedSize) {
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(31, requestedSize));
        long totalDays = updates.countDays();
        List<LocalDate> dates = updates.selectDays((long) (page - 1) * size, size);
        if (dates.isEmpty()) return new UpdateDiaryPageDto(List.of(), totalDays, page, size);

        Map<LocalDate, List<UpdateDiaryPageDto.Entry>> grouped = new LinkedHashMap<>();
        for (LocalDate date : dates) grouped.put(date, new java.util.ArrayList<>());
        List<ProductUpdateRow> rows = updates.selectBetween(
                dates.get(dates.size() - 1), dates.get(0));
        for (ProductUpdateRow row : rows) {
            LocalDate date = row.getCommittedAt().toLocalDate();
            List<UpdateDiaryPageDto.Entry> entries = grouped.get(date);
            if (entries != null) entries.add(entry(row));
        }
        List<UpdateDiaryPageDto.Day> days = grouped.entrySet().stream()
                .filter(item -> !item.getValue().isEmpty())
                .map(item -> new UpdateDiaryPageDto.Day(item.getKey(), List.copyOf(item.getValue())))
                .toList();
        return new UpdateDiaryPageDto(days, totalDays, page, size);
    }

    private UpdateDiaryPageDto.Entry entry(ProductUpdateRow row) {
        String subject = row.getCommitSubject() == null ? "" : row.getCommitSubject().trim();
        var match = CONVENTIONAL_PREFIX.matcher(subject);
        boolean conventional = match.find();
        String kind = conventional ? switch (match.group(1).toLowerCase()) {
            case "fix" -> "FIX";
            case "perf" -> "PERFORMANCE";
            default -> "FEATURE";
        } : "FEATURE";
        String title = MERGE_SUFFIX.matcher(conventional ? subject.substring(match.end()).trim() : subject)
                .replaceFirst("").trim();
        return new UpdateDiaryPageDto.Entry(row.getScope(), kind,
                title.isEmpty() ? "完成一项功能更新" : title);
    }

    private LocalDateTime committedAt(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return OffsetDateTime.parse(value.trim()).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException exception) {
            return null;
        }
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
