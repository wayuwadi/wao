package cn.wayuwa.wao.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 替换一枚设备令牌的组织/项目授权；个人授权始终保留。 */
public record SaveMemoryGrantsRequest(@NotNull @Valid List<MemoryGrantDto> grants) { }
