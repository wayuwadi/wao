package cn.wayuwa.wao.dto;

/** 快速收集结果；重复词会返回既有记录并标明未新建。 */
public record CaptureVocabularyEntryDto(VocabularyEntryDto entry, boolean created) {
}
