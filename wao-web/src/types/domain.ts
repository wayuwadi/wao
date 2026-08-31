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
  /**
   * 事件描述。带 `by` 时不含主语（如「创建了任务」），由 `utils/activity` 按看的人补上
   * 「你」或对方名字；历史数据是写死的「你创建了任务」，此时 `by` 为空，原样展示。
   */
  text: string
  /** 发生时刻（ISO 8601）。历史数据可能是「刚刚」这类现成文案。 */
  time: string
  /** 操作人成员 id；系统事件与历史数据没有。 */
  by?: string | null
}

export interface FileRef {
  /** 真实文件库中的稳定 id；升级前的历史展示附件可能为空。 */
  id?: string | null
  name: string
  size: string
  icon: string
}

/** 上传到 WAO 并公开托管的单文件 HTML 页面。 */
export interface HostedPage {
  id: string
  slug: string
  title: string
  fileName: string
  /** 滚动发布兼容期可能缺失；页面会用稳定的报告 id 做只读兜底识别。 */
  sourceType?: 'MANUAL_UPLOAD' | 'AI_RUN_REPORT'
  hasIcon: boolean
  updatedAt: string
  url: string
}

export type ProjectStatus = '进行中' | '有风险' | '规划中' | '已完成' | '已搁置'

/**
 * 项目内的任务分组（分组视图）。
 *
 * <p>一个项目常常同时压着好几个 domain 的事，看板按状态切分帮不上忙 —— 分组按人自己的
 * 说法切（「证件」「装修」「上线前」），组内只关心做没做完。
 */
export interface TaskGroup {
  id: string
  name: string
}

/** 一个项目最多能建的任务分组数，与后端 ProjectService.MAX_TASK_GROUPS 一致。 */
export const MAX_TASK_GROUPS = 6

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
  /** 这个项目的动静要不要发飞书群；老数据没有这个字段时按开处理。 */
  notifyEnabled?: boolean
  /** 自定义任务分组（分组视图），最多 {@link MAX_TASK_GROUPS} 个；没建过时为空。 */
  taskGroups?: TaskGroup[]
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

export type PdcaOutcome = '达成' | '部分达成' | '未达成'

/** 一轮可回看的 PDCA：复盘完成后保留，下一轮不会覆盖上一轮经验。 */
export interface PdcaCycle {
  id: string
  /** P：希望达成的结果与判断标准。 */
  plan: string
  /** C：实际结果、偏差和原因。 */
  check: string
  /** A：下一轮要保留、修正或尝试的行动。 */
  act: string
  outcome?: PdcaOutcome | null
  startedAt: string
  reviewedAt?: string | null
}

/**
 * 任务状态。「收集中」表示正在向同伴征集想法（进入这个状态会自动分享到飞书群），
 * 「AI 进行中」标记交给 AI 跑的任务，「待验证」是做完但还没验收的任务。
 *
 * <p>老的「阻塞」已下线（V16 迁移到「进行中」）：等待他人由 {@link Task#waiting} 表达，
 * 不必再占一个状态位。
 */
export type TaskStatus = '待办' | '收集中' | '进行中' | 'AI 进行中' | '待验证' | '已完成'
export type Priority = 'P0' | 'P1' | 'P2' | 'P3'

export type IdeaStage =
  | 'INBOX'
  | 'RESEARCHING'
  | 'THINKING'
  | 'ALIGNING'
  | 'MVP_BUILDING'
  | 'VALIDATING'
  | 'ACHIEVED'
  | 'ABANDONED'
export type IdeaPriority = 'P0' | 'P1' | 'P2'
export type IdeaArtifactKind =
  | 'RESEARCH_REPORT'
  | 'SOLUTION_MVP'
  | 'MEETING_NOTE'
  | 'VALIDATION_RECORD'
  | 'ABANDONMENT_REPORT'
  | 'LEGACY_ARCHIVE'
export type IdeaIterationStatus =
  'PLANNED' | 'RUNNING' | 'READY_FOR_REVIEW' | 'ACCEPTED' | 'REJECTED' | 'FAILED'
export type IdeaResearchRunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface IdeaDetail {
  targetUsers?: string | null
  coreValue?: string | null
  nonGoals?: string | null
  feasibility?: string | null
  mvpHypothesis?: string | null
  acceptanceCriteria?: string | null
  meetingDate?: string | null
  meetingDuration?: number | null
  meetingParticipants?: string[] | null
  meetingConclusion?: string | null
  meetingNotes?: string | null
  validationConclusion?: string | null
  validationEvidence?: string | null
  abandonmentReason?: string | null
  researchAcceptedAt?: string | null
  researchAcceptedBy?: string | null
  activity?: Array<{ action: string; by: string; at: string }>
}

export interface IdeaArtifact {
  id: string
  kind: IdeaArtifactKind
  docId: string
  title: string
  createdBy: string
  createdAt: string
}
export interface IdeaTaskLink {
  id: string
  taskId: string
  title: string
  status: TaskStatus
  requiredMvp: boolean
  order: number
  createdBy: string
  createdAt: string
}
export interface IdeaIteration {
  id: string
  taskId: string
  aiRunId?: string | null
  objective: string
  acceptanceCriteria?: string | null
  status: IdeaIterationStatus
  feedback?: string | null
  resultSummary?: string | null
  reportUrl?: string | null
  createdBy: string
  createdAt: string
  finishedAt?: string | null
}
export interface IdeaResearchRun {
  id: string
  status: IdeaResearchRunStatus
  error?: string | null
  docId?: string | null
  createdBy: string
  createdAt: string
  finishedAt?: string | null
}
export interface Idea {
  id: string
  title: string
  rawIdea?: string | null
  problem?: string | null
  targetOutcome?: string | null
  priority: IdeaPriority
  stage: IdeaStage
  createdBy: string
  ownerId: string
  projectId?: string | null
  detail: IdeaDetail
  artifacts: IdeaArtifact[]
  tasks: IdeaTaskLink[]
  iterations: IdeaIteration[]
  researchRuns: IdeaResearchRun[]
  createdAt: string
  updatedAt: string
}

export interface Task {
  id: string
  title: string
  project: string
  status: TaskStatus
  pr: Priority
  owner: string
  /** 创建人由服务端根据当前会话写入；历史任务没有可靠来源时为空。 */
  createdBy?: string | null
  /** 任务真实创建时间；极早期没有可靠来源的历史任务可能为空。 */
  createdAt?: string | null
  /** 完成时间由服务端在状态进入「已完成」时写入，重新打开时清空。 */
  completedAt?: string | null
  /** 所属任务分组 id（项目分组视图）；未归组时为空，视为落在第一个分组。 */
  groupId?: string | null
  parts: string[]
  start?: string
  due: string
  /** 日历时间段开始（本地 ISO 日期时间，yyyy-MM-ddTHH:mm）；为空表示仅按截止日展示。 */
  startAt?: string | null
  /** 日历时间段结束（本地 ISO 日期时间）；仅在 startAt 有值时使用。 */
  endAt?: string | null
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
  /** 按时间顺序保存的 PDCA 轮次；空数组表示普通任务。 */
  pdcaCycles?: PdcaCycle[]
}

/** 文档块：以下字段互斥，命中哪个就渲染哪种块。 */
export interface DocBlock {
  /** 提示块（浅蓝） */
  call?: string
  /** 标题 */
  h?: string
  /** 标题级别；旧数据缺省为 2。 */
  hLevel?: number
  /** 段落 */
  p?: string
  /** 无序列表 */
  ul?: string[]
  /** 引用：[正文, 出处] */
  q?: [string, string]
}

/** 当前成员可复用的文档正文模板。 */
export interface DocTemplate {
  id: string
  name: string
  description: string
  blocks: DocBlock[]
  sourceDocId: string | null
  createdAt: string
  updatedAt: string
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
  /** 自动化运行记录 id。 */
  id?: string
  time: string
  executor: string
  result: string
  /** 真实开始时间，供跨任务运行记录按时间排序；旧数据可为空。 */
  runAt?: string | null
  /** 服务端记录的真实执行耗时；旧数据可为空。 */
  durationMs?: number | null
  /** 本次自动化创建的真实 AI Run；跳过和启动失败时为空。 */
  aiRunId?: string | null
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
  /** 服务端真实执行器名（如 feishu-chat-sync）；为空或未知时运行会明确失败。 */
  handler?: string | null
}

export interface FileItem {
  id?: string
  name: string
  /** 服务端保存的 MIME；旧接口数据可能缺失，调用方需按扩展名兜底。 */
  contentType?: string
  size: string
  icon: string
  time: string
  /** 真实创建时间；time 只用于面向人的本地化展示。 */
  createdAt?: string | null
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
  pdcaCycles: PdcaCycle[]
}

/** 公开分享的文档 / 小记视图（只有标题、正文与更新时间）。 */
export interface SharedDoc {
  id: string
  title: string
  note: boolean
  kbId: string
  updated: string | null
  md: string
}

/** 可以托管任务的本地 AI 工具。 */
export type AiEngine = 'codex' | 'claude' | 'qoder'

export interface AiRunOption {
  id: string
  label: string
  description: string
}

/** 后台下发的单次 AI Run 模型参数，前端不自行维护模型标识。 */
export interface AiRunOptions {
  codexModels: AiRunOption[]
  reasoningEfforts: AiRunOption[]
  defaultCodexModel: string
  defaultReasoningEffort: string
  serviceTier: 'default'
}

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
  /** AI CLI 异常或非零退出后，在同一次运行内自动重试的次数。 */
  failureRetryCount: number
}

/** 一台已和 WAO 配对的执行电脑，可能同时装了 Codex 和 Claude Code。 */
export interface AiRunner {
  id: string
  name: string
  platform: string
  engines: AiEngine[]
  maxConcurrency: number
  /** 管理员在后台单独配置的线程数；存在时优先于 Runner 本地自报值。 */
  configuredMaxConcurrency?: number | null
  activeRuns: number
  online: boolean
  lastSeenAt: string | null
  wakeMac: string | null
  wakeHost: string | null
  wakePort: number
  wakeConfigured: boolean
  lastWakeRequestedAt: string | null
  /** Runner 脚本是否与服务端打包的最新版一致；旧版 Runner 未上报哈希时为 null。 */
  scriptUpToDate?: boolean | null
  /** 后台已下发脚本更新、该电脑还没完成自更新重启。 */
  updateRequested?: boolean
  memoryCollectionEnabled?: boolean
  memoryCollectionDue?: boolean
  lastMemoryCollectionAttemptAt?: string | null
  lastMemoryCollectedAt?: string | null
  lastMemoryCollectionError?: string | null
  cpuCores?: number | null
  cpuUsagePercent?: number | null
  memoryTotalBytes?: number | null
  memoryAvailableBytes?: number | null
  diskTotalBytes?: number | null
  diskFreeBytes?: number | null
  diskRoot?: string | null
  systemMetricsCollectedAt?: string | null
}

/** 执行电脑最近一次心跳上报的已脱敏日志快照。 */
export interface AiRunnerLog {
  runnerId: string
  runnerName: string
  content: string
  updatedAt: string | null
}

/** AI 控制台「工作流」视图里的一个项目。 */
export interface AiWorkflow {
  projectId: string
  projectName: string
  config: AiProjectConfig
  /** 是否已保存过工作流配置；false 时只是项目的“未接入”入口。 */
  configured?: boolean
  /** 项目已删除但配置仍存留，需要用户清理的历史孤儿工作流。 */
  projectDeleted?: boolean
  preferredRunnerName: string | null
  activeRuns: number
  succeededRuns: number
  failedRuns: number
  lastRunAt: string | null
}

export type AiRunStatus = 'queued' | 'claimed' | 'running' | 'succeeded' | 'failed' | 'cancelled'
export type AiRunDeliveryStage = 'coding' | 'waiting_deployment' | 'deployment_succeeded' | 'deployment_failed' | 'code_failed'

export interface AiRunProgress {
  id: string
  kind: 'phase' | 'message' | 'plan' | 'goal' | 'result'
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

/** WAO 默认或项目绑定，并在 AI Run 入队时固化的审核 Skill。 */
export interface AiSkillCapability {
  skillId: string
  versionId: string
  displayName: string
  summary: string
  version: string
  sha256: string
  compatiblePlatforms: string[]
  activationScope?: 'WAO_APPLICATION' | 'PROJECT' | null
}

export interface AiSkillAudit {
  skillId: string
  versionId: string
  displayName: string
  version: string
  status: 'loaded' | 'failed' | 'incompatible' | 'not_reported'
  message: string | null
}

export type ProjectMemoryCategory =
  'decision' | 'convention' | 'pitfall' | 'verification' | 'operation' | 'context'

/** 项目工程记忆；与当前成员的个人记忆是完全独立的数据域。 */
export interface ProjectMemory {
  id: string
  projectId: string
  category: ProjectMemoryCategory
  title: string
  content: string
  sourceTaskId: string | null
  sourceRunId: string | null
  evidenceLinks: string[]
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export type ProjectMemoryReference = Pick<
  ProjectMemory,
  | 'id'
  | 'projectId'
  | 'category'
  | 'title'
  | 'content'
  | 'sourceTaskId'
  | 'sourceRunId'
  | 'evidenceLinks'
>

export interface AiRun {
  id: string
  taskId: string
  taskTitle: string
  /** 合并运行覆盖的全部任务；旧服务端和单任务运行可缺省。 */
  taskIds?: string[]
  /** 目标里的图片预览地址，最多四张；不包含 Base64 正文。 */
  previewImages?: string[]
  /** 本次只跑这一条子任务；整个任务的运行为 null。 */
  subId: string | null
  /** 子任务标题，用于在执行记录里标明跑的是哪一条。 */
  subTitle: string | null
  projectId: string
  projectName: string
  engine: AiEngine
  requestedModel?: string | null
  reasoningEffort?: string | null
  planMode?: boolean
  goalMode?: boolean
  goalObjective?: string | null
  goalStatus?: 'pending' | 'active' | 'paused' | 'blocked' | 'usageLimited' | 'budgetLimited' | 'complete' | null
  goalTokensUsed?: number | null
  goalTimeUsedSeconds?: number | null
  serviceTier?: string | null
  status: AiRunStatus
  /** 代码提交与生产发布分开展示；滚动升级期间旧 API 可暂时缺失。 */
  deliveryStage?: AiRunDeliveryStage
  runnerId: string | null
  runnerName: string | null
  requestedRunnerId: string | null
  /** 旧运行记录尚未回填触发人，因此兼容缺失字段。 */
  requestedBy?: string | null
  branchName: string | null
  /** 本次是在修哪一次失败的运行；不是重跑时为 null。 */
  retryOfRunId: string | null
  resultSummary: string | null
  /** AI 为本次运行额外产出的持久化单文件 HTML 报告。 */
  reportUrl: string | null
  latestProgress: string | null
  progressUpdatedAt: string | null
  progress: AiRunProgress[]
  /** 本次用掉的模型、token 与折算额度。 */
  usage: AiRunUsage
  /** 新输入已保存，正在等待当前执行电脑接收。 */
  continuationPending?: boolean
  sessionId: string | null
  exitCode: number | null
  /** 本次入队时固定的 Skill 版本，不随项目之后的配置变化。 */
  skills?: AiSkillCapability[]
  /** Runner 实际装配结果；运行未结束时可能为空。 */
  skillAudits?: AiSkillAudit[]
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  /** 旧服务端和历史运行没有这两个字段。 */
  memoryReads?: ProjectMemoryReference[]
  memoryWrites?: ProjectMemoryReference[]
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
  /** 该成员的默认分组：不可删除、新建条目的默认落点。改名后依然成立（见 ADR-036）。 */
  isDefault?: boolean
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
