package cn.wayuwa.wao.agent.dto;

import java.util.List;

public record VideoCenterDto(VideoCapabilityDto capability, List<VideoGenerationDto> tasks) { }
