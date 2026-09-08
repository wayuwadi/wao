import type { ModuleId } from '@/types'

export type NavigationCatalogModuleId = Exclude<
  ModuleId,
  'home' | 'notes' | 'navigation' | 'settings' | 'chat' | 'chat-reviews'
>

export interface NavigationCatalogSection {
  id: string
  label: string
  description: string
  icon: string
  items: Array<{ id: NavigationCatalogModuleId; description: string; keywords?: string[] }>
}

/**
 * 与侧栏显隐无关的完整功能目录。新增真实模块时必须在这里归类，避免入口隐藏后无人再能找到。
 * 已融合到其它页面的兼容跳转（例如聊天复盘）不重复列卡片。
 */
export const NAVIGATION_CATALOG: NavigationCatalogSection[] = [
  {
    id: 'collaboration',
    label: '协作与执行',
    description: '把想法变成项目、任务和持续协作。',
    icon: 'ri-team-line',
    items: [
      {
        id: 'projects',
        description: '管理项目、任务、成员和 AI 工作流。',
        keywords: ['任务', '团队'],
      },
      {
        id: 'updates',
        description: '按时间线查看 WAO 已经完成并验收的功能变化。',
        keywords: ['更新', '日记', '版本', '发布'],
      },
      {
        id: 'ideas',
        description: '归并旧版点子档案；新想法直接进入项目任务。',
        keywords: ['历史点子', '灵感'],
      },
      { id: 'todo', description: '集中处理个人待办和需要跟进的事项。', keywords: ['清单'] },
      { id: 'calendar', description: '按日、周、月安排任务和时间段。', keywords: ['日程'] },
    ],
  },
  {
    id: 'planning',
    label: '规划与复盘',
    description: '安排今天，也保留长期行动与回顾。',
    icon: 'ri-route-line',
    items: [
      {
        id: 'workday-rhythm',
        description: '规划工作日节奏，并记录每天的真实完成情况。',
        keywords: ['今天'],
      },
      { id: 'action-plans', description: '维护需要长期推进的行动计划和打卡。', keywords: ['计划'] },
      {
        id: 'decisions',
        description: '记录在相似情境中应如何选择，并保留决定依据。',
        keywords: ['决定', '选择', '经验'],
      },
      {
        id: 'appearance',
        description: '管理个人电子衣库、实拍、穿搭、个人档案和穿着复盘。',
        keywords: ['衣服', '上衣', '裤子', '鞋子', '外套', '床品', '床单被罩', '搭配', '衣柜'],
      },
      {
        id: 'food',
        description: '安排工作日与休息日三餐，记录实际饮食、餐后感受和备餐事项。',
        keywords: ['三餐', '吃饭', '备餐', '黄帝内经'],
      },
      {
        id: 'workspaces',
        description: '整理个人项目常用链接与工作资料。',
        keywords: ['链接', '空间'],
      },
      { id: 'weekly-report', description: '根据真实工作记录生成和保存周报。', keywords: ['总结'] },
      { id: 'life-review', description: '沿时间线回看重要经历、变化和结论。', keywords: ['人生'] },
    ],
  },
  {
    id: 'knowledge',
    label: '知识与记忆',
    description: '沉淀可核对的资料、经验和学习记录。',
    icon: 'ri-book-open-line',
    items: [
      { id: 'knowledge', description: '按知识库、文件夹和文档组织长期资料。', keywords: ['文档'] },
      {
        id: 'memory',
        description: '管理个人、组织、项目、机器与应用记忆，并连接 Codex MCP。',
        keywords: ['mcp', 'codex', '上下文'],
      },
      { id: 'english-learning', description: '使用真实学习材料完成英语训练。', keywords: ['英语'] },
      { id: 'vocabulary', description: '收集单词、释义和复习进度。', keywords: ['背词'] },
    ],
  },
  {
    id: 'creation',
    label: '创作与自动化',
    description: '连接能力、制作内容并把重复工作自动化。',
    icon: 'ri-sparkling-line',
    items: [
      {
        id: 'skills',
        description: '发现、安装和管理经过审核的 Skill。',
        keywords: ['插件', '能力'],
      },
      {
        id: 'skill-connections',
        description: '查看具备真实接口和授权条件的外部应用接入。',
        keywords: ['应用', '连接'],
      },
      { id: 'drawings', description: '创建和整理草图。', keywords: ['画图'] },
      { id: 'files', description: '管理真实文件、图片和分享地址。', keywords: ['图床', '上传'] },
      { id: 'automation', description: '配置自动任务并查看运行结果。', keywords: ['流程'] },
      {
        id: 'crawler-capabilities',
        description: '集中管理后续自动化任务可引用的真实爬虫源码。',
        keywords: ['爬虫', '脚本', '采集'],
      },
      {
        id: 'account-resources',
        description: '共享管理平台账号，并把手机验证码转发给飞书和爬虫。',
        keywords: ['账号', '手机号', '验证码', '短信'],
      },
      {
        id: 'text-polish',
        description: '用可编辑的方法提示词润色长文，并回看每次前后对照。',
        keywords: ['润色', '改写', '表达'],
      },
    ],
  },
  {
    id: 'system',
    label: 'WAO 与系统',
    description: '查看 AI、系统运行情况和辅助工具。',
    icon: 'ri-dashboard-3-line',
    items: [
      {
        id: 'data-center',
        description: '盘点 WAO 已保存的数据、记录量、最近写入时间与采集边界。',
        keywords: ['数据源', '目录', '采集'],
      },
      {
        id: 'dashboard',
        description: '查看 AI 用量、模型和真实调用统计。',
        keywords: ['统计', '额度'],
      },
      { id: 'system-reports', description: '查看系统定期报告和运行结论。', keywords: ['报告'] },
      {
        id: 'request-monitoring',
        description: '检查接口请求、性能与失败情况。',
        keywords: ['监控', '性能'],
      },
      {
        id: 'devices',
        description: '检测本机摄像头和麦克风，并完成实时预览与连通调试。',
        keywords: ['摄像头', '麦克风', '相机', '音频'],
      },
      { id: 'vault', description: '安全管理需要按需查看的账号资料。', keywords: ['密码'] },
      { id: 'games', description: '打开使用服务端真实存档的小游戏。', keywords: ['游戏'] },
      { id: 'tianren', description: '创建和回看天人一卦记录。', keywords: ['每日一卦'] },
    ],
  },
]
