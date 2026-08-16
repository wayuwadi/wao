package cn.wayuwa.wao.agent.dto;

import java.util.List;

public record VideoCapabilityDto(boolean available, String provider, List<String> models,
                                 List<Integer> durations, List<String> sizes,
                                 String shutdownDate, String note) { }
