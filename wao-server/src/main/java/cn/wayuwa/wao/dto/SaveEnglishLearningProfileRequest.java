package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 保存成员英语目标；身份只从服务端会话取得。 */
public record SaveEnglishLearningProfileRequest(
        @NotBlank(message = "请写下英语学习目标")
        @Size(max = 500, message = "学习目标不能超过 500 个字符") String goal,
        @Min(value = 10, message = "每日目标不能少于 10 分钟")
        @Max(value = 120, message = "每日目标不能超过 120 分钟") int dailyTargetMinutes,
        @NotNull(message = "请选择开始日期") LocalDate startDate) { }
