package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.FoodWorkspaceEntity;
import cn.wayuwa.wao.dto.FoodWorkspaceDto;
import cn.wayuwa.wao.dto.FoodWorkspaceDto.FoodProfile;
import cn.wayuwa.wao.dto.FoodWorkspaceDto.MealLog;
import cn.wayuwa.wao.dto.FoodWorkspaceDto.MealTemplate;
import cn.wayuwa.wao.dto.FoodWorkspaceDto.PreparationTask;
import cn.wayuwa.wao.mapper.FoodWorkspaceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FoodServiceTest {
    private final FoodWorkspaceMapper workspaces = Mockito.mock(FoodWorkspaceMapper.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final FoodService service = new FoodService(workspaces, json);

    @Test
    void firstVisitReturnsCulturalBoundaryWithoutFakeMeals() {
        when(workspaces.selectById("wg")).thenReturn(null);

        var result = service.workspace("wg");

        assertThat(result.principles()).contains("饮食有节", "如实记录");
        assertThat(result.templates()).isEmpty();
        assertThat(result.mealLogs()).isEmpty();
        assertThat(result.preparationTasks()).isEmpty();
        verify(workspaces, never()).upsert(any(), any());
    }

    @Test
    void savesOnlyNormalizedMemberFactsAndKeepsRecordsNewestFirst() throws Exception {
        LocalDate today = LocalDate.now();
        var request = workspace(
                List.of(new MealTemplate("ft1", "WORKDAY", "BREAKFAST", " 快手早餐 ",
                        LocalTime.of(8, 0), List.of("燕麦", "燕麦", " 鸡蛋 "), 8, "", true)),
                List.of(
                        new MealLog("fl1", today.minusDays(1), "WORKDAY", "LUNCH", LocalTime.NOON,
                                List.of("米饭", "青菜"), 20, "NEUTRAL", 4, ""),
                        new MealLog("fl2", today, "RESTDAY", "BREAKFAST", LocalTime.of(9, 0),
                                List.of("小米粥"), 30, "COMFORTABLE", 5, "舒服")));
        FoodWorkspaceEntity saved = new FoodWorkspaceEntity();
        saved.setMemberId("wg");
        saved.setUpdatedAt(LocalDateTime.now());
        when(workspaces.selectById("wg")).thenReturn(saved);

        var result = service.save("wg", request);

        assertThat(result.templates().get(0).title()).isEqualTo("快手早餐");
        assertThat(result.templates().get(0).foods()).containsExactly("燕麦", "鸡蛋");
        assertThat(result.mealLogs()).extracting(MealLog::id).containsExactly("fl2", "fl1");
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(workspaces).upsert(eq("wg"), content.capture());
        FoodWorkspaceDto persisted = json.readValue(content.getValue(), FoodWorkspaceDto.class);
        assertThat(persisted.mealLogs()).hasSize(2);
    }

    @Test
    void rejectsFutureOutOfRangeAndDuplicateMealFactsInsteadOfSaving() {
        var future = new MealLog("fl1", LocalDate.now().plusDays(1), "WORKDAY", "DINNER",
                LocalTime.of(18, 30), List.of("米饭"), 10, "COMFORTABLE", 5, "");
        assertThatThrownBy(() -> service.save("wg", workspace(List.of(), List.of(future))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不能晚于今天");

        var invalidScore = new MealLog("fl2", LocalDate.now(), "WORKDAY", "DINNER",
                LocalTime.of(18, 30), List.of("米饭"), 10, "COMFORTABLE", 9, "");
        assertThatThrownBy(() -> service.save("wg", workspace(List.of(), List.of(invalidScore))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("1 到 5 分");

        var sameMeal = new MealLog("fl3", LocalDate.now(), "WORKDAY", "DINNER",
                LocalTime.of(19, 0), List.of("面条"), 15, "NEUTRAL", 3, "");
        var firstDinner = new MealLog("fl4", LocalDate.now(), "WORKDAY", "DINNER",
                LocalTime.of(18, 30), List.of("米饭"), 10, "COMFORTABLE", 5, "");
        assertThatThrownBy(() -> service.save("wg", workspace(List.of(), List.of(firstDinner, sameMeal))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("同一餐");
        verify(workspaces, never()).upsert(any(), any());
    }

    private static FoodWorkspaceDto workspace(List<MealTemplate> templates, List<MealLog> logs) {
        return new FoodWorkspaceDto(templates, logs, List.<PreparationTask>of(),
                new FoodProfile("清淡", "", "", "工作日十分钟完成早餐"),
                FoodService.DEFAULT_PRINCIPLES, null);
    }
}
