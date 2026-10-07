export const SETTINGS_CATEGORIES = [
  { id: 'common', label: '常用设置', icon: 'ri-star-smile-line', description: '常用的放在手边，偶尔才用的收进分类。' },
  { id: 'appearance', label: '外观与陪伴', icon: 'ri-palette-line', description: '主题、伙伴和界面习惯。' },
  { id: 'notes', label: '小记与 AI', icon: 'ri-quill-pen-line', description: '记录之后，让 AI 按你的习惯帮忙。' },
  { id: 'reminders', label: '通知与提醒', icon: 'ri-notification-3-line', description: '先选提醒来源，再调整具体内容。' },
  { id: 'personal', label: '个人与账号', icon: 'ri-user-smile-line', description: '资料、登录方式和我的导航。' },
  { id: 'management', label: '管理与连接', icon: 'ri-settings-4-line', description: '组织、AI 账号和外部连接。' },
] as const

export const SETTINGS_ENTRIES = {
  appearance: [['pet', '伙伴设置', '外观与互动', 'ri-bear-smile-line']],
  reminders: [
    ['notification-policy', '站内与共享通知', '我的站内偏好 · 组织共享策略', 'ri-chat-3-line'],
    ['project-notifications', '项目通知', '按项目调整', 'ri-folder-3-line'],
    ['tianren-notifications', '天人提醒', '独立设置提醒方式', 'ri-leaf-line'],
  ],
  personal: [
    ['profile', '个人资料', '名称与头像文字', 'ri-user-smile-line'],
    ['account', '账号与登录', '登录方式、邮箱与密码', 'ri-shield-user-line'],
    ['navigation', '我的导航', '常用入口与外部链接', 'ri-compass-3-line'],
  ],
  management: [
    ['members', '组织与成员', '组织资料与真实成员', 'ri-community-line'],
    ['ai-accounts', 'AI 账号', '已连接的 AI 服务', 'ri-robot-2-line'],
    ['webhook', 'Webhook 配置', '前往能力中心统一管理机器人', 'ri-links-line'],
    ['site', '网站信息 · 备案', '网站公开展示信息', 'ri-global-line'],
  ],
} as const

const categoryIds = new Set<string>(SETTINGS_CATEGORIES.map(category => category.id))
const entryCategories = new Map<string, string>(Object.entries(SETTINGS_ENTRIES)
  .flatMap(([category, entries]) => entries.map(entry => [entry[0], category] as [string, string])))

/** Keep published settings links usable while moving their panels into categories. */
export function normalizeSettingsTab(value: unknown, hash = ''): string {
  if (['network-outage', 'version', 'wao-ai-capabilities'].includes(value as string)) return 'members'
  if (value === 'prefs') return 'appearance'
  if (value === 'quick-links') return 'navigation'
  if (value === 'notifications') return hash === '#tianren-notifications' ? 'tianren-notifications' : 'notification-policy'
  if (typeof value === 'string' && (categoryIds.has(value) || entryCategories.has(value))) return value
  return 'common'
}

export function settingsCategory(tab: string): string {
  return entryCategories.get(tab) ?? normalizeSettingsTab(tab)
}
