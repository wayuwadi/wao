package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 本人选择的完成、需调整或例外记录。 */
public record SavePrincipleCheckinRequest(@NotBlank String status, @Size(max = 500) String note) { }
