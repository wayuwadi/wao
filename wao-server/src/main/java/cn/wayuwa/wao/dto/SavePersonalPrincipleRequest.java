package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

/** 由发起人确认的准则文本、适用人和提醒时机。 */
public record SavePersonalPrincipleRequest(
        @NotBlank @Size(max = 240) String title,
        @NotBlank String category,
        @NotEmpty List<@NotBlank String> targetMemberIds,
        @NotBlank @Size(max = 240) String triggerText,
        @NotBlank @Size(max = 500) String desiredAction,
        @Size(max = 500) String exceptionText,
        LocalTime reminderTime,
        @NotBlank String evidenceSource,
        boolean paused,
        Integer version) { }
