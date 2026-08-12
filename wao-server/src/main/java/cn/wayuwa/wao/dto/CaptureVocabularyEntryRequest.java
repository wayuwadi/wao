package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 快速收集一个陌生英语单词或短语。 */
public record CaptureVocabularyEntryRequest(
        @NotBlank(message = "单词或短语不能为空")
        @Size(max = 128, message = "单词或短语不能超过 128 个字符") String term) {
}
