-- 内容域种子（与前端 fixtures 一致）。

INSERT INTO kb (id, ord, payload) VALUES
('k1', 1, '{"id":"k1","name":"WAO 产品","icon":"ri-stack-line","color":"#4358D6","project":"p1","desc":"产品定义、交互规范与评审结论","folders":[{"id":"f1","name":"产品设计","docs":["d1","d2"]},{"id":"f2","name":"调研与结论","docs":["d3","d4"]}]}'),
('k2', 2, '{"id":"k2","name":"家庭事务","icon":"ri-archive-line","color":"#B9821F","project":"p2","desc":"归档规范、台账与流程说明","folders":[{"id":"f3","name":"归档规范","docs":["d5","d6"]}]}'),
('k3', 3, '{"id":"k3","name":"个人成长","icon":"ri-seedling-line","color":"#2C8A5B","project":null,"desc":"健康、阅读与长期笔记","folders":[{"id":"f4","name":"健康","docs":["d7"]},{"id":"f5","name":"学习笔记","docs":["d8"]}]}'),
('k4', 4, '{"id":"k4","name":"群聊沉淀","icon":"ri-flashlight-line","color":"#7A5CC6","project":null,"auto":true,"desc":"飞书群聊每日自动同步沉淀","folders":[{"id":"f6","name":"2026 年 7 月","docs":["d9","d10"]}]}');

INSERT INTO doc (id, ord, payload) VALUES
('d1', 1, '{"id":"d1","title":"任务模型与字段定义","author":"wg","created":"7月12日","updated":"今天 09:40","links":[{"type":"project","id":"p1"},{"type":"task","id":"t1"}],"blocks":[{"call":"本文是「WAO 1.0 原型」任务体系的单一事实源。字段增删需在周评审会同步，并回写到原型标注。"},{"h":"核心字段"},{"p":"任务是 WAO 中最小的可执行单元。字段设计原则：默认极简，复杂度通过自定义属性按需扩展。"},{"ul":["状态机：待办 → 进行中 → 已完成；「阻塞」是标记而非状态，解除后回到原状态","优先级：P0 当天必须处理；P1 影响里程碑；P2 常规；P3 有空再说","时间：开始 / 截止分离，支持仅有截止；逾期在所有视图中红色高亮","人员：负责人唯一、参与人多选；「等待他人」由参与人 + 阻塞标记推导"]},{"h":"关联模型"},{"p":"任务与文档、项目、日程之间都是双向链接：在任一侧建立关联，另一侧自动出现反向链接。这是「同一个上下文网络」的基础。"},{"ul":["任务 ↔ 文档：需求、结论、复盘互相引用","任务 ↔ 任务：阻塞 / 被阻塞、前置 / 后置","任务 → 日程：拖入日历即生成时间块"]},{"h":"自定义属性"},{"p":"每个项目可为任务追加属性（文本 / 数字 / 单选 / 链接）。属性属于项目模板，不污染全局字段表。"},{"q":["字段可以少，但每个字段都要回答一个真实的问题。","娃弟 · 7月10日群聊"]}],"backlinks":[{"type":"task","id":"t1","snippet":"交互稿中的属性栏分层引用了本文「核心字段」"},{"type":"doc","id":"d2","snippet":"交互规范 3.2 节引用了状态机定义"},{"type":"project","id":"p1","snippet":"项目总览「关联知识」引用本文"}]}'),
('d2', 2, '{"id":"d2","title":"WAO 交互规范 v0.9","author":"wg","created":"7月8日","updated":"昨天 18:20","links":[{"type":"project","id":"p1"}],"blocks":[{"p":"覆盖导航、弹层、抽屉、快捷键与反馈时长的统一约定，随原型迭代持续修订。"},{"h":"弹层层级"},{"ul":["抽屉 > 弹窗 > 下拉 > 提示，同层级只允许一个实例","Esc 逐层关闭；遮罩点击只关闭最上层","全屏庆祝动画不阻塞任何操作，1–2 秒内自行结束"]}],"backlinks":[{"type":"task","id":"t1","snippet":"抽屉宽度断点引用 2.1 节"}]}'),
('d3', 3, '{"id":"d3","title":"竞品体验拆解：项目聚合","author":"wd","created":"7月2日","updated":"7月15日","links":[{"type":"project","id":"p1"}],"blocks":[{"p":"只吸收「信息聚合、层次清楚、上下文完整」的体验，不照抄任何产品的界面与品牌。"},{"ul":["项目总览应回答三件事：现在怎么样、谁在做、卡在哪","动态流比报表更能传达项目状态","知识与任务离得越近，沉淀率越高"]}],"backlinks":[]}'),
('d4', 4, '{"id":"d4","title":"品牌名与定位结论","author":"wg","created":"6月28日","updated":"7月19日","links":[{"type":"project","id":"p1"}],"blocks":[{"p":"WAO = Wayuwa All in One。定位：面向少数成员长期共同维护的 Life OS。"},{"q":["All in One 不是把功能挤进一个页面，而是让所有内容处于同一个上下文网络。","评审会结论 · 7月19日"]}],"backlinks":[{"type":"task","id":"t12","snippet":"由任务「品牌名讨论结论沉淀」产出"}]}'),
('d5', 5, '{"id":"d5","title":"家庭资料归档目录规范","author":"wd","created":"5月12日","updated":"7月19日","links":[{"type":"project","id":"p2"}],"blocks":[{"p":"四层目录：人 / 类型 / 年份 / 批次。文件名规则：日期-主题-编号。"},{"ul":["证件类：正反面合并为单个 PDF，重命名后入库","照片类：按扫描批次入库，EXIF 写入原始年代","病历类：按人归档，就诊时间倒序"]}],"backlinks":[{"type":"task","id":"t5","snippet":"第 3 批扫描按本规范执行"}]}'),
('d6', 6, '{"id":"d6","title":"证件与合同台账说明","author":"wg","created":"5月20日","updated":"7月10日","links":[{"type":"project","id":"p2"}],"blocks":[{"p":"台账覆盖有效期、存放位置与更新责任人；到期前 60 天由自动化「证件到期扫描提醒」推送。"}],"backlinks":[]}'),
('d7', 7, '{"id":"d7","title":"健康重启：训练与恢复笔记","author":"wg","created":"6月1日","updated":"7月20日","links":[{"type":"project","id":"p3"}],"blocks":[{"p":"记录每次训练的负荷与主观疲劳（RPE），每周日复盘并调整下周计划。"},{"ul":["第 6 周结论：睡眠不足 6.5h 时，改为低强度有氧","跑步机配速与户外配速差约 20 秒 / 公里","恢复日拉伸清单固定为 8 个动作"]}],"backlinks":[{"type":"task","id":"t7","snippet":"今晚打卡后补记 RPE"}]}'),
('d8', 8, '{"id":"d8","title":"阅读笔记：卡片盒笔记法","author":"wg","created":"6月15日","updated":"7月5日","links":[{"type":"project","id":"p4"}],"blocks":[{"p":"迁移知识库前先定标签体系：领域 / 项目 / 状态三轴，避免文件夹深层嵌套。"}],"backlinks":[]}'),
('d9', 9, '{"id":"d9","title":"群聊沉淀 · 7月23日","author":"auto","auto":true,"created":"今天 06:12","updated":"今天 06:12","links":[{"type":"project","id":"p1"}],"blocks":[{"call":"由自动化「飞书群聊 → 知识库沉淀」于每日 06:00 生成，已按话题聚类。原始聊天记录保留 90 天。"},{"h":"昨日讨论摘要"},{"ul":["任务详情：倾向「主区 + 右侧属性栏」结构；评论与活动是否合并，娃哥今天给结论","图标方案：娃弟倾向 B（线性 · 2px 描边），待正式拍板","周末安排：周六上午扫描第 3 批照片，下午户外 10km"]}],"backlinks":[]}'),
('d10', 10, '{"id":"d10","title":"群聊沉淀 · 7月22日","author":"auto","auto":true,"created":"昨天 06:09","updated":"昨天 06:09","links":[],"blocks":[{"h":"昨日讨论摘要"},{"ul":["原型评审改到周四 09:30，地点：书房","体检报告已归档到「年度保险与体检梳理」","确认 8 月旅行请假区间：9月28日–10月7日"]}],"backlinks":[]}');

INSERT INTO job (id, ord, payload) VALUES
('j1', 1, '{"name":"飞书群聊 → 知识库沉淀","icon":"ri-chat-3-line","schedule":"每日 06:00","status":"正常","last":"今天 06:12 · 归档 12 条讨论","desc":"把「娃屋日常」群的讨论按话题聚类，沉淀为可检索的长期知识。","source":"飞书群「娃屋日常」","target":"知识库 · 群聊沉淀","steps":["拉取近 24 小时群消息","按话题聚类并生成摘要","写入「群聊沉淀」并建立与项目 / 任务的关联"],"runs":[["今天 06:12","成功 · 归档 12 条"],["昨天 06:09","成功 · 归档 8 条"],["7月21日 06:10","成功 · 归档 15 条"]]}'),
('j2', 2, '{"name":"手机相册 → 家庭 NAS 备份","icon":"ri-image-line","schedule":"每日 02:00","status":"正常","last":"今天 02:03 · 备份 214 张","desc":"两台手机的新增照片自动归档到 NAS，并写入图床索引。","source":"娃哥 / 娃弟 手机相册","target":"家庭 NAS · /photos","steps":["扫描两台手机新增照片","按人物 / 年份归档到 NAS","生成缩略图并更新图床索引"],"runs":[["今天 02:03","成功 · 214 张"],["昨天 02:02","成功 · 96 张"],["7月21日 02:04","成功 · 180 张"]]}'),
('j3', 3, '{"name":"账单邮件 → 财务归档","icon":"ri-mail-line","schedule":"每周一 09:00","status":"待修复","last":"7月20日 失败 · 邮箱授权过期","desc":"抓取账单邮件，解析金额与账期，自动归档到财务领域。","source":"邮箱 bill@wayuwa.example","target":"文件 · 财务归档","steps":["抓取账单类新邮件","解析金额、账期与商户","归档并更新台账"],"runs":[["7月20日 09:00","失败 · 授权过期"],["7月13日 09:01","成功 · 6 封"],["7月6日 09:00","成功 · 4 封"]]}'),
('j4', 4, '{"name":"证件到期扫描提醒","icon":"ri-alarm-line","schedule":"每月 1 日","status":"正常","last":"7月1日 · 无即将到期证件","desc":"扫描证件台账，到期前 60 天在首页与飞书提醒责任人。","source":"知识库 · 证件与合同台账","target":"首页提醒 + 飞书通知","steps":["读取台账有效期字段","筛选 60 天内到期条目","生成提醒并 @ 责任人"],"runs":[["7月1日 08:00","成功 · 无到期项"],["6月1日 08:00","成功 · 提醒 1 项"],["5月1日 08:00","成功 · 无到期项"]]}');

INSERT INTO file_item (id, ord, payload) VALUES
('fi1', 1, '{"name":"WAO-视觉规范-0718.pdf","size":"8.1 MB","icon":"ri-file-text-line","time":"7月18日","by":"娃哥","link":"https://f.wayuwa.example/dl/9f2k1/WAO-视觉规范-0718.pdf"}'),
('fi2', 2, '{"name":"任务详情-线框-v3.fig","size":"2.4 MB","icon":"ri-pen-nib-line","time":"7月16日","by":"娃哥","link":"https://f.wayuwa.example/dl/x83nd/任务详情-线框-v3.fig"}'),
('fi3', 3, '{"name":"2026-证件扫描-批次03.zip","size":"1.2 GB","icon":"ri-file-zip-line","time":"今天 11:20","by":"娃弟","link":"https://f.wayuwa.example/dl/72jd8/证件扫描-批次03.zip"}'),
('fi4', 4, '{"name":"体检报告-娃哥-2026.pdf","size":"3.2 MB","icon":"ri-file-text-line","time":"6月28日","by":"娃哥","link":"https://f.wayuwa.example/dl/lm2s9/体检报告-2026.pdf"}'),
('fi5', 5, '{"name":"滇西北行程预算表.xlsx","size":"96 KB","icon":"ri-table-line","time":"7月10日","by":"娃弟","link":"https://f.wayuwa.example/dl/qp1aa/滇西北行程预算表.xlsx"}');

INSERT INTO image_item (id, ord, payload) VALUES
('im1', 1, '{"name":"滇西北路线-手绘草图.png","size":"2.8 MB","time":"7月12日","from":"项目 · 滇西北家庭旅行","url":"https://i.wayuwa.example/8f3a2c.png","c1":"#E8ECFA","c2":"#DDE3F6"}'),
('im2', 2, '{"name":"任务详情-截图-0722.png","size":"640 KB","time":"昨天","from":"任务 · 任务详情抽屉","url":"https://i.wayuwa.example/2b9d1e.png","c1":"#EAF4EE","c2":"#DEEEE5"}'),
('im3', 3, '{"name":"老照片-1999-全家福.jpg","size":"1.9 MB","time":"7月19日","from":"项目 · 家庭资料归档","url":"https://i.wayuwa.example/5c2f77.jpg","c1":"#F7EFE2","c2":"#F0E5D0"}'),
('im4', 4, '{"name":"白板-年度规划-导出.png","size":"1.1 MB","time":"7月5日","from":"白板 · 年度规划","url":"https://i.wayuwa.example/91ac3b.png","c1":"#F3ECF8","c2":"#EAE0F2"}'),
('im5', 5, '{"name":"评审会-白板拍照.jpg","size":"2.2 MB","time":"7月18日","from":"知识库 · WAO 产品","url":"https://i.wayuwa.example/c7d940.jpg","c1":"#FDEEEE","c2":"#F6E2E2"}'),
('im6', 6, '{"name":"跑步路线-截图.png","size":"380 KB","time":"7月15日","from":"项目 · 健康重启计划","url":"https://i.wayuwa.example/44e0b2.png","c1":"#E9F1F7","c2":"#DCE9F2"}');

INSERT INTO vault_group (id, ord, payload) VALUES
('vg1', 1, '{"group":"家庭网络","items":[{"name":"家庭 Wi-Fi","icon":"ri-wifi-line","user":"wayuwa-home","site":"192.168.1.1","note":"路由器管理密码单独存放；访客网络每季度更换。","updated":"6月12日"},{"name":"NAS 管理后台","icon":"ri-hard-drive-2-line","user":"admin@nas.local","site":"nas.local:5001","note":"开启了两步验证，恢复代码在保险柜。","updated":"5月30日"}]}'),
('vg2', 2, '{"group":"生活缴费","items":[{"name":"水电煤缴费账户","icon":"ri-flashlight-line","user":"138****0000","site":"95598.example","note":"","updated":"4月18日"}]}'),
('vg3', 3, '{"group":"订阅服务","items":[{"name":"流媒体家庭组","icon":"ri-movie-2-line","user":"family@wayuwa.example","site":"tv.example.com","note":"家庭组 4 人位，娃弟管理续费。","updated":"7月2日"},{"name":"云相册会员","icon":"ri-image-line","user":"wd@wayuwa.example","site":"photos.example.com","note":"","updated":"6月20日"}]}');

INSERT INTO calendar_event (id, ord, payload) VALUES
('ce1', 1, '{"date":"2026-07-24","title":"原型评审（工作）","color":"#C05D8E"}'),
('ce2', 2, '{"date":"2026-07-25","title":"家庭聚餐 · 爸妈家","color":"#0E8C8C"}'),
('ce3', 3, '{"date":"2026-07-28","title":"物业费缴纳提醒","color":"#0E8C8C"}'),
('ce4', 4, '{"date":"2026-08-01","title":"爷爷生日","color":"#0E8C8C"}'),
('ce5', 5, '{"date":"2026-08-05","title":"牙科复诊（工作日历）","color":"#C05D8E"}');

INSERT INTO schedule_item (id, ord, payload) VALUES
('si1', 1, '{"time":"09:30","title":"WAO 原型评审 · 与娃弟","sub":"会议 · 1 小时","color":"#4358D6"}'),
('si2', 2, '{"time":"14:00","title":"整理家庭档案 · 第 3 批扫描","sub":"家庭资料归档","color":"#B9821F"}'),
('si3', 3, '{"time":"19:30","title":"夜跑 5km · 第 18 次","sub":"健康重启计划","color":"#2C8A5B"}');

INSERT INTO suggestion (id, ord, payload) VALUES
('s1', 1, '{"id":"s1","task":"t1","title":"今天优先完成「任务详情抽屉」的交互稿","why":"「WAO 1.0 原型」距 8 月 15 日验收还有 23 天，任务详情是验收标准中权重最高的体验；这项工作已阻塞「评论区组件开发」2 天，娃弟明早需要结论。","steps":["上午 10:00 前完成属性栏信息分层（约 90 分钟专注时段）","下午 3 点与娃弟花 15 分钟对齐评论与活动的合并方案","完成后解除阻塞标记，系统将自动通知娃弟"],"ev":[["任务","「设计任务详情抽屉交互稿」P0 · 已阻塞下游任务 2 天"],["日程","明天 10:00 与娃弟有「原型评审」，需要今天产出结论"],["目标","「发布 WAO 1.0」进度 62%，落后时间基线约 8%"]]}'),
('s2', 2, '{"id":"s2","task":"t7","title":"给「健康重启计划」安排一次弥补性打卡","why":"该项目已标记「有风险」：过去 3 天没有任何运动记录，本周完成率 25%，低于 60% 的目标线。今晚 19:30 后你没有其他日程。","steps":["把今晚 19:30 的夜跑降级为 30 分钟低强度快走，先恢复节奏","结束后在「健康重启」日志里记录主观疲劳度","明早 8:00 提醒你决定是否恢复原训练计划"],"ev":[["项目","「健康重启计划」连续 3 天无记录，状态：有风险"],["日程","今晚 19:30–21:00 无安排"],["目标","「健康重启」季度目标完成率 45%"]]}'),
('s3', 3, '{"id":"s3","task":"t2","title":"用 30 分钟清掉两条「等待他人」事项","why":"「评论区组件开发」与「图标方案拍板」都已等待超过 24 小时；再拖会影响 7 月 28 日的里程碑「核心页面交互稿」。","steps":["把任务详情结论整理成 3 行摘要，@娃弟 发到项目动态","把图标方案 A/B 对比图发给娃弟，约定今晚 22:00 前回复","把两条事项的截止统一改到 7 月 24 日"],"ev":[["任务","2 条事项等待回应超过 24 小时"],["里程碑","「核心页面交互稿」7 月 28 日到期"],["成员","娃弟今天 20:00 后在线概率高（近两周规律）"]]}');

INSERT INTO goal (id, ord, payload) VALUES
('g_career', 1, '{"id":"career","name":"发布 WAO 1.0","color":"#4358D6","pct":62}'),
('g_health', 2, '{"id":"health","name":"健康重启","color":"#2C8A5B","pct":45}');
