package cn.wayuwa.wao.dto;
public record AttendanceDayRequest(String clockInTime, String clockOutTime, boolean clockOutNextDay, String dayType) { }
