package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 保存一天训练进度；完成状态还会在服务端校验四项技能与信心自评。 */
public record SaveEnglishLearningSessionRequest(
        @NotBlank(message = "课程标识不能为空") @Size(max = 32) String lessonId,
        boolean listeningDone,
        boolean speakingDone,
        boolean readingDone,
        boolean writingDone,
        @Min(value = 0, message = "练习时长不能小于 0")
        @Max(value = 240, message = "单日练习时长不能超过 240 分钟") int practiceMinutes,
        @Min(value = 1, message = "信心自评最低为 1")
        @Max(value = 5, message = "信心自评最高为 5") Integer confidence,
        @Size(max = 4000, message = "写作练习不能超过 4000 个字符") String writingText,
        @Size(max = 2000, message = "今日复盘不能超过 2000 个字符") String reflection,
        @Size(max = 500, message = "下次重点不能超过 500 个字符") String nextFocus,
        boolean completed) { }
