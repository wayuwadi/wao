-- 知识库域种子（与前端 fixtures 一致），落在 V5 新建的关系表上。

INSERT INTO kb_group (id, name, ord) VALUES ('g1', '我的知识库', 1);

INSERT INTO kb (id, name, icon, color, project_id, auto, description, group_id, perm, ord) VALUES
('k1', 'WAO 产品', 'ri-stack-line', '#4358D6', 'p1', 0, '产品定义、交互规范与评审结论', NULL, '仅自己', 1),
('k2', '家庭事务', 'ri-archive-line', '#B9821F', 'p2', 0, '归档规范、台账与流程说明', NULL, '仅自己', 2),
('k3', '个人成长', 'ri-seedling-line', '#2C8A5B', NULL, 0, '健康、阅读与长期笔记', 'g1', '仅自己', 3),
('k4', '群聊沉淀', 'ri-flashlight-line', '#7A5CC6', NULL, 1, '飞书群聊每日自动同步沉淀', 'g1', '仅自己', 4);

INSERT INTO kb_folder (id, kb_id, name, ord) VALUES
('f1', 'k1', '产品设计', 1),
('f2', 'k1', '调研与结论', 2),
('f3', 'k2', '归档规范', 1),
('f4', 'k3', '健康', 1),
('f5', 'k3', '学习笔记', 2),
('f6', 'k4', '2026 年 7 月', 1);

INSERT INTO doc (id, kb_id, folder_id, title, author_id, auto, created_label, updated_label, blocks_json, links_json, ord) VALUES
('d1', 'k1', 'f1', '任务模型与字段定义', 'wg', 0, '7月12日', '今天 09:40',
 JSON_ARRAY(
   JSON_OBJECT('call', '本文是「WAO 1.0 原型」任务体系的单一事实源。字段增删需在周评审会同步，并回写到原型标注。'),
   JSON_OBJECT('h', '核心字段'),
   JSON_OBJECT('p', '任务是 WAO 中最小的可执行单元。字段设计原则：默认极简，复杂度通过自定义属性按需扩展。'),
   JSON_OBJECT('ul', JSON_ARRAY('状态机：待办 → 进行中 → 已完成；「阻塞」是标记而非状态，解除后回到原状态', '优先级：P0 当天必须处理；P1 影响里程碑；P2 常规；P3 有空再说', '时间：开始 / 截止分离，支持仅有截止；逾期在所有视图中红色高亮', '人员：负责人唯一、参与人多选；「等待他人」由参与人 + 阻塞标记推导')),
   JSON_OBJECT('h', '关联模型'),
   JSON_OBJECT('p', '任务与文档、项目、日程之间都是双向链接：在任一侧建立关联，另一侧自动出现反向链接。这是「同一个上下文网络」的基础。'),
   JSON_OBJECT('ul', JSON_ARRAY('任务 ↔ 文档：需求、结论、复盘互相引用', '任务 ↔ 任务：阻塞 / 被阻塞、前置 / 后置', '任务 → 日程：拖入日历即生成时间块')),
   JSON_OBJECT('h', '自定义属性'),
   JSON_OBJECT('p', '每个项目可为任务追加属性（文本 / 数字 / 单选 / 链接）。属性属于项目模板，不污染全局字段表。'),
   JSON_OBJECT('q', JSON_ARRAY('字段可以少，但每个字段都要回答一个真实的问题。', '娃弟 · 7月10日群聊'))
 ),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p1'), JSON_OBJECT('type', 'task', 'id', 't1')), 1),
('d2', 'k1', 'f1', 'WAO 交互规范 v0.9', 'wg', 0, '7月8日', '昨天 18:20',
 JSON_ARRAY(
   JSON_OBJECT('p', '覆盖导航、弹层、抽屉、快捷键与反馈时长的统一约定，随原型迭代持续修订。'),
   JSON_OBJECT('h', '弹层层级'),
   JSON_OBJECT('ul', JSON_ARRAY('抽屉 > 弹窗 > 下拉 > 提示，同层级只允许一个实例', 'Esc 逐层关闭；遮罩点击只关闭最上层', '全屏庆祝动画不阻塞任何操作，1–2 秒内自行结束'))
 ),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p1')), 2),
('d3', 'k1', 'f2', '竞品体验拆解：项目聚合', 'wd', 0, '7月2日', '7月15日',
 JSON_ARRAY(
   JSON_OBJECT('p', '只吸收「信息聚合、层次清楚、上下文完整」的体验，不照抄任何产品的界面与品牌。'),
   JSON_OBJECT('ul', JSON_ARRAY('项目总览应回答三件事：现在怎么样、谁在做、卡在哪', '动态流比报表更能传达项目状态', '知识与任务离得越近，沉淀率越高'))
 ),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p1')), 3),
('d4', 'k1', 'f2', '品牌名与定位结论', 'wg', 0, '6月28日', '7月19日',
 JSON_ARRAY(
   JSON_OBJECT('p', 'WAO = Wayuwa All in One。定位：面向少数成员长期共同维护的 Life OS。'),
   JSON_OBJECT('q', JSON_ARRAY('All in One 不是把功能挤进一个页面，而是让所有内容处于同一个上下文网络。', '评审会结论 · 7月19日'))
 ),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p1')), 4),
('d5', 'k2', 'f3', '家庭资料归档目录规范', 'wd', 0, '5月12日', '7月19日',
 JSON_ARRAY(
   JSON_OBJECT('p', '四层目录：人 / 类型 / 年份 / 批次。文件名规则：日期-主题-编号。'),
   JSON_OBJECT('ul', JSON_ARRAY('证件类：正反面合并为单个 PDF，重命名后入库', '照片类：按扫描批次入库，EXIF 写入原始年代', '病历类：按人归档，就诊时间倒序'))
 ),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p2')), 5),
('d6', 'k2', 'f3', '证件与合同台账说明', 'wg', 0, '5月20日', '7月10日',
 JSON_ARRAY(JSON_OBJECT('p', '台账覆盖有效期、存放位置与更新责任人；到期前 60 天由自动化「证件到期扫描提醒」推送。')),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p2')), 6),
('d7', 'k3', 'f4', '健康重启：训练与恢复笔记', 'wg', 0, '6月1日', '7月20日',
 JSON_ARRAY(
   JSON_OBJECT('p', '记录每次训练的负荷与主观疲劳（RPE），每周日复盘并调整下周计划。'),
   JSON_OBJECT('ul', JSON_ARRAY('第 6 周结论：睡眠不足 6.5h 时，改为低强度有氧', '跑步机配速与户外配速差约 20 秒 / 公里', '恢复日拉伸清单固定为 8 个动作'))
 ),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p3')), 7),
('d8', 'k3', 'f5', '阅读笔记：卡片盒笔记法', 'wg', 0, '6月15日', '7月5日',
 JSON_ARRAY(JSON_OBJECT('p', '迁移知识库前先定标签体系：领域 / 项目 / 状态三轴，避免文件夹深层嵌套。')),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p4')), 8),
('d9', 'k4', 'f6', '群聊沉淀 · 7月23日', 'auto', 1, '今天 06:12', '今天 06:12',
 JSON_ARRAY(
   JSON_OBJECT('call', '由自动化「飞书群聊 → 知识库沉淀」于每日 06:00 生成，已按话题聚类。原始聊天记录保留 90 天。'),
   JSON_OBJECT('h', '昨日讨论摘要'),
   JSON_OBJECT('ul', JSON_ARRAY('任务详情：倾向「主区 + 右侧属性栏」结构；评论与活动是否合并，娃哥今天给结论', '图标方案：娃弟倾向 B（线性 · 2px 描边），待正式拍板', '周末安排：周六上午扫描第 3 批照片，下午户外 10km'))
 ),
 JSON_ARRAY(JSON_OBJECT('type', 'project', 'id', 'p1')), 9),
('d10', 'k4', 'f6', '群聊沉淀 · 7月22日', 'auto', 1, '昨天 06:09', '昨天 06:09',
 JSON_ARRAY(
   JSON_OBJECT('h', '昨日讨论摘要'),
   JSON_OBJECT('ul', JSON_ARRAY('原型评审改到周四 09:30，地点：书房', '体检报告已归档到「年度保险与体检梳理」', '确认 8 月旅行请假区间：9月28日–10月7日'))
 ),
 JSON_ARRAY(), 10);
