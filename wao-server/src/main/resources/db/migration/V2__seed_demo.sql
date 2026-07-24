-- 演示数据：与前端 wao-web/src/data/fixtures.ts 一致（娃屋 · 娃哥/娃弟）。

INSERT INTO org (id, name, member_count) VALUES ('wao', 'Wa Allin One', 2);

INSERT INTO member (id, name, initial, color, role) VALUES
    ('wg', '娃哥', '哥', '#4358D6', '管理员'),
    ('wd', '娃弟', '弟', '#2C8A5B', '成员');

INSERT INTO area (id, name, color) VALUES
    ('career', '事业', '#4358D6'),
    ('health', '健康', '#2C8A5B'),
    ('family', '家庭', '#B9821F'),
    ('finance', '财务', '#7A5CC6'),
    ('growth', '学习成长', '#C05D8E');

INSERT INTO project (id, name, area, status, health, progress, owner_id, start_date, due_date, visibility, updated_label, goal, detail_json) VALUES
('p1', 'WAO 1.0 原型', 'career', '进行中', '良好', 62, 'wg', '2026-06-20', '2026-08-15', '组织内 2 人 · 可编辑', '26 分钟前',
 '8 月 15 日前完成可运行、可交互的高保真原型，通过家庭内部验收，并确定 1.0 开发范围。',
 '{"members":["wg","wd"],"msDates":["2026-07-05","2026-07-18","2026-07-28","2026-08-08","2026-08-15"],"trend":[8,15,22,31,38,47,55,62],"docs":["d1","d2","d3","d4"],"milestones":[{"name":"信息架构定稿","date":"7月5日","state":2},{"name":"视觉方案定稿","date":"7月18日","state":2},{"name":"核心页面交互稿","date":"7月28日","state":1},{"name":"可用性走查","date":"8月8日","state":0},{"name":"1.0 内部验收","date":"8月15日","state":0}],"blockers":[{"text":"「评论区组件开发」等待任务详情抽屉结论","who":"娃哥","days":"2 天"},{"text":"导航图标方案 A/B 未拍板","who":"娃弟","days":"1 天"}],"files":[{"name":"任务详情-线框-v3.fig","size":"2.4 MB","icon":"ri-pen-nib-line"},{"name":"WAO-视觉规范-0718.pdf","size":"8.1 MB","icon":"ri-file-text-line"},{"name":"竞品结构对比.xlsx","size":"86 KB","icon":"ri-table-line"}],"activity":[{"icon":"ri-chat-3-line","color":"#4358D6","text":"娃弟在「设计任务详情抽屉交互稿」下添加了评论","time":"昨天 21:40"},{"icon":"ri-loader-4-line","color":"#B9821F","text":"「评论区组件开发」被标记为阻塞，等待交互稿结论","time":"7月21日"},{"icon":"ri-links-line","color":"#4358D6","text":"娃哥关联了文档「任务模型与字段定义」","time":"7月20日"},{"icon":"ri-flag-2-line","color":"#2C8A5B","text":"里程碑「视觉方案定稿」完成，比计划提前 1 天","time":"7月18日"},{"icon":"ri-user-add-line","color":"#2C8A5B","text":"娃弟加入项目，权限为可编辑","time":"6月21日"}]}'),
('p2', '家庭资料归档', 'family', '进行中', '良好', 38, 'wd', '2026-05-10', '2026-09-30', '组织内 2 人 · 可编辑', '2 小时前',
 '把证件、合同、病历与三千余张老照片全部数字化，建立可检索的家庭档案目录。',
 '{"members":["wd","wg"],"msDates":[],"trend":[4,9,15,20,26,31,35,38],"docs":["d5","d6"],"milestones":[],"blockers":[],"files":[{"name":"档案盒编号对照表.xlsx","size":"44 KB","icon":"ri-table-line"}],"activity":[{"icon":"ri-image-line","color":"#B9821F","text":"第 2 批扫描完成：312 张老照片入库","time":"7月19日"}]}'),
('p3', '健康重启计划', 'health', '有风险', '需要关注', 45, 'wg', '2026-06-01', '2026-10-01', '仅项目成员可见', '3 天前',
 '12 周重建运动与睡眠习惯：体脂降到 20%，5km 配速进入 6 分钟。',
 '{"members":["wg"],"msDates":[],"trend":[10,18,26,33,40,44,45,45],"docs":["d7"],"milestones":[],"blockers":[{"text":"连续 3 天未记录训练","who":"娃哥","days":"3 天"}],"files":[],"activity":[{"icon":"ri-alert-line","color":"#D65454","text":"连续 3 天无训练记录，项目转为「有风险」","time":"今天 06:00"}]}'),
('p4', '个人知识库迁移', 'growth', '规划中', '良好', 12, 'wg', '2026-08-01', '2026-11-15', '仅自己可见', '5 天前',
 '把散落在飞书、Notion 与微信收藏的 800+ 条笔记迁入 WAO 知识库并建立标签体系。',
 '{"members":["wg"],"msDates":[],"trend":[0,0,2,4,6,8,10,12],"docs":["d8"],"milestones":[],"blockers":[],"files":[],"activity":[]}'),
('p5', '滇西北家庭旅行', 'family', '规划中', '良好', 8, 'wd', '2026-08-20', '2026-10-06', '组织内 2 人 · 可编辑', '1 周前',
 '国庆 10 天滇西北环线：路线、住宿、预算与装备全部提前一个月锁定。',
 '{"members":["wd","wg"],"msDates":[],"trend":[0,0,0,2,3,5,6,8],"docs":[],"milestones":[],"blockers":[],"files":[],"activity":[]}'),
('p6', '年度保险与体检梳理', 'finance', '已完成', '良好', 100, 'wd', '2026-04-01', '2026-06-30', '组织内 2 人 · 可编辑', '6月30日',
 '完成全家保单年检与两人年度体检，理赔与报告全部归档。',
 '{"members":["wd","wg"],"msDates":[],"trend":[20,35,50,65,78,88,95,100],"docs":[],"milestones":[],"blockers":[],"files":[{"name":"2026 体检报告-娃哥.pdf","size":"3.2 MB","icon":"ri-file-text-line"}],"activity":[]}');

INSERT INTO task (id, title, project_id, status, pr, owner_id, start_date, due_date, waiting, description, detail_json) VALUES
('t1', '设计任务详情抽屉交互稿', 'p1', '进行中', 'P0', 'wg', '2026-07-20', '2026-07-24', NULL,
 '任务详情是 WAO 1.0 验收权重最高的体验。目标：信息完整但不混乱，主区（描述 / 步骤 / 子任务 / 评论）与右侧属性栏分层清晰，所有修改不离开当前上下文。',
 '{"parts":["wd"],"docs":["d1","d2"],"steps":[{"text":"属性栏信息分层：状态行 / 人员行 / 时间行","done":true},{"text":"主区块排序：描述 → 步骤 → 子任务 → 附件","done":true},{"text":"评论与活动时间线的合并方案","done":false},{"text":"空状态与加载态设计","done":false}],"subs":[{"text":"抽屉宽度与窄窗口断点规则","done":true},{"text":"状态 / 优先级选择器交互","done":false},{"text":"活动时间线信息密度确认","done":false}],"files":[{"name":"任务详情-线框-v3.fig","size":"2.4 MB","icon":"ri-pen-nib-line"},{"name":"竞品结构对比.xlsx","size":"86 KB","icon":"ri-table-line"}],"linkTasks":[{"id":"t2","rel":"被本任务阻塞"}],"comments":[{"who":"wd","time":"昨天 21:40","text":"属性栏建议用我们讨论的三段式：状态行 / 人员行 / 时间行，自定义属性默认折叠。"},{"who":"wg","time":"今天 09:15","text":"收到，上午出一版。评论区和活动是否合并，下午对齐。"}],"custom":[{"name":"设计稿链接","value":"figma.com/wao-task-detail"},{"name":"验收权重","value":"15%"}],"activity":[{"icon":"ri-chat-3-line","color":"#4358D6","text":"娃弟添加了评论","time":"昨天 21:40"},{"icon":"ri-loader-4-line","color":"#B9821F","text":"标记「评论区组件开发」为被阻塞","time":"7月21日"},{"icon":"ri-play-line","color":"#4358D6","text":"状态改为进行中","time":"7月21日"},{"icon":"ri-add-line","color":"#9A9DA9","text":"娃哥创建了任务","time":"7月20日"}]}'),
('t2', '评论区组件开发', 'p1', '阻塞', 'P1', 'wd', NULL, '2026-07-29', '等待娃哥', '依赖任务详情抽屉的评论 / 活动合并结论。', '{"parts":["wg"]}'),
('t3', '首页 AI 聚焦文案走查', 'p1', '待办', 'P2', 'wg', NULL, '2026-07-25', NULL, '', '{}'),
('t4', '知识库阅读排版规范', 'p1', '待办', 'P2', 'wd', NULL, '2026-07-27', NULL, '', '{}'),
('t5', '扫描第 3 批老照片（1998–2004）', 'p2', '进行中', 'P2', 'wd', NULL, '2026-07-23', NULL, '', '{}'),
('t6', '证件有效期台账更新', 'p2', '待办', 'P1', 'wg', NULL, '2026-07-23', NULL, '', '{}'),
('t7', '夜跑 5km · 第 18/36 次', 'p3', '待办', 'P1', 'wg', NULL, '2026-07-23', NULL, '', '{}'),
('t8', '预约体态评估复测', 'p3', '待办', 'P2', 'wg', NULL, '2026-07-31', NULL, '', '{}'),
('t9', '制定 8 月饮食模板', 'p3', '待办', 'P3', 'wd', NULL, '2026-08-03', NULL, '', '{}'),
('t10', '整理 Notion 导出清单', 'p4', '待办', 'P3', 'wg', NULL, '2026-08-10', NULL, '', '{}'),
('t11', '香格里拉段路线初稿', 'p5', '待办', 'P3', 'wd', NULL, '2026-08-24', NULL, '', '{"parts":["wg"]}'),
('t12', '「品牌名讨论」结论沉淀到知识库', 'p1', '已完成', 'P2', 'wg', NULL, '2026-07-19', NULL, '', '{}'),
('t13', '家庭 NAS 目录规范 v2', 'p2', '待办', 'P2', 'wg', NULL, '2026-07-30', '等待娃弟', '', '{"parts":["wd"]}'),
('t14', '导航图标方案 A/B 拍板', 'p1', '待办', 'P1', 'wd', NULL, '2026-07-22', '等待娃弟', '', '{"parts":["wg"]}');
