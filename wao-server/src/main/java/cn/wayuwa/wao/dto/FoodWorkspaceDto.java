package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 饮食管理工作区；业务事实只来自当前成员的模板、记录与自述。 */
public record FoodWorkspaceDto(
        List<MealTemplate> templates,
        List<MealLog> mealLogs,
        List<PreparationTask> preparationTasks,
        FoodProfile profile,
        String principles,
        LocalDateTime updatedAt) {

    public record MealTemplate(String id, String dayType, String mealType, String title,
            LocalTime targetTime, List<String> foods, Integer prepMinutes, String note, boolean enabled) { }

    public record MealLog(String id, LocalDate date, String dayType, String mealType,
            LocalTime eatenAt, List<String> foods, Integer prepMinutes, String comfort,
            Integer qualityScore, String observation) { }

    public record PreparationTask(String id, String type, String title, LocalDate dueDate,
            String status, String note) { }

    public record FoodProfile(String preferences, String avoidances, String stomachObservations,
            String efficiencyGoal) { }
}
