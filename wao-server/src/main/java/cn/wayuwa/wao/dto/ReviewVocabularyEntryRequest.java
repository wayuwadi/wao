package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 一次复习完成后的自评状态。 */
public record ReviewVocabularyEntryRequest(
        @NotBlank(message = "复习结果不能为空") String state) {
}
