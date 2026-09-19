package cn.wayuwa.wao.domain;

/** 首页运行计数的窄查询结果，不加载历史运行正文。 */
public record DashboardRunCounts(int total, int active, int succeededWeek, int failedWeek) { }
