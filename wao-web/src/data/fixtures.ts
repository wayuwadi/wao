/**
 * 演示数据 —— 逐字移植自 init-design 原型 Component 构造器。
 * 这是前端阶段的唯一数据来源；后端就绪后由 src/api/* 切换到真实 /api，本文件退役或作为种子。
 */
import type {
  Area,
  AutomationJob,
  CalendarEvent,
  Doc,
  FileItem,
  ImageItem,
  KnowledgeBase,
  Member,
  Org,
  Project,
  ScheduleItem,
  Suggestion,
  Task,
  VaultGroup,
} from '@/types'

/** 演示“今天”。 */
export const TODAY = '2026-07-23'

export const MEMBERS: Member[] = [
  { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' },
  { id: 'wd', name: '娃弟', initial: '弟', color: '#2C8A5B', role: '成员' },
]

export const ORG: Org = { id: 'wao', name: 'Wa Allin One', memberCount: MEMBERS.length }

export const AREAS: Area[] = [
  { id: 'career', name: '事业', color: '#4358D6' },
  { id: 'health', name: '健康', color: '#2C8A5B' },
  { id: 'family', name: '家庭', color: '#B9821F' },
  { id: 'finance', name: '财务', color: '#7A5CC6' },
  { id: 'growth', name: '学习成长', color: '#C05D8E' },
]

export const PROJECTS: Project[] = [
  {
    id: 'p1', name: 'WAO 1.0 原型', area: 'career', status: '进行中', health: '良好', progress: 62,
    owner: 'wg', members: ['wg', 'wd'], start: '2026-06-20', due: '2026-08-15',
    visibility: '组织内 2 人 · 可编辑', updated: '26 分钟前',
    goal: '8 月 15 日前完成可运行、可交互的高保真原型，通过家庭内部验收，并确定 1.0 开发范围。',
    milestones: [
      { name: '信息架构定稿', date: '7月5日', state: 2 },
      { name: '视觉方案定稿', date: '7月18日', state: 2 },
      { name: '核心页面交互稿', date: '7月28日', state: 1 },
      { name: '可用性走查', date: '8月8日', state: 0 },
      { name: '1.0 内部验收', date: '8月15日', state: 0 },
    ],
    msDates: ['2026-07-05', '2026-07-18', '2026-07-28', '2026-08-08', '2026-08-15'],
    trend: [8, 15, 22, 31, 38, 47, 55, 62],
    blockers: [
      { text: '「评论区组件开发」等待任务详情抽屉结论', who: '娃哥', days: '2 天' },
      { text: '导航图标方案 A/B 未拍板', who: '娃弟', days: '1 天' },
    ],
    docs: ['d1', 'd2', 'd3', 'd4'],
    files: [
      { name: '任务详情-线框-v3.fig', size: '2.4 MB', icon: 'ri-pen-nib-line' },
      { name: 'WAO-视觉规范-0718.pdf', size: '8.1 MB', icon: 'ri-file-text-line' },
      { name: '竞品结构对比.xlsx', size: '86 KB', icon: 'ri-table-line' },
    ],
    activity: [
      { icon: 'ri-chat-3-line', color: '#4358D6', text: '娃弟在「设计任务详情抽屉交互稿」下添加了评论', time: '昨天 21:40' },
      { icon: 'ri-loader-4-line', color: '#B9821F', text: '「评论区组件开发」被标记为阻塞，等待交互稿结论', time: '7月21日' },
      { icon: 'ri-links-line', color: '#4358D6', text: '娃哥关联了文档「任务模型与字段定义」', time: '7月20日' },
      { icon: 'ri-flag-2-line', color: '#2C8A5B', text: '里程碑「视觉方案定稿」完成，比计划提前 1 天', time: '7月18日' },
      { icon: 'ri-user-add-line', color: '#2C8A5B', text: '娃弟加入项目，权限为可编辑', time: '6月21日' },
    ],
  },
  {
    id: 'p2', name: '家庭资料归档', area: 'family', status: '进行中', health: '良好', progress: 38,
    owner: 'wd', members: ['wd', 'wg'], start: '2026-05-10', due: '2026-09-30',
    visibility: '组织内 2 人 · 可编辑', updated: '2 小时前',
    goal: '把证件、合同、病历与三千余张老照片全部数字化，建立可检索的家庭档案目录。',
    milestones: [], msDates: [], trend: [4, 9, 15, 20, 26, 31, 35, 38], blockers: [], docs: ['d5', 'd6'],
    files: [{ name: '档案盒编号对照表.xlsx', size: '44 KB', icon: 'ri-table-line' }],
    activity: [{ icon: 'ri-image-line', color: '#B9821F', text: '第 2 批扫描完成：312 张老照片入库', time: '7月19日' }],
  },
  {
    id: 'p3', name: '健康重启计划', area: 'health', status: '有风险', health: '需要关注', progress: 45,
    owner: 'wg', members: ['wg'], start: '2026-06-01', due: '2026-10-01',
    visibility: '仅项目成员可见', updated: '3 天前',
    goal: '12 周重建运动与睡眠习惯：体脂降到 20%，5km 配速进入 6 分钟。',
    milestones: [], msDates: [], trend: [10, 18, 26, 33, 40, 44, 45, 45],
    blockers: [{ text: '连续 3 天未记录训练', who: '娃哥', days: '3 天' }], docs: ['d7'], files: [],
    activity: [{ icon: 'ri-alert-line', color: '#D65454', text: '连续 3 天无训练记录，项目转为「有风险」', time: '今天 06:00' }],
  },
  {
    id: 'p4', name: '个人知识库迁移', area: 'growth', status: '规划中', health: '良好', progress: 12,
    owner: 'wg', members: ['wg'], start: '2026-08-01', due: '2026-11-15',
    visibility: '仅自己可见', updated: '5 天前',
    goal: '把散落在飞书、Notion 与微信收藏的 800+ 条笔记迁入 WAO 知识库并建立标签体系。',
    milestones: [], msDates: [], trend: [0, 0, 2, 4, 6, 8, 10, 12], blockers: [], docs: ['d8'], files: [], activity: [],
  },
  {
    id: 'p5', name: '滇西北家庭旅行', area: 'family', status: '规划中', health: '良好', progress: 8,
    owner: 'wd', members: ['wd', 'wg'], start: '2026-08-20', due: '2026-10-06',
    visibility: '组织内 2 人 · 可编辑', updated: '1 周前',
    goal: '国庆 10 天滇西北环线：路线、住宿、预算与装备全部提前一个月锁定。',
    milestones: [], msDates: [], trend: [0, 0, 0, 2, 3, 5, 6, 8], blockers: [], docs: [], files: [], activity: [],
  },
  {
    id: 'p6', name: '年度保险与体检梳理', area: 'finance', status: '已完成', health: '良好', progress: 100,
    owner: 'wd', members: ['wd', 'wg'], start: '2026-04-01', due: '2026-06-30',
    visibility: '组织内 2 人 · 可编辑', updated: '6月30日',
    goal: '完成全家保单年检与两人年度体检，理赔与报告全部归档。',
    milestones: [], msDates: [], trend: [20, 35, 50, 65, 78, 88, 95, 100], blockers: [], docs: [],
    files: [{ name: '2026 体检报告-娃哥.pdf', size: '3.2 MB', icon: 'ri-file-text-line' }], activity: [],
  },
]

const DEFAULT_ACTIVITY = { icon: 'ri-add-line', color: '#9A9DA9', text: '创建了任务', time: '7月20日' }

/** 原型 rawTasks + Object.assign 默认值。 */
const RAW_TASKS: Array<Partial<Task> & Pick<Task, 'id' | 'title' | 'project' | 'status' | 'pr' | 'owner' | 'due'>> = [
  {
    id: 't1', title: '设计任务详情抽屉交互稿', project: 'p1', status: '进行中', pr: 'P0', owner: 'wg', parts: ['wd'], start: '2026-07-20', due: '2026-07-24',
    desc: '任务详情是 WAO 1.0 验收权重最高的体验。目标：信息完整但不混乱，主区（描述 / 步骤 / 子任务 / 评论）与右侧属性栏分层清晰，所有修改不离开当前上下文。',
    steps: [
      { text: '属性栏信息分层：状态行 / 人员行 / 时间行', done: true },
      { text: '主区块排序：描述 → 步骤 → 子任务 → 附件', done: true },
      { text: '评论与活动时间线的合并方案', done: false },
      { text: '空状态与加载态设计', done: false },
    ],
    subs: [
      { text: '抽屉宽度与窄窗口断点规则', done: true },
      { text: '状态 / 优先级选择器交互', done: false },
      { text: '活动时间线信息密度确认', done: false },
    ],
    files: [
      { name: '任务详情-线框-v3.fig', size: '2.4 MB', icon: 'ri-pen-nib-line' },
      { name: '竞品结构对比.xlsx', size: '86 KB', icon: 'ri-table-line' },
    ],
    docs: ['d1', 'd2'], linkTasks: [{ id: 't2', rel: '被本任务阻塞' }],
    comments: [
      { who: 'wd', time: '昨天 21:40', text: '属性栏建议用我们讨论的三段式：状态行 / 人员行 / 时间行，自定义属性默认折叠。' },
      { who: 'wg', time: '今天 09:15', text: '收到，上午出一版。评论区和活动是否合并，下午对齐。' },
    ],
    activity: [
      { icon: 'ri-chat-3-line', color: '#4358D6', text: '娃弟添加了评论', time: '昨天 21:40' },
      { icon: 'ri-loader-4-line', color: '#B9821F', text: '标记「评论区组件开发」为被阻塞', time: '7月21日' },
      { icon: 'ri-play-line', color: '#4358D6', text: '状态改为进行中', time: '7月21日' },
      { icon: 'ri-add-line', color: '#9A9DA9', text: '娃哥创建了任务', time: '7月20日' },
    ],
    custom: [
      { name: '设计稿链接', value: 'figma.com/wao-task-detail' },
      { name: '验收权重', value: '15%' },
    ],
  },
  { id: 't2', title: '评论区组件开发', project: 'p1', status: '阻塞', pr: 'P1', owner: 'wd', parts: ['wg'], due: '2026-07-29', waiting: '等待娃哥', desc: '依赖任务详情抽屉的评论 / 活动合并结论。' },
  { id: 't3', title: '首页 AI 聚焦文案走查', project: 'p1', status: '待办', pr: 'P2', owner: 'wg', parts: [], due: '2026-07-25' },
  { id: 't4', title: '知识库阅读排版规范', project: 'p1', status: '待办', pr: 'P2', owner: 'wd', parts: [], due: '2026-07-27' },
  { id: 't5', title: '扫描第 3 批老照片（1998–2004）', project: 'p2', status: '进行中', pr: 'P2', owner: 'wd', parts: [], due: '2026-07-23' },
  { id: 't6', title: '证件有效期台账更新', project: 'p2', status: '待办', pr: 'P1', owner: 'wg', parts: [], due: '2026-07-23' },
  { id: 't7', title: '夜跑 5km · 第 18/36 次', project: 'p3', status: '待办', pr: 'P1', owner: 'wg', parts: [], due: '2026-07-23' },
  { id: 't8', title: '预约体态评估复测', project: 'p3', status: '待办', pr: 'P2', owner: 'wg', parts: [], due: '2026-07-31' },
  { id: 't9', title: '制定 8 月饮食模板', project: 'p3', status: '待办', pr: 'P3', owner: 'wd', parts: [], due: '2026-08-03' },
  { id: 't10', title: '整理 Notion 导出清单', project: 'p4', status: '待办', pr: 'P3', owner: 'wg', parts: [], due: '2026-08-10' },
  { id: 't11', title: '香格里拉段路线初稿', project: 'p5', status: '待办', pr: 'P3', owner: 'wd', parts: ['wg'], due: '2026-08-24' },
  { id: 't12', title: '「品牌名讨论」结论沉淀到知识库', project: 'p1', status: '已完成', pr: 'P2', owner: 'wg', parts: [], due: '2026-07-19' },
  { id: 't13', title: '家庭 NAS 目录规范 v2', project: 'p2', status: '待办', pr: 'P2', owner: 'wg', parts: ['wd'], due: '2026-07-30', waiting: '等待娃弟' },
  { id: 't14', title: '导航图标方案 A/B 拍板', project: 'p1', status: '待办', pr: 'P1', owner: 'wd', parts: ['wg'], due: '2026-07-22', waiting: '等待娃弟' },
]

export const TASKS: Task[] = RAW_TASKS.map((t) => ({
  desc: '', steps: [], subs: [], files: [], docs: [], linkTasks: [], comments: [], custom: [], parts: [],
  activity: [{ ...DEFAULT_ACTIVITY }],
  ...t,
})) as Task[]

export const KBS: KnowledgeBase[] = [
  { id: 'k1', name: 'WAO 产品', icon: 'ri-stack-line', color: '#4358D6', project: 'p1', desc: '产品定义、交互规范与评审结论', folders: [{ id: 'f1', name: '产品设计', docs: ['d1', 'd2'] }, { id: 'f2', name: '调研与结论', docs: ['d3', 'd4'] }] },
  { id: 'k2', name: '家庭事务', icon: 'ri-archive-line', color: '#B9821F', project: 'p2', desc: '归档规范、台账与流程说明', folders: [{ id: 'f3', name: '归档规范', docs: ['d5', 'd6'] }] },
  { id: 'k3', name: '个人成长', icon: 'ri-seedling-line', color: '#2C8A5B', project: null, desc: '健康、阅读与长期笔记', folders: [{ id: 'f4', name: '健康', docs: ['d7'] }, { id: 'f5', name: '学习笔记', docs: ['d8'] }] },
  { id: 'k4', name: '群聊沉淀', icon: 'ri-flashlight-line', color: '#7A5CC6', project: null, auto: true, desc: '飞书群聊每日自动同步沉淀', folders: [{ id: 'f6', name: '2026 年 7 月', docs: ['d9', 'd10'] }] },
]

export const DOCS: Record<string, Doc> = {
  d1: {
    id: 'd1', title: '任务模型与字段定义', author: 'wg', created: '7月12日', updated: '今天 09:40',
    links: [{ type: 'project', id: 'p1' }, { type: 'task', id: 't1' }],
    blocks: [
      { call: '本文是「WAO 1.0 原型」任务体系的单一事实源。字段增删需在周评审会同步，并回写到原型标注。' },
      { h: '核心字段' },
      { p: '任务是 WAO 中最小的可执行单元。字段设计原则：默认极简，复杂度通过自定义属性按需扩展。' },
      { ul: ['状态机：待办 → 进行中 → 已完成；「阻塞」是标记而非状态，解除后回到原状态', '优先级：P0 当天必须处理；P1 影响里程碑；P2 常规；P3 有空再说', '时间：开始 / 截止分离，支持仅有截止；逾期在所有视图中红色高亮', '人员：负责人唯一、参与人多选；「等待他人」由参与人 + 阻塞标记推导'] },
      { h: '关联模型' },
      { p: '任务与文档、项目、日程之间都是双向链接：在任一侧建立关联，另一侧自动出现反向链接。这是「同一个上下文网络」的基础。' },
      { ul: ['任务 ↔ 文档：需求、结论、复盘互相引用', '任务 ↔ 任务：阻塞 / 被阻塞、前置 / 后置', '任务 → 日程：拖入日历即生成时间块'] },
      { h: '自定义属性' },
      { p: '每个项目可为任务追加属性（文本 / 数字 / 单选 / 链接）。属性属于项目模板，不污染全局字段表。' },
      { q: ['字段可以少，但每个字段都要回答一个真实的问题。', '娃弟 · 7月10日群聊'] },
    ],
    backlinks: [
      { type: 'task', id: 't1', snippet: '交互稿中的属性栏分层引用了本文「核心字段」' },
      { type: 'doc', id: 'd2', snippet: '交互规范 3.2 节引用了状态机定义' },
      { type: 'project', id: 'p1', snippet: '项目总览「关联知识」引用本文' },
    ],
  },
  d2: { id: 'd2', title: 'WAO 交互规范 v0.9', author: 'wg', created: '7月8日', updated: '昨天 18:20', links: [{ type: 'project', id: 'p1' }], blocks: [{ p: '覆盖导航、弹层、抽屉、快捷键与反馈时长的统一约定，随原型迭代持续修订。' }, { h: '弹层层级' }, { ul: ['抽屉 > 弹窗 > 下拉 > 提示，同层级只允许一个实例', 'Esc 逐层关闭；遮罩点击只关闭最上层', '全屏庆祝动画不阻塞任何操作，1–2 秒内自行结束'] }], backlinks: [{ type: 'task', id: 't1', snippet: '抽屉宽度断点引用 2.1 节' }] },
  d3: { id: 'd3', title: '竞品体验拆解：项目聚合', author: 'wd', created: '7月2日', updated: '7月15日', links: [{ type: 'project', id: 'p1' }], blocks: [{ p: '只吸收「信息聚合、层次清楚、上下文完整」的体验，不照抄任何产品的界面与品牌。' }, { ul: ['项目总览应回答三件事：现在怎么样、谁在做、卡在哪', '动态流比报表更能传达项目状态', '知识与任务离得越近，沉淀率越高'] }], backlinks: [] },
  d4: { id: 'd4', title: '品牌名与定位结论', author: 'wg', created: '6月28日', updated: '7月19日', links: [{ type: 'project', id: 'p1' }], blocks: [{ p: 'WAO = Wayuwa All in One。定位：面向少数成员长期共同维护的 Life OS。' }, { q: ['All in One 不是把功能挤进一个页面，而是让所有内容处于同一个上下文网络。', '评审会结论 · 7月19日'] }], backlinks: [{ type: 'task', id: 't12', snippet: '由任务「品牌名讨论结论沉淀」产出' }] },
  d5: { id: 'd5', title: '家庭资料归档目录规范', author: 'wd', created: '5月12日', updated: '7月19日', links: [{ type: 'project', id: 'p2' }], blocks: [{ p: '四层目录：人 / 类型 / 年份 / 批次。文件名规则：日期-主题-编号。' }, { ul: ['证件类：正反面合并为单个 PDF，重命名后入库', '照片类：按扫描批次入库，EXIF 写入原始年代', '病历类：按人归档，就诊时间倒序'] }], backlinks: [{ type: 'task', id: 't5', snippet: '第 3 批扫描按本规范执行' }] },
  d6: { id: 'd6', title: '证件与合同台账说明', author: 'wg', created: '5月20日', updated: '7月10日', links: [{ type: 'project', id: 'p2' }], blocks: [{ p: '台账覆盖有效期、存放位置与更新责任人；到期前 60 天由自动化「证件到期扫描提醒」推送。' }], backlinks: [] },
  d7: { id: 'd7', title: '健康重启：训练与恢复笔记', author: 'wg', created: '6月1日', updated: '7月20日', links: [{ type: 'project', id: 'p3' }], blocks: [{ p: '记录每次训练的负荷与主观疲劳（RPE），每周日复盘并调整下周计划。' }, { ul: ['第 6 周结论：睡眠不足 6.5h 时，改为低强度有氧', '跑步机配速与户外配速差约 20 秒 / 公里', '恢复日拉伸清单固定为 8 个动作'] }], backlinks: [{ type: 'task', id: 't7', snippet: '今晚打卡后补记 RPE' }] },
  d8: { id: 'd8', title: '阅读笔记：卡片盒笔记法', author: 'wg', created: '6月15日', updated: '7月5日', links: [{ type: 'project', id: 'p4' }], blocks: [{ p: '迁移知识库前先定标签体系：领域 / 项目 / 状态三轴，避免文件夹深层嵌套。' }], backlinks: [] },
  d9: { id: 'd9', title: '群聊沉淀 · 7月23日', author: 'auto', auto: true, created: '今天 06:12', updated: '今天 06:12', links: [{ type: 'project', id: 'p1' }], blocks: [{ call: '由自动化「飞书群聊 → 知识库沉淀」于每日 06:00 生成，已按话题聚类。原始聊天记录保留 90 天。' }, { h: '昨日讨论摘要' }, { ul: ['任务详情：倾向「主区 + 右侧属性栏」结构；评论与活动是否合并，娃哥今天给结论', '图标方案：娃弟倾向 B（线性 · 2px 描边），待正式拍板', '周末安排：周六上午扫描第 3 批照片，下午户外 10km'] }], backlinks: [] },
  d10: { id: 'd10', title: '群聊沉淀 · 7月22日', author: 'auto', auto: true, created: '昨天 06:09', updated: '昨天 06:09', links: [], blocks: [{ h: '昨日讨论摘要' }, { ul: ['原型评审改到周四 09:30，地点：书房', '体检报告已归档到「年度保险与体检梳理」', '确认 8 月旅行请假区间：9月28日–10月7日'] }], backlinks: [] },
}

export const AUTOS: AutomationJob[] = [
  { id: 'j1', name: '飞书群聊 → 知识库沉淀', icon: 'ri-chat-3-line', schedule: '每日 06:00', status: '正常', last: '今天 06:12 · 归档 12 条讨论', desc: '把「娃屋日常」群的讨论按话题聚类，沉淀为可检索的长期知识。', source: '飞书群「娃屋日常」', target: '知识库 · 群聊沉淀', steps: ['拉取近 24 小时群消息', '按话题聚类并生成摘要', '写入「群聊沉淀」并建立与项目 / 任务的关联'], runs: [['今天 06:12', '成功 · 归档 12 条'], ['昨天 06:09', '成功 · 归档 8 条'], ['7月21日 06:10', '成功 · 归档 15 条']] },
  { id: 'j2', name: '手机相册 → 家庭 NAS 备份', icon: 'ri-image-line', schedule: '每日 02:00', status: '正常', last: '今天 02:03 · 备份 214 张', desc: '两台手机的新增照片自动归档到 NAS，并写入图床索引。', source: '娃哥 / 娃弟 手机相册', target: '家庭 NAS · /photos', steps: ['扫描两台手机新增照片', '按人物 / 年份归档到 NAS', '生成缩略图并更新图床索引'], runs: [['今天 02:03', '成功 · 214 张'], ['昨天 02:02', '成功 · 96 张'], ['7月21日 02:04', '成功 · 180 张']] },
  { id: 'j3', name: '账单邮件 → 财务归档', icon: 'ri-mail-line', schedule: '每周一 09:00', status: '待修复', last: '7月20日 失败 · 邮箱授权过期', desc: '抓取账单邮件，解析金额与账期，自动归档到财务领域。', source: '邮箱 bill@wayuwa.example', target: '文件 · 财务归档', steps: ['抓取账单类新邮件', '解析金额、账期与商户', '归档并更新台账'], runs: [['7月20日 09:00', '失败 · 授权过期'], ['7月13日 09:01', '成功 · 6 封'], ['7月6日 09:00', '成功 · 4 封']] },
  { id: 'j4', name: '证件到期扫描提醒', icon: 'ri-alarm-line', schedule: '每月 1 日', status: '正常', last: '7月1日 · 无即将到期证件', desc: '扫描证件台账，到期前 60 天在首页与飞书提醒责任人。', source: '知识库 · 证件与合同台账', target: '首页提醒 + 飞书通知', steps: ['读取台账有效期字段', '筛选 60 天内到期条目', '生成提醒并 @ 责任人'], runs: [['7月1日 08:00', '成功 · 无到期项'], ['6月1日 08:00', '成功 · 提醒 1 项'], ['5月1日 08:00', '成功 · 无到期项']] },
]

export const FILES: FileItem[] = [
  { name: 'WAO-视觉规范-0718.pdf', size: '8.1 MB', icon: 'ri-file-text-line', time: '7月18日', by: '娃哥', link: 'https://f.wayuwa.example/dl/9f2k1/WAO-视觉规范-0718.pdf' },
  { name: '任务详情-线框-v3.fig', size: '2.4 MB', icon: 'ri-pen-nib-line', time: '7月16日', by: '娃哥', link: 'https://f.wayuwa.example/dl/x83nd/任务详情-线框-v3.fig' },
  { name: '2026-证件扫描-批次03.zip', size: '1.2 GB', icon: 'ri-file-zip-line', time: '今天 11:20', by: '娃弟', link: 'https://f.wayuwa.example/dl/72jd8/证件扫描-批次03.zip' },
  { name: '体检报告-娃哥-2026.pdf', size: '3.2 MB', icon: 'ri-file-text-line', time: '6月28日', by: '娃哥', link: 'https://f.wayuwa.example/dl/lm2s9/体检报告-2026.pdf' },
  { name: '滇西北行程预算表.xlsx', size: '96 KB', icon: 'ri-table-line', time: '7月10日', by: '娃弟', link: 'https://f.wayuwa.example/dl/qp1aa/滇西北行程预算表.xlsx' },
]

export const IMGS: ImageItem[] = [
  { name: '滇西北路线-手绘草图.png', size: '2.8 MB', time: '7月12日', from: '项目 · 滇西北家庭旅行', url: 'https://i.wayuwa.example/8f3a2c.png', c1: '#E8ECFA', c2: '#DDE3F6' },
  { name: '任务详情-截图-0722.png', size: '640 KB', time: '昨天', from: '任务 · 任务详情抽屉', url: 'https://i.wayuwa.example/2b9d1e.png', c1: '#EAF4EE', c2: '#DEEEE5' },
  { name: '老照片-1999-全家福.jpg', size: '1.9 MB', time: '7月19日', from: '项目 · 家庭资料归档', url: 'https://i.wayuwa.example/5c2f77.jpg', c1: '#F7EFE2', c2: '#F0E5D0' },
  { name: '白板-年度规划-导出.png', size: '1.1 MB', time: '7月5日', from: '白板 · 年度规划', url: 'https://i.wayuwa.example/91ac3b.png', c1: '#F3ECF8', c2: '#EAE0F2' },
  { name: '评审会-白板拍照.jpg', size: '2.2 MB', time: '7月18日', from: '知识库 · WAO 产品', url: 'https://i.wayuwa.example/c7d940.jpg', c1: '#FDEEEE', c2: '#F6E2E2' },
  { name: '跑步路线-截图.png', size: '380 KB', time: '7月15日', from: '项目 · 健康重启计划', url: 'https://i.wayuwa.example/44e0b2.png', c1: '#E9F1F7', c2: '#DCE9F2' },
]

export const VAULT: VaultGroup[] = [
  { id: 'vg1', group: '家庭网络', items: [
    { id: 'vi1', name: '家庭 Wi-Fi', icon: 'ri-wifi-line', user: 'wayuwa-home', site: '192.168.1.1', note: '路由器管理密码单独存放；访客网络每季度更换。', updated: '6月12日', hasSecret: false },
    { id: 'vi2', name: 'NAS 管理后台', icon: 'ri-hard-drive-2-line', user: 'admin@nas.local', site: 'nas.local:5001', note: '开启了两步验证，恢复代码在保险柜。', updated: '5月30日', hasSecret: false },
  ] },
  { id: 'vg2', group: '生活缴费', items: [
    { id: 'vi3', name: '水电煤缴费账户', icon: 'ri-flashlight-line', user: '138****0000', site: '95598.example', note: '', updated: '4月18日', hasSecret: false },
  ] },
  { id: 'vg3', group: '订阅服务', items: [
    { id: 'vi4', name: '流媒体家庭组', icon: 'ri-movie-2-line', user: 'family@wayuwa.example', site: 'tv.example.com', note: '家庭组 4 人位，娃弟管理续费。', updated: '7月2日', hasSecret: false },
    { id: 'vi5', name: '云相册会员', icon: 'ri-image-line', user: 'wd@wayuwa.example', site: 'photos.example.com', note: '', updated: '6月20日', hasSecret: false },
  ] },
]

export const CALDAV: CalendarEvent[] = [
  { date: '2026-07-24', title: '原型评审（工作）', color: '#C05D8E' },
  { date: '2026-07-25', title: '家庭聚餐 · 爸妈家', color: '#0E8C8C' },
  { date: '2026-07-28', title: '物业费缴纳提醒', color: '#0E8C8C' },
  { date: '2026-08-01', title: '爷爷生日', color: '#0E8C8C' },
  { date: '2026-08-05', title: '牙科复诊（工作日历）', color: '#C05D8E' },
]

export const SCHED: ScheduleItem[] = [
  { time: '09:30', title: 'WAO 原型评审 · 与娃弟', sub: '会议 · 1 小时', color: '#4358D6' },
  { time: '14:00', title: '整理家庭档案 · 第 3 批扫描', sub: '家庭资料归档', color: '#B9821F' },
  { time: '19:30', title: '夜跑 5km · 第 18 次', sub: '健康重启计划', color: '#2C8A5B' },
]

export const SUGG: Suggestion[] = [
  {
    id: 's1', task: 't1', title: '今天优先完成「任务详情抽屉」的交互稿',
    why: '「WAO 1.0 原型」距 8 月 15 日验收还有 23 天，任务详情是验收标准中权重最高的体验；这项工作已阻塞「评论区组件开发」2 天，娃弟明早需要结论。',
    steps: ['上午 10:00 前完成属性栏信息分层（约 90 分钟专注时段）', '下午 3 点与娃弟花 15 分钟对齐评论与活动的合并方案', '完成后解除阻塞标记，系统将自动通知娃弟'],
    ev: [['任务', '「设计任务详情抽屉交互稿」P0 · 已阻塞下游任务 2 天'], ['日程', '明天 10:00 与娃弟有「原型评审」，需要今天产出结论'], ['目标', '「发布 WAO 1.0」进度 62%，落后时间基线约 8%']],
  },
  {
    id: 's2', task: 't7', title: '给「健康重启计划」安排一次弥补性打卡',
    why: '该项目已标记「有风险」：过去 3 天没有任何运动记录，本周完成率 25%，低于 60% 的目标线。今晚 19:30 后你没有其他日程。',
    steps: ['把今晚 19:30 的夜跑降级为 30 分钟低强度快走，先恢复节奏', '结束后在「健康重启」日志里记录主观疲劳度', '明早 8:00 提醒你决定是否恢复原训练计划'],
    ev: [['项目', '「健康重启计划」连续 3 天无记录，状态：有风险'], ['日程', '今晚 19:30–21:00 无安排'], ['目标', '「健康重启」季度目标完成率 45%']],
  },
  {
    id: 's3', task: 't2', title: '用 30 分钟清掉两条「等待他人」事项',
    why: '「评论区组件开发」与「图标方案拍板」都已等待超过 24 小时；再拖会影响 7 月 28 日的里程碑「核心页面交互稿」。',
    steps: ['把任务详情结论整理成 3 行摘要，@娃弟 发到项目动态', '把图标方案 A/B 对比图发给娃弟，约定今晚 22:00 前回复', '把两条事项的截止统一改到 7 月 24 日'],
    ev: [['任务', '2 条事项等待回应超过 24 小时'], ['里程碑', '「核心页面交互稿」7 月 28 日到期'], ['成员', '娃弟今天 20:00 后在线概率高（近两周规律）']],
  },
]

/** 人生领域目标胶囊（首页右上）。 */
export const GOALS = [
  { id: 'career', name: '发布 WAO 1.0', color: '#4358D6', pct: 62 },
  { id: 'health', name: '健康重启', color: '#2C8A5B', pct: 45 },
]
