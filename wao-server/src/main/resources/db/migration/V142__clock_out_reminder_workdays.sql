-- 下班休息与打卡提醒只在中国工作日发送；Cron 仍每日推进，由执行器依据法定休息与调休事实决定发送或跳过。
UPDATE automation_job
SET handler = 'workday-feishu-notification',
    schedule_label = '工作日 18:00',
    steps_json = JSON_ARRAY('按上海时间每日检查工作日', '仅在工作日发送飞书提醒卡片')
WHERE id = 'job-reminder-clock-out'
  AND handler = 'feishu-notification';
