-- 在既有真实自动化调度与飞书通知执行器上预置四条每日提醒。
-- next_fire_at 使用数据库节点当前日期计算下一个上海时间计划点；生产节点统一使用 Asia/Shanghai。
INSERT INTO automation_job
    (id, name, icon, schedule_label, cron_expr, status, last_run_label, description, source, target,
     steps_json, next_fire_at, ord, handler)
VALUES
    ('job-reminder-clock-in-am', '早间打卡提醒', 'ri-alarm-line', '每日 08:50', '0 50 8 * * *',
     '正常', '尚未运行', '早上好，记得打卡。', '通知设置 · 每日提醒', '飞书群',
     JSON_ARRAY('按上海时间每日触发', '发送飞书提醒卡片'),
     DATE_ADD(DATE_ADD(CURDATE(), INTERVAL IF(CURTIME() < '08:50:00', 0, 1) DAY), INTERVAL 530 MINUTE),
     120, 'feishu-notification'),
    ('job-reminder-lunch', '午餐提醒', 'ri-alarm-line', '每日 12:00', '0 0 12 * * *',
     '正常', '尚未运行', '到午饭时间了，记得去吃饭。', '通知设置 · 每日提醒', '飞书群',
     JSON_ARRAY('按上海时间每日触发', '发送飞书提醒卡片'),
     DATE_ADD(DATE_ADD(CURDATE(), INTERVAL IF(CURTIME() < '12:00:00', 0, 1) DAY), INTERVAL 720 MINUTE),
     121, 'feishu-notification'),
    ('job-reminder-nap', '午休提醒', 'ri-alarm-line', '每日 12:30', '0 30 12 * * *',
     '正常', '尚未运行', '到午休时间了，准备睡觉休息。', '通知设置 · 每日提醒', '飞书群',
     JSON_ARRAY('按上海时间每日触发', '发送飞书提醒卡片'),
     DATE_ADD(DATE_ADD(CURDATE(), INTERVAL IF(CURTIME() < '12:30:00', 0, 1) DAY), INTERVAL 750 MINUTE),
     122, 'feishu-notification'),
    ('job-reminder-clock-out', '下班休息与打卡提醒', 'ri-alarm-line', '每日 18:00', '0 0 18 * * *',
     '正常', '尚未运行', '忙了一天，记得休息并打卡。', '通知设置 · 每日提醒', '飞书群',
     JSON_ARRAY('按上海时间每日触发', '发送飞书提醒卡片'),
     DATE_ADD(DATE_ADD(CURDATE(), INTERVAL IF(CURTIME() < '18:00:00', 0, 1) DAY), INTERVAL 1080 MINUTE),
     123, 'feishu-notification');
