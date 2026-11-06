package cn.wayuwa.wao.service;

/** Emitted in the task transaction; repeated tasks are advanced only after commit. */
public record TaskCompletedEvent(String taskId) {}
