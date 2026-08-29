package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.FoodWorkspaceEntity;
import cn.wayuwa.wao.dto.FoodWorkspaceDto;
import cn.wayuwa.wao.dto.FoodWorkspaceDto.FoodProfile;
import cn.wayuwa.wao.dto.FoodWorkspaceDto.MealLog;
import cn.wayuwa.wao.dto.FoodWorkspaceDto.MealTemplate;
import cn.wayuwa.wao.dto.FoodWorkspaceDto.PreparationTask;
import cn.wayuwa.wao.mapper.FoodWorkspaceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 成员私有饮食模板、真实用餐记录与低风险自我观察。 */
@Service
public class FoodService {
    public static final String DEFAULT_PRINCIPLES =
            "饮食有节，按自己的日常节律安排三餐；少做临时决定，吃后如实记录，再用长期观察调整。";
    private static final int MAX_JSON_LENGTH = 2_000_000;
    private static final Set<String> DAY_TYPES = Set.of("WORKDAY", "RESTDAY");
    private static final Set<String> MEAL_TYPES = Set.of("BREAKFAST", "LUNCH", "DINNER");
    private static final Set<String> COMFORTS = Set.of("COMFORTABLE", "NEUTRAL", "UNCOMFORTABLE");
    private static final Set<String> TASK_TYPES = Set.of("GROCERY", "PREP");
    private static final Set<String> TASK_STATUSES = Set.of("OPEN", "DONE");

    private final FoodWorkspaceMapper workspaces;
    private final ObjectMapper json;

    public FoodService(FoodWorkspaceMapper workspaces, ObjectMapper json) {
        this.workspaces = workspaces;
        this.json = json;
    }

    /** 读取当前成员工作区；首次使用返回空业务集合，不生成示例餐单。 */
    public FoodWorkspaceDto workspace(String memberId) {
        requireMember(memberId);
        FoodWorkspaceEntity stored = workspaces.selectById(memberId);
        if (stored == null) return empty(null);
        try {
            return normalize(json.readValue(stored.getContentJson(), FoodWorkspaceDto.class), stored.getUpdatedAt());
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("饮食管理数据无法读取，请联系管理员修复后再编辑");
        }
    }

    /** 校验并保存当前成员的完整工作区；派生汇总不进入持久化数据。 */
    @Transactional
    public FoodWorkspaceDto save(String memberId, FoodWorkspaceDto requested) {
        requireMember(memberId);
        if (requested == null) throw new BadRequestException("饮食管理内容不能为空");
        FoodWorkspaceDto normalized = normalize(requested, LocalDateTime.now());
        validate(normalized);
        try {
            String content = json.writeValueAsString(normalized);
            if (content.length() > MAX_JSON_LENGTH) {
                throw new BadRequestException("饮食管理内容过多，请精简记录后重试");
            }
            workspaces.upsert(memberId, content);
            FoodWorkspaceEntity saved = workspaces.selectById(memberId);
            LocalDateTime updatedAt = saved == null ? normalized.updatedAt() : saved.getUpdatedAt();
            return new FoodWorkspaceDto(normalized.templates(), normalized.mealLogs(),
                    normalized.preparationTasks(), normalized.profile(), normalized.principles(), updatedAt);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("饮食管理内容无法保存，请检查输入后重试");
        }
    }

    private FoodWorkspaceDto normalize(FoodWorkspaceDto source, LocalDateTime updatedAt) {
        if (source == null) return empty(updatedAt);
        List<MealTemplate> templates = list(source.templates()).stream().map(item -> new MealTemplate(
                text(item.id()), text(item.dayType()), text(item.mealType()), text(item.title()),
                item.targetTime(), tags(item.foods()), item.prepMinutes(), text(item.note()), item.enabled())).toList();
        List<MealLog> logs = list(source.mealLogs()).stream().map(item -> new MealLog(
                text(item.id()), item.date(), text(item.dayType()), text(item.mealType()), item.eatenAt(),
                tags(item.foods()), item.prepMinutes(), text(item.comfort()), item.qualityScore(),
                text(item.observation())))
                .sorted((left, right) -> {
                    int dates = compareDates(right.date(), left.date());
                    if (dates != 0) return dates;
                    if (left.eatenAt() == null) return right.eatenAt() == null ? 0 : 1;
                    if (right.eatenAt() == null) return -1;
                    return right.eatenAt().compareTo(left.eatenAt());
                }).toList();
        List<PreparationTask> tasks = list(source.preparationTasks()).stream().map(item -> new PreparationTask(
                text(item.id()), text(item.type()), text(item.title()), item.dueDate(), text(item.status()),
                text(item.note()))).toList();
        FoodProfile profile = source.profile() == null ? emptyProfile() : new FoodProfile(
                text(source.profile().preferences()), text(source.profile().avoidances()),
                text(source.profile().stomachObservations()), text(source.profile().efficiencyGoal()));
        String principles = StringUtils.hasText(source.principles())
                ? source.principles().trim() : DEFAULT_PRINCIPLES;
        return new FoodWorkspaceDto(templates, logs, tasks, profile, principles, updatedAt);
    }

    private void validate(FoodWorkspaceDto workspace) {
        requireLimit(workspace.templates(), 100, "餐次模板");
        requireLimit(workspace.mealLogs(), 3000, "用餐记录");
        requireLimit(workspace.preparationTasks(), 500, "备餐事项");
        uniqueIds(workspace.templates().stream().map(MealTemplate::id).toList(), "餐次模板");
        uniqueIds(workspace.mealLogs().stream().map(MealLog::id).toList(), "用餐记录");
        uniqueIds(workspace.preparationTasks().stream().map(PreparationTask::id).toList(), "备餐事项");
        for (MealTemplate template : workspace.templates()) {
            validateDayAndMeal(template.dayType(), template.mealType());
            requireText(template.title(), 120, "模板名称");
            if (template.targetTime() == null) throw new BadRequestException("模板用餐时间不能为空");
            requireFoods(template.foods());
            validatePrepMinutes(template.prepMinutes());
            maxText(template.note(), 2000, "模板备注");
        }
        Set<String> loggedMeals = new HashSet<>();
        for (MealLog log : workspace.mealLogs()) {
            validateDayAndMeal(log.dayType(), log.mealType());
            if (log.date() == null) throw new BadRequestException("用餐日期不能为空");
            if (log.date().isAfter(LocalDate.now())) throw new BadRequestException("实际用餐日期不能晚于今天");
            if (log.eatenAt() == null) throw new BadRequestException("实际用餐时间不能为空");
            if (!loggedMeals.add(log.date() + "|" + log.mealType())) {
                throw new BadRequestException("同一天的同一餐只能保留一条实际记录");
            }
            requireFoods(log.foods());
            validatePrepMinutes(log.prepMinutes());
            if (!COMFORTS.contains(log.comfort())) throw new BadRequestException("餐后感受不合法");
            if (log.qualityScore() == null || log.qualityScore() < 1 || log.qualityScore() > 5) {
                throw new BadRequestException("主观质量只能选择 1 到 5 分");
            }
            maxText(log.observation(), 2000, "餐后观察");
        }
        for (PreparationTask task : workspace.preparationTasks()) {
            if (!TASK_TYPES.contains(task.type())) throw new BadRequestException("备餐事项类型不合法");
            if (!TASK_STATUSES.contains(task.status())) throw new BadRequestException("备餐事项状态不合法");
            requireText(task.title(), 160, "备餐事项");
            if (task.dueDate() == null) throw new BadRequestException("备餐事项日期不能为空");
            maxText(task.note(), 2000, "备餐事项备注");
        }
        maxText(workspace.profile().preferences(), 2000, "口味偏好");
        maxText(workspace.profile().avoidances(), 2000, "主动避开食物");
        maxText(workspace.profile().stomachObservations(), 3000, "个人餐后观察");
        maxText(workspace.profile().efficiencyGoal(), 2000, "饮食效率目标");
        maxText(workspace.principles(), 2000, "饮食理念");
    }

    private FoodWorkspaceDto empty(LocalDateTime updatedAt) {
        return new FoodWorkspaceDto(List.of(), List.of(), List.of(), emptyProfile(), DEFAULT_PRINCIPLES, updatedAt);
    }

    private static FoodProfile emptyProfile() { return new FoodProfile("", "", "", ""); }
    private static void validateDayAndMeal(String dayType, String mealType) {
        if (!DAY_TYPES.contains(dayType)) throw new BadRequestException("日期类型不合法");
        if (!MEAL_TYPES.contains(mealType)) throw new BadRequestException("餐次类型不合法");
    }
    private static void requireFoods(List<String> foods) {
        if (foods.isEmpty()) throw new BadRequestException("请至少记录一种实际食物");
        for (String food : foods) maxText(food, 80, "食物名称");
    }
    private static void validatePrepMinutes(Integer value) {
        if (value == null || value < 0 || value > 1440) throw new BadRequestException("准备时长必须在 0 到 1440 分钟之间");
    }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static List<String> tags(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(StringUtils::hasText).map(String::trim).distinct().limit(30).toList();
    }
    private static int compareDates(LocalDate left, LocalDate right) {
        if (left == null) return right == null ? 0 : -1;
        return right == null ? 1 : left.compareTo(right);
    }
    private static void requireMember(String memberId) {
        if (!StringUtils.hasText(memberId) || "unknown".equals(memberId)) throw new BadRequestException("无法识别当前成员");
    }
    private static <T> void requireLimit(List<T> values, int max, String label) {
        if (values.size() > max) throw new BadRequestException(label + "最多保存 " + max + " 条");
    }
    private static Set<String> uniqueIds(List<String> ids, String label) {
        Set<String> unique = new HashSet<>();
        for (String id : ids) {
            if (!StringUtils.hasText(id) || id.length() > 32) throw new BadRequestException(label + "标识不合法");
            if (!unique.add(id)) throw new BadRequestException(label + "存在重复标识");
        }
        return unique;
    }
    private static void requireText(String value, int max, String label) {
        if (!StringUtils.hasText(value)) throw new BadRequestException(label + "不能为空");
        maxText(value, max, label);
    }
    private static void maxText(String value, int max, String label) {
        if (value != null && value.length() > max) throw new BadRequestException(label + "最多 " + max + " 字");
    }
}
