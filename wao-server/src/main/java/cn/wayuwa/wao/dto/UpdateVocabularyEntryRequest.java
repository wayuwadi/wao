package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 更新单词的个人释义、语境与学习状态。 */
public record UpdateVocabularyEntryRequest(
        @Size(max = 1000, message = "个人释义不能超过 1000 个字符") String meaning,
        @Size(max = 2000, message = "语境笔记不能超过 2000 个字符") String context,
        @NotBlank(message = "学习状态不能为空") String state) {
}
