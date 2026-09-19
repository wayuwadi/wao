import type { ModuleId } from '@/types'
import { MODULES, type NavItemId } from './nav'
import type { NavigationLayout } from './navigationLayout'

export type NavigationCatalogModuleId = Exclude<ModuleId, 'navigation' | 'chat' | 'chat-reviews'>

export interface NavigationCatalogSection {
  id: string
  label: string
  description: string
  icon: string
  items: Array<{
    id: NavigationCatalogModuleId
    description: string
    keywords?: string[]
    preview?: 'planned' | 'demo'
  }>
}

/**
 * 与侧栏显隐无关的完整功能目录。新增真实模块时必须在这里归类，避免入口隐藏后无人再能找到。
 * 已融合到其它页面的兼容跳转（例如聊天复盘）不重复列卡片。
 */
const CATALOG_DETAILS: NavigationCatalogSection['items'] = [
  {
    id: 'chatgpt',
    description: '直接提问、继续日常对话，聊天记录保存在个人历史中。',
    keywords: ['ChatGPT', '问答', '聊天'],
  },
  {
    id: 'ai-sessions',
    description: '创建 AI 任务、查看执行结果，或继续已有任务的对话。',
    keywords: ['Codex', 'session', 'AI Run', 'AiRun', '会话', 'AI任务', 'AI 任务', '执行历史'],
  },
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
    id: 'changelog',
    preview: 'demo',
    description: '示例预览：查看更新日志样式；真实交付请打开「更新日记」。',
    keywords: ['Changelog', '更新日志', '版本', '订阅'],
  },
  {
    id: 'ideas',
    description: '归并旧版点子档案；新想法直接进入项目任务。',
    keywords: ['历史点子', '灵感'],
  },
  { id: 'todo', description: '集中处理个人待办和需要跟进的事项。', keywords: ['清单'] },
  { id: 'calendar', description: '按日、周、月安排任务和时间段。', keywords: ['日程'] },
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
  { id: 'knowledge', description: '按知识库、文件夹和文档组织长期资料。', keywords: ['文档'] },
  {
    id: 'memory',
    description: '记下希望 AI 参考的偏好、背景和经验，管理共享记忆与 AI 读取权限。',
    keywords: ['mcp', 'codex', '上下文'],
  },
  { id: 'english-learning', description: '使用真实学习材料完成英语训练。', keywords: ['英语'] },
  { id: 'vocabulary', description: '收集单词、释义和复习进度。', keywords: ['背词'] },
  {
    id: 'skills',
    description: '在 WAO-HUB 发现 Skill，创造、上传和分享宠物。',
    keywords: ['插件', '能力', '宠物', 'Pet', '仓库', 'HUB'],
  },
  {
    id: 'skill-connections',
    preview: 'planned',
    description: '规划中：外部应用授权尚未开放，当前只能查看接入规划。',
    keywords: ['应用', '连接'],
  },
  { id: 'drawings', description: '创建和整理草图。', keywords: ['画图'] },
  { id: 'files', description: '管理真实文件、图片和分享地址。', keywords: ['文件库', '图片库', '图床', '上传', '上传文件', '上传图片', '分享文件'] },
  { id: 'automation', description: '配置自动任务并查看运行结果。', keywords: ['流程', '定时任务', '自动任务', '自动化任务'] },
  {
    id: 'crawler-capabilities',
    description: '集中管理后续自动化任务可引用的真实爬虫源码。',
    keywords: ['爬虫', '脚本', '采集'],
  },
  {
    id: 'account-resources',
    description: '集中管理团队共用的平台账号、登录手机号和短信验证码。',
    keywords: ['账号', '共享账号', '团队账号', '平台账号', '手机号', '验证码', '短信'],
  },
  {
    id: 'text-polish',
    description: '用可编辑的方法提示词润色长文，并回看每次前后对照。',
    keywords: ['润色', '改写', '表达'],
  },
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
    id: 'infrastructure',
    description: '查看三台服务器的资源余量、部署应用与历史趋势。',
    keywords: ['基础设施', '服务器', 'Linux', 'Jenkins', 'JumpServer', 'WeKnora', '磁盘', '内存'],
  },
  {
    id: 'devices',
    description: '检测本机摄像头和麦克风，并完成实时预览与连通调试。',
    keywords: ['摄像头', '麦克风', '相机', '音频', '测试摄像头', '测试麦克风', '检测摄像头', '检测麦克风'],
  },
  { id: 'vault', description: '安全管理需要按需查看的账号资料。', keywords: ['密码'] },
  { id: 'games', description: '打开使用服务端真实存档的小游戏。', keywords: ['游戏'] },
  {
    id: 'pet-world',
    description: '和张三丰一起探索松风庭院，位置保存在本浏览器。',
    keywords: ['宠物', '小世界', '3D', '张三丰'],
  },
  { id: 'tianren', description: '创建和回看天人一卦记录。', keywords: ['每日一卦'] },
]

/** 功能归属只有这一份；个人菜单分组可以任意命名，互不改变。 */
const DOMAINS: Array<{
  id: string
  label: string
  icon: string
  description: string
  ids: NavigationCatalogModuleId[]
}> = [
  {
    id: 'today',
    label: '今日',
    icon: 'ri-sun-line',
    description: '记录、安排与处理今天的事。',
    ids: ['home', 'notes', 'todo', 'calendar', 'workday-rhythm'],
  },
  {
    id: 'projects',
    label: '项目',
    icon: 'ri-stack-line',
    description: '推进项目，回顾行动与成果。',
    ids: ['projects', 'action-plans', 'weekly-report', 'workspaces', 'ideas'],
  },
  {
    id: 'knowledge',
    label: '知识',
    icon: 'ri-book-open-line',
    description: '资料、文件与可复用的经验。',
    ids: [
      'knowledge',
      'memory',
      'files',
      'drawings',
      'decisions',
      'english-learning',
      'vocabulary',
    ],
  },
  {
    id: 'ai',
    label: 'AI 与自动化',
    icon: 'ri-sparkling-line',
    description: '对话、执行与自动化工作。',
    ids: [
      'chatgpt',
      'ai-sessions',
      'text-polish',
      'automation',
      'skills',
      'skill-connections',
      'crawler-capabilities',
    ],
  },
  {
    id: 'life',
    label: '生活',
    icon: 'ri-heart-line',
    description: '日常生活、个人回顾与休闲。',
    ids: ['appearance', 'food', 'life-review', 'games', 'pet-world', 'tianren'],
  },
  {
    id: 'system',
    label: '系统与资源',
    icon: 'ri-server-line',
    description: '账号资源、服务运行与数据。',
    ids: [
      'infrastructure',
      'dashboard',
      'request-monitoring',
      'system-reports',
      'data-center',
      'account-resources',
      'vault',
      'devices',
    ],
  },
  {
    id: 'help',
    label: '设置与帮助',
    icon: 'ri-settings-3-line',
    description: '个人偏好与产品更新。',
    ids: ['settings', 'updates', 'changelog'],
  },
]
const details = new Map(CATALOG_DETAILS.map((item) => [item.id, item]))
export const NAVIGATION_CATALOG: NavigationCatalogSection[] = DOMAINS.map((domain) => ({
  ...domain,
  items: domain.ids.map(
    (id) =>
      details.get(id) ?? {
        id,
        description:
          (
            {
              home: '查看今日概览，继续手头的事。',
              notes: '随手记录想法，整理或转为项目任务。',
              settings: '管理个人导航、账号与使用偏好。',
            } as Partial<Record<ModuleId, string>>
          )[id] ?? MODULES[id].label,
      },
  ),
}))
export function functionDomain(id: NavItemId) {
  return NAVIGATION_CATALOG.find((section) => section.items.some((item) => item.id === id))
}
/** 目录与全局搜索共用用途说明，避免搜索结果只告诉用户入口放在哪里。 */
export function functionDescription(id: NavItemId): string | undefined {
  return functionDomain(id)?.items.find((item) => item.id === id)?.description
}
export function matchesFunction(id: NavItemId, label: string, query: string): boolean {
  const domain = functionDomain(id)
  const item = domain?.items.find((item) => item.id === id)
  const meta = MODULES[id as ModuleId]
  const aliases =
    id === 'workspaces'
      ? ['工作台', '链接空间']
      : id === 'navigation'
        ? ['工作台', '全部功能', '菜单']
        : []
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  const haystack = [
    id,
    label,
    meta?.label,
    meta?.path,
    domain?.label,
    item?.description,
    ...(item?.keywords ?? []),
    ...aliases,
  ]
    .join(' ')
    .toLocaleLowerCase()
  return words.every((word) => haystack.includes(word))
}
export function menuLocation(id: NavItemId, layout: NavigationLayout): string {
  const item = layout.items.find((item) => item.id === id)
  if (!item) return id === 'settings' ? '固定设置入口' : '全部功能'
  if (!item.visible) return '未固定 · 可直接打开'
  if (item.placement === 'TOP') return '顶部菜单'
  if (item.placement === 'DOCK') return '底部停靠'
  return (
    '左侧菜单' +
    (item.group === 'ROOT'
      ? ''
      : ' · ' + (layout.groups.find((g) => g.id === item.group)?.label ?? item.group))
  )
}
/** 推荐方案只改草稿中的左侧归属；顶部、停靠、显隐和用户命名原样保留。 */
export function recommendedLayout(current: NavigationLayout): NavigationLayout {
  const next = JSON.parse(JSON.stringify(current)) as NavigationLayout
  for (const domain of DOMAINS) {
    const id = 'domain:' + domain.id
    if (!next.groups.some((group) => group.id === id)) next.groups.push({ id, label: domain.label })
  }
  next.items = next.items.map((item) => {
    const domain = functionDomain(item.id)
    return domain && item.placement === 'MENU' && item.group !== 'ROOT'
      ? { ...item, group: 'domain:' + domain.id }
      : item
  })
  next.groups = next.groups.filter(
    (group) => group.id === 'ROOT' || next.items.some((item) => item.group === group.id),
  )
  return next
}
