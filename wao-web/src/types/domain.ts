/** WAO 业务域类型 —— 与原型 Component 的 mock 数据结构一致，后端 DTO 将对齐这些字段。 */

export interface Area {
  id: string
  name: string
  color: string
}

export interface Milestone {
  name: string
  date: string
  /** 0=未来 1=进行中 2=完成 */
  state: 0 | 1 | 2
}

export interface Blocker {
  text: string
  who: string
  days: string
}

export interface Activity {
  icon: string
  color: string
  text: string
  time: string
}

export interface FileRef {
  name: string
  size: string
  icon: string
}

export type ProjectStatus = '进行中' | '有风险' | '规划中' | '已完成'

export interface Project {
  id: string
  name: string
  area: string
  status: ProjectStatus
  health: string
  progress: number
  owner: string
  members: string[]
  start: string
  due: string
  visibility: string
  updated: string
  goal: string
  milestones: Milestone[]
  msDates: string[]
  trend: number[]
  blockers: Blocker[]
  docs: string[]
  files: FileRef[]
  activity: Activity[]
}

export interface TaskStep {
  text: string
  done: boolean
}

export interface Comment {
  who: string
  time: string
  text: string
}

export interface TaskLink {
  id: string
  rel: string
}

export interface CustomProp {
  name: string
  value: string
}

export type TaskStatus = '待办' | '进行中' | '阻塞' | '已完成'
export type Priority = 'P0' | 'P1' | 'P2' | 'P3'

export interface Task {
  id: string
  title: string
  project: string
  status: TaskStatus
  pr: Priority
  owner: string
  parts: string[]
  start?: string
  due: string
  waiting?: string
  desc: string
  steps: TaskStep[]
  subs: TaskStep[]
  files: FileRef[]
  docs: string[]
  linkTasks: TaskLink[]
  comments: Comment[]
  custom: CustomProp[]
  activity: Activity[]
}

/** 文档块：以下字段互斥，命中哪个就渲染哪种块。 */
export interface DocBlock {
  /** 提示块（浅蓝） */
  call?: string
  /** H2 标题 */
  h?: string
  /** 段落 */
  p?: string
  /** 无序列表 */
  ul?: string[]
  /** 引用：[正文, 出处] */
  q?: [string, string]
}

export interface DocLink {
  type: 'project' | 'task' | 'doc'
  id: string
}

export interface Backlink {
  type: 'project' | 'task' | 'doc'
  id: string
  snippet: string
}

export interface Doc {
  id: string
  title: string
  author: string
  created: string
  updated: string
  auto?: boolean
  links: DocLink[]
  blocks: DocBlock[]
  backlinks: Backlink[]
}

export interface Folder {
  id: string
  name: string
  docs: string[]
}

export interface KnowledgeBase {
  id: string
  name: string
  icon: string
  color: string
  project: string | null
  auto?: boolean
  desc: string
  folders: Folder[]
  /** 个人库所属自建分组 id（默认 g1）。 */
  group?: string
  /** 权限：'仅自己' | '成员可编辑'。 */
  perm?: string
}

export interface KbGroup {
  id: string
  name: string
}

export type JobRun = [time: string, result: string]

export interface AutomationJob {
  name: string
  icon: string
  schedule: string
  status: '正常' | '待修复'
  last: string
  desc: string
  source: string
  target: string
  steps: string[]
  runs: JobRun[]
}

export interface FileItem {
  name: string
  size: string
  icon: string
  time: string
  by: string
  link: string
}

export interface ImageItem {
  name: string
  size: string
  time: string
  from: string
  url: string
  c1: string
  c2: string
}

export interface VaultItem {
  name: string
  icon: string
  user: string
  site: string
  note: string
  updated: string
}

export interface VaultGroup {
  group: string
  items: VaultItem[]
}

export interface CalendarEvent {
  date: string
  title: string
  color: string
}

export interface ScheduleItem {
  time: string
  title: string
  sub: string
  color: string
}

export type Evidence = [chip: string, text: string]

export interface Suggestion {
  id: string
  task: string
  title: string
  why: string
  steps: string[]
  ev: Evidence[]
}
