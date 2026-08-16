package cn.wayuwa.wao.dto;

import java.time.LocalDate;

/** 回顾生成时使用的一条可追溯证据摘要。 */
public record LifeReviewSourceDto(String id, String type, String title, LocalDate date, String url) { }
