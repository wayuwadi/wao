import type { ModuleId } from '@/types'
import { MODULES, RETIRED_MODULE_IDS, type NavItemId } from './nav'
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
    id: 'projects',
    description: '管理项目、任务和成员，记录进展与验收结果。',
    keywords: ['任务', '团队', '进展', '验收'],
  },
  {
    id: 'changelog',
    description: '查看当前版本亮点，并按目标探索 WAO 的新旧全部功能。',
    keywords: ['功能探索', '新功能', '版本', '更新介绍'],
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
  {
    id: 'decisions',
    description: '记录在相似情境中应如何选择，并保留决定依据。',
    keywords: ['决定', '选择', '经验'],
  },
  { id: 'life-review', description: '沿时间线回看重要经历、变化和结论。', keywords: ['人生'] },
  { id: 'knowledge', description: '按知识库、文件夹和文档组织长期资料。', keywords: ['文档'] },
  {
    id: 'reference-images',
    description: '收集截图，选图并附一句需求交给 AI 参考。',
    keywords: ['素材库', '视觉库', '灵感', '图片', '截图', '参考图'],
  },
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
  {
    id: 'files',
    description: '管理真实文件、图片和分享地址。',
    keywords: ['文件库', '图片库', '图床', '上传', '上传文件', '上传图片', '分享文件'],
  },
  {
    id: 'automation',
    description: '配置自动任务并查看运行结果。',
    keywords: ['流程', '定时任务', '自动任务', '自动化任务'],
  },
  {
    id: 'text-polish',
    description: '用可编辑的方法提示词润色长文，并回看每次前后对照。',
    keywords: ['润色', '改写', '表达'],
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
  { id: 'vault', description: '安全管理需要按需查看的账号资料。', keywords: ['密码'] },
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
    ids: ['projects', 'ideas'],
  },
  {
    id: 'knowledge',
    label: '知识',
    icon: 'ri-book-open-line',
    description: '资料、文件与可复用的经验。',
    ids: [
      'knowledge',
      'reference-images',
      'files',
      'drawings',
      'decisions',
    ],
  },
  {
    id: 'ai',
    label: 'AI 与自动化',
    icon: 'ri-sparkling-line',
    description: '对话、执行与自动化工作。',
    ids: [
      'text-polish',
      'automation',
      'skills',
      'skill-connections',
    ],
  },
  {
    id: 'life',
    label: '生活',
    icon: 'ri-heart-line',
    description: '日常生活、个人回顾与休闲。',
    ids: [
      'life-review',
      'pet-world',
      'tianren',
    ],
  },
  {
    id: 'system',
    label: '系统与资源',
    icon: 'ri-server-line',
    description: '账号资源、服务运行与数据。',
    ids: [
      'dashboard',
      'request-monitoring',
      'system-reports',
      'vault',
    ],
  },
  {
    id: 'help',
    label: '设置与帮助',
    icon: 'ri-settings-3-line',
    description: '个人偏好与产品更新。',
    ids: ['settings', 'changelog'],
  },
]
const details = new Map(CATALOG_DETAILS.map((item) => [item.id, item]))
export const NAVIGATION_CATALOG: NavigationCatalogSection[] = DOMAINS.map((domain) => ({
  ...domain,
  items: domain.ids.filter((id) => !RETIRED_MODULE_IDS.has(id)).map(
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
