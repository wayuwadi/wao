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

/** 任务 / 项目内嵌图片（url 为 data: 或远程链接）。 */
export interface ImageRef {
  name: string
  url: string
}

export interface Project {
  id: string
  name: string
  /** 项目图标（remixicon 类名，如 ri-stack-line）。 */
  icon?: string
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
  /**
   * 子任务稳定 id（`s` + 时间戳）。历史数据没有这个字段，读取时按数组下标兜底，
   * 新建的子任务一律带 id —— 分享链接与详情面板都靠它定位，不能用会漂移的下标。
   */
  id?: string
  /** 子任务描述（Markdown）。 */
  desc?: string
  /** 子任务内嵌图片。 */
  images?: ImageRef[]
}

export interface Comment {
  /** 评论稳定 id（`c` + 时间戳）。历史评论没有，改 / 删时按数组下标兜底。 */
  id?: string
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

/**
 * 任务状态。「AI 进行中」标记交给 AI 跑的任务，「待验证」是做完但还没验收的任务。
 *
 * <p>老的「阻塞」已下线（V16 迁移到「进行中」）：等待他人由 {@link Task#waiting} 表达，
 * 不必再占一个状态位。
 */
export type TaskStatus = '待办' | '进行中' | 'AI 进行中' | '待验证' | '已完成'
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
  /** 内嵌图片（粘贴 / 上传的截图或参考图）。 */
  images?: ImageRef[]
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
  /** 创建时间。后端写入的是 ISO 时间戳，升级前的老文档是「7月12日」这类现成文案。 */
  created: string
  /** 更新时间，口径同 created。 */
  updated: string
  auto?: boolean
  links: DocLink[]
  blocks: DocBlock[]
  backlinks: Backlink[]
  /** 归属知识库 / 文件夹（后端返回，本地乐观创建时也会补上）。 */
  kbId?: string
  folderId?: string
  /** 正文版本号：协同端据此判断服务端有没有更新的内容。 */
  rev?: number
}

/** 正打开某篇文档的协作者（协同在场，见 ADR-017）。 */
export interface DocPeer {
  id: string
  name: string
  initial: string
  color: string
  /** 光标所在的顶层块序号；-1 表示只在查看。 */
  line: number
  editing: boolean
}

/** 回收站条目：删除的知识库 / 文件夹 / 文档，保留 7 天。 */
export interface TrashEntry {
  type: 'kb' | 'folder' | 'doc'
  id: string
  name: string
  parent: string
  deletedAt: string
  deletedBy: string | null
  expiresAt: string
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
  /** 是否为小记知识库：全局「新建小记」的默认落点，不可删除。 */
  notes?: boolean
}

export interface KbGroup {
  id: string
  name: string
}

export interface JobRun {
  time: string
  executor: string
  result: string
}

export interface AutomationJob {
  id: string
  name: string
  icon: string
  schedule: string
  status: '正常' | '待修复' | '已暂停'
  last: string
  desc: string
  source: string
  target: string
  steps: string[]
  runs: JobRun[]
  /** 服务端真实执行器名（如 feishu-chat-sync）；为空表示这条任务只是描述性的模拟运行。 */
  handler?: string | null
}

export interface FileItem {
  id?: string
  name: string
  size: string
  icon: string
  time: string
  by: string
  link: string
}

export interface ImageItem {
  /** 上传到图床的图片才有 id；内置演示数据没有。 */
  id?: string
  name: string
  size: string
  time: string
  from: string
  url: string
  c1: string
  c2: string
}

/** 公开分享的任务 / 子任务视图（不含负责人、评论、活动等内部信息）。 */
export interface SharedTask {
  kind: 'task' | 'sub'
  id: string
  subId: string | null
  title: string
  status: string
  pr: string | null
  due: string | null
  projectId: string | null
  project: string
  desc: string
  images: ImageRef[]
  subs: TaskStep[]
}

/** 可以托管任务的本地 AI 工具。 */
export type AiEngine = 'codex' | 'claude'

/** 项目级 AI Run 配置。 */
export interface AiProjectConfig {
  projectId: string
  enabled: boolean
  repoUrl: string
  defaultBranch: string
  workspaceDir: string
  defaultEngine: AiEngine
  preferredRunnerId: string | null
  maxParallelRuns: number
}

/** 一台已和 WAO 配对的执行电脑，可能同时装了 Codex 和 Claude Code。 */
export interface AiRunner {
  id: string
  name: string
  platform: string
  engines: AiEngine[]
  maxConcurrency: number
  activeRuns: number
  online: boolean
  lastSeenAt: string | null
}

/** AI 控制台「工作流」视图里的一个项目。 */
export interface AiWorkflow {
  projectId: string
  projectName: string
  config: AiProjectConfig
  preferredRunnerName: string | null
  activeRuns: number
  succeededRuns: number
  failedRuns: number
  lastRunAt: string | null
}

export type AiRunStatus = 'queued' | 'claimed' | 'running' | 'succeeded' | 'failed' | 'cancelled'

export interface AiRunProgress {
  id: string
  kind: 'phase' | 'message' | 'plan' | 'result'
  content: string
  createdAt: string
}

/** 一次从 WAO 派发到本地 AI CLI 的任务运行。 */
/** 一次 AI 运行的用量账单；`recorded` 为 false 表示这次没记上账，不是「用了 0」。 */
export interface AiRunUsage {
  model: string | null
  models: string | null
  inputTokens: number
  outputTokens: number
  cachedInputTokens: number
  totalTokens: number
  costUsd: number | null
  recorded: boolean
}

export interface AiRun {
  id: string
  taskId: string
  taskTitle: string
  projectId: string
  projectName: string
  engine: AiEngine
  status: AiRunStatus
  runnerId: string | null
  runnerName: string | null
  requestedRunnerId: string | null
  branchName: string | null
  /** 本次是在修哪一次失败的运行；不是重跑时为 null。 */
  retryOfRunId: string | null
  resultSummary: string | null
  latestProgress: string | null
  progressUpdatedAt: string | null
  progress: AiRunProgress[]
  /** 本次用掉的模型、token 与折算额度。 */
  usage: AiRunUsage
  sessionId: string | null
  exitCode: number | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface VaultItem {
  id: string
  name: string
  icon: string
  user: string
  site: string
  note: string
  updated: string
  /** 是否已设置密码；密码明文从不随列表返回，需调用 reveal 接口按需查看。 */
  hasSecret: boolean
}

export interface VaultGroup {
  id: string
  group: string
  items: VaultItem[]
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
