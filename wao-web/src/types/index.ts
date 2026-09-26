/** WAO 核心域类型（会话 / 导航 / 页签）；业务实体见 ./domain。 */
export * from './domain'

/** 组织成员；登录后始终来自后端 /api/members。 */
export interface Member {
  id: string
  name: string
  initial: string
  color: string
  role: string
}

/** 组织。 */
export interface Org {
  id: string
  name: string
  memberCount: number
}

/** 顶部模块 id —— 与左侧导航一一对应。 */
export type ModuleId =
  | 'home'
  | 'ai-sessions'
  | 'chatgpt'
  | 'notes'
  | 'navigation'
  | 'updates'
  | 'changelog'
  | 'projects'
  | 'ideas'
  | 'calendar'
  | 'todo'
  | 'knowledge'
  | 'files'
  | 'reference-images'
  | 'automation'
  | 'crawler-capabilities'
  | 'account-resources'
  | 'games'
  | 'pet-world'
  | 'infrastructure'
  | 'devices'
  | 'request-monitoring'
  | 'data-center'
  | 'text-polish'
  | 'dashboard'
  | 'drawings'
  | 'vocabulary'
  | 'english-learning'
  | 'tianren'
  | 'workday-rhythm'
  | 'action-plans'
  | 'personal-principles'
  | 'decisions'
  | 'appearance'
  | 'food'
  | 'weekly-report'
  | 'life-review'
  | 'chat'
  | 'chat-reviews'
  | 'system-reports'
  | 'skills'
  | 'skill-connections'
  | 'memory'
  | 'workspaces'
  | 'vault'
  | 'ledger'
  | 'settings'

/** 页签种类：模块页 / 项目详情 / 知识库详情。 */
export type TabKind = 'module' | 'project' | 'kb' | 'external'

/** 一个多页签栏中的页签。key 规则：模块=模块名；项目=`proj:<id>`；知识库=`kb:<id>`。 */
export interface Tab {
  key: string
  label: string
  icon: string
  path: string
  /** 首页固定不可关闭；其余可关。 */
  closable: boolean
  /** 成员手动置顶；批量关闭时保留，并随页签列表在当前浏览器持久化。 */
  pinned?: boolean
  /** 当前浏览器中由成员显式重命名；后续路由元数据刷新不得覆盖。 */
  customLabel?: boolean
  /**
   * label 只是「项目 p3」这样的 id 占位，真名要等详情视图加载实体后回填。
   * 打开页签时，占位标签不得覆盖已经解析出来的真名。
   */
  labelPlaceholder?: boolean
}
