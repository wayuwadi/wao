import { http, type ApiResponse } from './http'
import type {
  Area,
  AutomationJob,
  Comment,
  Doc,
  DocBlock,
  DocLink,
  DocPeer,
  RichDocContent,
  DocTemplate,
  FileItem,
  Folder,
  ImageItem,
  ImageRef,
  HostedPage,
  KbGroup,
  KnowledgeBase,
  Member,
  Org,
  Project,
  ScheduleItem,
  SharedDoc,
  SharedTask,
  Suggestion,
  Task,
  PdcaCycle,
  TaskGroup,
  TrashEntry,
  VaultGroup,
  VaultItem,
} from '@/types'

/** 目标胶囊（首页右上）。 */
export interface GoalDto {
  id: string
  name: string
  color: string
  pct: number
}

/**
 * 后端读写接口封装。组件不直接调用，统一经由 data store。
 * 契约见 docs/api/*.yaml；响应为 ApiResponse<T>，拦截器已解包 code!=0 的错误。
 */

async function get<T>(url: string): Promise<T> {
  const res = await http.get<ApiResponse<T>>(url)
  return res.data.data
}
async function post<T>(url: string, body?: unknown): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, body)
  return res.data.data
}
async function patch<T>(url: string, body?: unknown): Promise<T> {
  const res = await http.patch<ApiResponse<T>>(url, body)
  return res.data.data
}
async function put<T>(url: string, body?: unknown): Promise<T> {
  const res = await http.put<ApiResponse<T>>(url, body)
  return res.data.data
}
async function del(url: string): Promise<void> {
  await http.delete<ApiResponse<void>>(url)
}

export const fetchMembers = () => get<Member[]>('/api/members')
export const fetchOrg = () => get<Org>('/api/org')
export const fetchAreas = () => get<Area[]>('/api/areas')
export const fetchProjects = () => get<Project[]>('/api/projects')
export const fetchTasks = () => get<Task[]>('/api/tasks')

// 内容 / 参考域
export const fetchKbs = () => get<KnowledgeBase[]>('/api/kbs')
export const fetchKbGroups = () => get<KbGroup[]>('/api/kb-groups')
export const fetchDocs = () => get<Doc[]>('/api/docs')
export const fetchArchivedDocs = (kbId: string) =>
  get<Doc[]>(`/api/docs/archived?kbId=${encodeURIComponent(kbId)}`)
export const fetchDoc = (id: string) => get<Doc>(`/api/docs/${id}`)
export const fetchJobs = () => get<AutomationJob[]>('/api/jobs')
export const fetchAutomationJob = (id: string) => get<AutomationJob>(`/api/jobs/${id}`)
export const fetchFiles = (projectId?: string) =>
  get<FileItem[]>(`/api/files${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`)
export async function apiUploadFile(file: File, projectId?: string): Promise<FileItem> {
  const form = new FormData()
  form.append('file', file)
  if (projectId) form.append('projectId', projectId)
  const res = await http.post<ApiResponse<FileItem>>('/api/files', form, { timeout: 120_000 })
  return res.data.data
}
export const apiRenameFile = (id: string, displayName: string) =>
  patch<FileItem>(`/api/files/${id}`, { displayName })
export const apiDeleteFile = (id: string) => del(`/api/files/${id}`)
export const fetchHostedPages = (projectId?: string) =>
  get<HostedPage[]>(
    `/api/hosted-pages${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`,
  )
export async function apiSaveHostedPage(payload: {
  id?: string
  html?: File
  icon?: File
  slug: string
  title: string
  projectId?: string
}): Promise<HostedPage> {
  const form = new FormData()
  if (payload.html) form.append('html', payload.html)
  if (payload.icon) form.append('icon', payload.icon)
  form.append('slug', payload.slug)
  form.append('title', payload.title)
  if (payload.projectId) form.append('projectId', payload.projectId)
  const res = payload.id
    ? await http.put<ApiResponse<HostedPage>>(`/api/hosted-pages/${payload.id}`, form, {
        timeout: 120_000,
      })
    : await http.post<ApiResponse<HostedPage>>('/api/hosted-pages', form, { timeout: 120_000 })
  return res.data.data
}
export const apiDeleteHostedPage = (id: string) => del(`/api/hosted-pages/${id}`)
/** 公开：下载确认页要展示的文件信息。 */
export const fetchFileMeta = (id: string) => get<FileItem>(`/api/files/${id}/meta`)
export const fetchFilePreview = (id: string) =>
  get<{ id: string; name: string; contentType: string; content: string }>(
    `/api/files/${id}/preview`,
  )
/** 公开：取一道人机校验题。 */
export const apiDownloadChallenge = (id: string) =>
  post<{ challengeId: string; question: string; captchaEnabled: boolean }>(
    `/api/files/${id}/challenge`,
  )
/** 公开：用答案换一张一次性下载票据。 */
export const apiDownloadTicket = (id: string, challengeId: string, answer: string) =>
  post<{ ticket: string }>(`/api/files/${id}/ticket`, { challengeId, answer })
export const fetchImages = (projectId?: string) =>
  get<ImageItem[]>(`/api/images${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`)
export const fetchVault = () => get<VaultGroup[]>('/api/vault')
export const fetchSchedule = () => get<ScheduleItem[]>('/api/schedule')
export const fetchSuggestions = () => get<Suggestion[]>('/api/suggestions')
export const fetchGoals = () => get<GoalDto[]>('/api/goals')

export interface CreateTaskPayload {
  title: string
  project: string
  pr: string
  owner: string
  due: string
  startAt?: string | null
  endAt?: string | null
  desc?: string
  images?: ImageRef[]
  /** 落进哪个任务分组（分组视图里的「+」带上它）。 */
  groupId?: string | null
  /** 历史客户端兼容字段；当前前端不再提供 PDCA 新建入口。 */
  pdcaCycles?: PdcaCycle[]
}
export async function apiCreateTask(payload: CreateTaskPayload): Promise<Task> {
  const res = await http.post<ApiResponse<Task>>('/api/tasks', payload)
  return res.data.data
}

export interface PatchTaskPayload {
  title?: string
  status?: string
  pr?: string
  owner?: string
  desc?: string
  startAt?: string | null
  endAt?: string | null
}
export async function apiPatchTask(id: string, payload: PatchTaskPayload): Promise<Task> {
  const res = await http.patch<ApiResponse<Task>>(`/api/tasks/${id}`, payload)
  return res.data.data
}

/** 全量替换任务（本地变更后的写透）。 */
export async function apiPutTask(id: string, task: Task): Promise<Task> {
  const res = await http.put<ApiResponse<Task>>(`/api/tasks/${id}`, task)
  return res.data.data
}
export const apiAddTaskComment = (id: string, text: string) =>
  post<Comment>(`/api/tasks/${id}/comments`, { text })

/** 当前成员明确把任务发到通知群；返回是否真实发送成功。 */
export async function notifyTaskManually(id: string): Promise<boolean> {
  const res = await http.post<ApiResponse<boolean>>(`/api/tasks/${id}/notifications`)
  return res.data.data
}

// ---------------- 图床 ----------------

/**
 * 上传一张图片到图床，返回可直接用于 img 的条目。
 *
 * @param file   图片文件（后端按图床设置限制且最高 10MB，仅接受常见图片类型）
 * @param source 来源描述，如「任务 · 整理证件盒」，会显示在图床列表里
 */
export async function apiUploadImage(
  file: File,
  source?: string,
  projectId?: string,
): Promise<ImageItem> {
  const form = new FormData()
  form.append('file', file)
  if (source) form.append('source', source)
  if (projectId) form.append('projectId', projectId)
  const res = await http.post<ApiResponse<ImageItem>>('/api/images', form, {
    // 上传比普通接口慢，用更宽的超时，避免大图在弱网下被 10s 直接掐断
    timeout: 60_000,
  })
  return res.data.data
}
export const apiDeleteImage = (id: string) => del(`/api/images/${id}`)

// ---------------- 任务 / 文档分享（公开只读） ----------------

export const apiShareTask = (id: string, openOnly = false) =>
  get<SharedTask>(`/api/share/tasks/${id}${openOnly ? '?openOnly=true' : ''}`)
export const apiShareSub = (id: string, subId: string) =>
  get<SharedTask>(`/api/share/tasks/${id}/subs/${encodeURIComponent(subId)}`)
export const apiShareDoc = (id: string) =>
  get<SharedDoc>(`/api/share/docs/${encodeURIComponent(id)}`)

export interface CreateProjectPayload {
  name: string
  area: string
  goal: string
  visAll: boolean
  /** false 表示只收进想法箱，首次开始推进时再通知团队。 */
  announce?: boolean
}

/** 首次开始推进项目；服务端保证状态和团队通知幂等。 */
export const apiStartProject = (id: string) => post<Project>(`/api/projects/${id}/start`)

/** 幂等创建或返回项目说明文档。 */
export const apiEnsureProjectBrief = (id: string) => post<Doc>(`/api/projects/${id}/brief-doc`)
export async function apiCreateProject(payload: CreateProjectPayload): Promise<Project> {
  const res = await http.post<ApiResponse<Project>>('/api/projects', payload)
  return res.data.data
}

export interface PatchProjectPayload {
  name?: string
  area?: string
  status?: Project['status']
  goal?: string
  icon?: string
  notifyEnabled?: boolean
  /** 任务分组的完整列表（整表替换）；不传表示不改。 */
  taskGroups?: TaskGroup[]
}
/** 局部更新项目（名称 / 领域 / 状态 / 目标 / 图标 / 通知开关 / 任务分组）。 */
export async function apiPatchProject(id: string, payload: PatchProjectPayload): Promise<Project> {
  const res = await http.patch<ApiResponse<Project>>(`/api/projects/${id}`, payload)
  return res.data.data
}

/** 删除项目。 */
export async function apiDeleteProject(id: string): Promise<void> {
  await http.delete<ApiResponse<void>>(`/api/projects/${id}`)
}

// ---------------- 知识库分组 ----------------

export const apiCreateKbGroup = (name: string) => post<KbGroup>('/api/kb-groups', { name })
export const apiRenameKbGroup = (id: string, name: string) =>
  patch<KbGroup>(`/api/kb-groups/${id}`, { name })
export const apiDeleteKbGroup = (id: string) => del(`/api/kb-groups/${id}`)

// ---------------- 知识库 / 文件夹 ----------------

export interface CreateKbPayload {
  name: string
  desc?: string
  icon?: string
  color?: string
  project?: string | null
  group?: string
  perm?: string
}
export const apiCreateKb = (payload: CreateKbPayload) => post<KnowledgeBase>('/api/kbs', payload)

export interface PatchKbPayload {
  name?: string
  desc?: string
  icon?: string
  color?: string
  group?: string
  perm?: string
}
export const apiPatchKb = (id: string, payload: PatchKbPayload) =>
  patch<KnowledgeBase>(`/api/kbs/${id}`, payload)
export const apiDeleteKb = (id: string) => del(`/api/kbs/${id}`)

export const apiCreateFolder = (kbId: string, name: string) =>
  post<Folder>(`/api/kbs/${kbId}/folders`, { name })
export const apiRenameFolder = (kbId: string, folderId: string, name: string) =>
  patch<Folder>(`/api/kbs/${kbId}/folders/${folderId}`, { name })
export const apiDeleteFolder = (kbId: string, folderId: string) =>
  del(`/api/kbs/${kbId}/folders/${folderId}`)

// ---------------- 文档 ----------------

export interface CreateDocPayload {
  kbId: string
  folderId: string
  title?: string
  templateId?: string
  blocks?: DocBlock[]
  content?: RichDocContent
}
/** 新建文档：kbId + folderId 必填，创建即完成与知识库的关联。作者由服务端按会话推导。 */
export const apiCreateDoc = (payload: CreateDocPayload) => post<Doc>('/api/docs', payload)

/** 幂等新建小记：草稿重试沿用同一个 id，服务端不会插入重复小记。 */
export const apiCreateNote = (id: string, payload: { title?: string; blocks: DocBlock[]; content?: RichDocContent; tags?: string[] }) =>
  put<Doc>(`/api/docs/notes/${id}`, payload)

export const fetchNoteTags = () => get<string[]>('/api/docs/note-tags')
export const apiCreateNoteTag = (name: string) => post<string>('/api/docs/note-tags', { name })

export interface PatchDocPayload {
  tags?: string[]
  title?: string
  blocks?: DocBlock[]
  content?: RichDocContent
  links?: DocLink[]
  folderId?: string
  expectedRev?: number
}
export const apiPatchDoc = (id: string, payload: PatchDocPayload) =>
  patch<Doc>(`/api/docs/${id}`, payload)
/** 归档文档会退出日常文档树，但长期保留且可恢复。 */
export const apiArchiveDoc = (id: string) => post<Doc>(`/api/docs/${id}/archive`)
export async function apiUnarchiveDoc(id: string): Promise<Doc> {
  const res = await http.delete<ApiResponse<Doc>>(`/api/docs/${id}/archive`)
  return res.data.data
}
/** 删除文档 —— 后端移入回收站，7 天内可恢复。 */
export const apiDeleteDoc = (id: string) => del(`/api/docs/${id}`)

// ---------------- 文档模板与文档树排序 ----------------

export const fetchDocTemplates = () => get<DocTemplate[]>('/api/doc-templates')
export const apiCreateDocTemplate = (payload: {
  name: string
  description?: string
  sourceDocId: string
  blocks: DocBlock[]
  content?: RichDocContent
}) => post<DocTemplate>('/api/doc-templates', payload)
export const apiPatchDocTemplate = (
  id: string,
  payload: { name?: string; description?: string; blocks?: DocBlock[]; content?: RichDocContent; expectedUpdatedAt: string },
) => patch<DocTemplate>(`/api/doc-templates/${id}`, payload)
export const apiDeleteDocTemplate = (id: string) => del(`/api/doc-templates/${id}`)
export interface DocumentOrderPayload {
  folders: { folderId: string; docIds: string[] }[]
}
export const apiReorderDocs = (kbId: string, payload: DocumentOrderPayload) =>
  put<void>(`/api/kbs/${kbId}/document-order`, payload)

// ---------------- 文档协同在场（见 ADR-017） ----------------

export interface DocPresencePayload {
  /** 光标所在的顶层块序号；-1 表示只在查看。 */
  line: number
  editing: boolean
  /** 本地手上的正文版本号；服务端更新时会把最新文档带回来。 */
  rev: number
}
export interface DocPresenceResult {
  peers: DocPeer[]
  rev: number
  /** 仅当本地版本落后时返回；否则为 null。 */
  doc: Doc | null
}
/** 一次在场心跳：上报光标位置，取回同刻的协作者与（落后时的）最新正文。 */
export const apiDocPresence = (id: string, payload: DocPresencePayload) =>
  post<DocPresenceResult>(`/api/docs/${id}/presence`, payload)
/** 主动离开文档，让协作者那边的头像立刻消失。 */
export const apiLeaveDoc = (id: string) => del(`/api/docs/${id}/presence`)

// ---------------- 回收站 ----------------

export const fetchTrash = () => get<TrashEntry[]>('/api/trash')
export const apiRestoreTrash = (type: string, id: string) =>
  post<void>(`/api/trash/${type}/${id}/restore`)
export const apiPurgeTrash = (type: string, id: string) => del(`/api/trash/${type}/${id}`)

// ---------------- 自动化 ----------------

export interface CreateJobPayload {
  name: string
  icon?: string
  schedule?: string
  desc?: string
  source?: string
  target?: string
  steps?: string[]
  cronExpr?: string
  /** 真实飞书通知任务；由服务端使用已配置的自建应用发到所选群。 */
  handler?: string
}
export const apiCreateJob = (payload: CreateJobPayload) => post<AutomationJob>('/api/jobs', payload)

export type PatchJobPayload = Partial<CreateJobPayload>
export const apiPatchJob = (id: string, payload: PatchJobPayload) =>
  patch<AutomationJob>(`/api/jobs/${id}`, payload)
export const apiDeleteJob = (id: string) => del(`/api/jobs/${id}`)
export const apiDeleteTask = (id: string) => del(`/api/tasks/${id}`)
export const apiUpdateTaskComment = (taskId: string, commentId: string, text: string) =>
  patch<Comment>(`/api/tasks/${taskId}/comments/${encodeURIComponent(commentId)}`, { text })
export const apiDeleteTaskComment = (taskId: string, commentId: string) =>
  del(`/api/tasks/${taskId}/comments/${encodeURIComponent(commentId)}`)
export const apiRunJob = (id: string) => post<AutomationJob>(`/api/jobs/${id}/run`)
export const apiPauseJob = (id: string) => post<AutomationJob>(`/api/jobs/${id}/pause`)
export const apiResumeJob = (id: string) => post<AutomationJob>(`/api/jobs/${id}/resume`)
export const apiReauthJob = (id: string) => post<AutomationJob>(`/api/jobs/${id}/reauth`)

export interface HttpPerformanceInspectionConfig {
  enabled: boolean
  frequency: 'DAILY' | 'WEEKDAYS' | 'WEEKLY'
  time: string
  windowHours: number
  topN: number
  maxEndpointsPerRun: number
  notificationWebhookId: string | null
}

const HTTP_INSPECTION_CONFIG_URL = '/api/jobs/job-http-performance-ai-run/config'
export const fetchHttpPerformanceInspectionConfig = () =>
  get<HttpPerformanceInspectionConfig>(HTTP_INSPECTION_CONFIG_URL)
export const saveHttpPerformanceInspectionConfig = (payload: HttpPerformanceInspectionConfig) =>
  put<HttpPerformanceInspectionConfig>(HTTP_INSPECTION_CONFIG_URL, payload)

// ---------------- 密钥库 ----------------

export const apiCreateVaultGroup = (name: string) => post<VaultGroup>('/api/vault/groups', { name })
export const apiRenameVaultGroup = (id: string, name: string) =>
  patch<VaultGroup>(`/api/vault/groups/${id}`, { name })
export const apiDeleteVaultGroup = (id: string) => del(`/api/vault/groups/${id}`)
export const apiReorderVaultGroups = (ids: string[]) =>
  put<void>('/api/vault/groups/order', { ids })

export interface CreateVaultItemPayload {
  groupId: string
  name: string
  icon?: string
  user?: string
  site?: string
  note?: string
  secret?: string
}
export const apiCreateVaultItem = (payload: CreateVaultItemPayload) =>
  post<VaultItem>('/api/vault/items', payload)

export type PatchVaultItemPayload = Partial<Omit<CreateVaultItemPayload, 'groupId'>> & {
  groupId?: string
}
export const apiPatchVaultItem = (id: string, payload: PatchVaultItemPayload) =>
  patch<VaultItem>(`/api/vault/items/${id}`, payload)
export const apiDeleteVaultItem = (id: string) => del(`/api/vault/items/${id}`)
/** 按需解密并返回一次密码明文；未设置密码的条目返回 null。 */
export const apiRevealVaultItem = (id: string) =>
  post<{ secret: string | null }>(`/api/vault/items/${id}/reveal`)
