import { reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import * as F from '@/data/fixtures'
import {
  apiAddTaskComment,
  apiDeleteTask,
  apiDeleteTaskComment,
  apiUpdateTaskComment,
  apiCreateDoc,
  apiCreateFolder,
  apiCreateJob,
  apiCreateKb,
  apiCreateKbGroup,
  apiCreateNote,
  apiCreateProject,
  apiCreateTask,
  apiCreateVaultGroup,
  apiCreateVaultItem,
  apiDeleteDoc,
  apiDeleteFolder,
  apiDeleteFile,
  apiDeleteJob,
  apiDeleteKb,
  apiDeleteProject,
  apiDeleteVaultGroup,
  apiDeleteVaultItem,
  apiPatchDoc,
  apiPatchJob,
  apiPatchKb,
  apiPatchProject,
  apiPatchVaultItem,
  apiPauseJob,
  apiDeleteImage,
  apiPutTask,
  apiReauthJob,
  apiUploadImage,
  apiUploadFile,
  apiPurgeTrash,
  apiRenameFolder,
  apiRenameKbGroup,
  apiRenameVaultGroup,
  apiReorderVaultGroups,
  apiRestoreTrash,
  apiResumeJob,
  apiRevealVaultItem,
  apiRunJob,
  fetchAreas,
  fetchDocs,
  fetchFiles,
  fetchGoals,
  fetchImages,
  fetchJobs,
  fetchKbGroups,
  fetchKbs,
  fetchProjects,
  fetchSchedule,
  fetchSuggestions,
  fetchTasks,
  fetchTrash,
  fetchVault,
  type CreateJobPayload,
  type CreateKbPayload,
  type PatchJobPayload,
  type PatchKbPayload,
} from '@/api/board'
import { blocksToText, textToBlocks } from '@/utils/doc'
import { nowIso, todayIso } from '@/utils/time'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { MAX_TASK_GROUPS } from '@/types'
import type {
  AiRun, AutomationJob, Comment, Doc, DocBlock, ImageRef, KnowledgeBase, Project, Task, TaskGroup, TaskStep,
  TrashEntry, VaultGroup, VaultItem,
} from '@/types'

/** 兜底分组的 id：项目还没建过分组时，所有任务都算落在它里面。 */
export const DEFAULT_TASK_GROUP = 'tg-default'

/** 文档编辑覆盖（标题 / 正文 Markdown）。 */
interface DocEdit {
  title?: string
  text?: string
}
/** 解析后的可编辑文档视图。 */
export interface ResolvedDoc {
  base: Doc
  title: string
  text: string
  blocks: DocBlock[]
}

function emptyDoc(id: string): Doc {
  return { id, title: '', author: 'wg', created: '—', updated: '—', links: [], blocks: [], backlinks: [] }
}

/**
 * 领域数据 store —— 前端阶段的单一数据源，从 fixtures 深拷贝进响应式状态。
 * 变更在内存内生效；文档 Markdown 编辑额外持久化到 localStorage（对齐原型 wao-docs-v1）。
 * 后端就绪后：把此处初始化改为 await src/api/*（读真实 /api），screens 无需改动。
 */

const DOC_KEY = 'wao-docs-v1'
const TASK_DRAFT_KEY = 'wao-task-drafts-v1'
// Pinia 返回的任务是 Proxy，structuredClone 会直接抛 DataCloneError；业务实体只含 JSON 数据。
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T

export type SaveState = 'idle' | 'saving' | 'saved' | 'error'

/**
 * 本地 id 生成器。
 *
 * <p>纯 `Date.now()` 在同一毫秒内会撞号 —— 连续新增几个子任务就可能拿到同一个 id，
 * 之后按 id 定位会全部命中第一条。加一个单调递增的序号把这种情况排除掉。
 */
let idSeq = 0
const uid = (prefix: string) => `${prefix}${Date.now()}${(idSeq++).toString(36)}`

export const useDataStore = defineStore('data', () => {
  const today = todayIso()
  const areas = ref(clone(F.AREAS))
  const projects = ref(clone(F.PROJECTS))
  const tasks = ref(clone(F.TASKS))
  const kbs = ref(clone(F.KBS))
  const docs = reactive<Record<string, Doc>>(clone(F.DOCS))
  const autos = ref(clone(F.AUTOS))
  const files = ref(clone(F.FILES))
  const imgs = ref(clone(F.IMGS))
  const vault = ref(clone(F.VAULT))
  const schedule = ref(clone(F.SCHED))
  const suggestions = ref(clone(F.SUGG))
  const goals = ref(clone(F.GOALS))
  /** 尚未被服务端确认的任务快照；刷新或意外关闭页面后仍可恢复。 */
  const taskDrafts = ref<Record<string, Task>>(loadTaskDrafts())
  /** 用户自建知识库分组（个人库归入其中）。 */
  const myGroups = ref([{ id: 'g1', name: '我的知识库' }])

  /** 是否已成功接入真实后端（否则用 fixtures 兜底）。 */
  const apiLive = ref(false)

  /**
   * 从 /api 拉取各域数据替换 fixtures；后端不可用时静默保留 fixtures，应用仍可运行。
   *
   * <p>按域独立容错（{@code Promise.allSettled}）：单个域（如内容域表尚未建好而 500）失败时，
   * 其余域仍正常接管，且不会拖垮写路径。只要核心读路径（领域 / 项目 / 任务）可达即判定后端在线
   * （{@code apiLive=true}），从而新建 / 更新会真正落库，而非仅本地乐观更新。
   */
  async function hydrate() {
    const settle = async <T>(p: Promise<T>): Promise<T | null> => {
      try {
        return await p
      } catch {
        return null
      }
    }
    const [a, p, t, kb, kg, dc, jb, fl, im, va, sc, sg, gl] = await Promise.all([
      settle(fetchAreas()), settle(fetchProjects()), settle(fetchTasks()), settle(fetchKbs()),
      settle(fetchKbGroups()), settle(fetchDocs()), settle(fetchJobs()), settle(fetchFiles()),
      settle(fetchImages()), settle(fetchVault()), settle(fetchSchedule()),
      settle(fetchSuggestions()), settle(fetchGoals()),
    ])
    // `[]` 是后端明确返回的真实空状态，不能继续露出 fixtures；只有请求失败（null）才保留兜底。
    if (a !== null) areas.value = a
    if (p !== null) projects.value = p
    if (t !== null) {
      tasks.value = t
      // 只叠加仍未收到服务端确认的本地草稿，避免刷新把刚输入的长文本吞掉。
      for (const [id, draft] of Object.entries(taskDrafts.value)) {
        const current = tasks.value.find((task) => task.id === id)
        if (current) Object.assign(current, clone(draft))
      }
    }
    if (kb !== null) kbs.value = kb
    if (kg !== null) myGroups.value = kg
    if (dc !== null) {
      Object.keys(docs).forEach((id) => delete docs[id])
      dc.forEach((d) => (docs[d.id] = d))
    }
    if (jb !== null) autos.value = jb
    if (fl !== null) files.value = fl
    if (im !== null) imgs.value = im
    if (va !== null) vault.value = va
    if (sc !== null) schedule.value = sc
    if (sg !== null) suggestions.value = sg
    if (gl !== null) goals.value = gl
    // 核心读路径任一可达即认为后端在线；写路径（POST/PUT）随之启用。
    apiLive.value = a !== null && p !== null && t !== null
    // 每个项目都应有一个默认知识库（后端就绪前为前端内存态），刷新后由此保持。
    projects.value.forEach((proj) => ensureProjectKb(proj))
    if (apiLive.value) {
      startSync()
      Object.keys(taskDrafts.value).forEach(persistTask)
    }
  }

  /**
   * 后台轮询：周期性从后端拉取项目与任务，让其他人 / 其他标签页的新建、状态变更
   * 无需手动大刷即可出现（对齐「新建任务后别人要刷新才可见」的修复）。
   * 仅在页面可见时轮询，避免后台标签页空转；页面重新获得焦点时立即同步一次。
   */
  let syncTimer: ReturnType<typeof setInterval> | null = null
  let syncing = false
  async function syncFromServer() {
    if (!apiLive.value || syncing || document.hidden) return
    syncing = true
    try {
      const [p, t] = await Promise.all([fetchProjects(), fetchTasks()])
      if (Array.isArray(p)) mergeList(projects.value, p)
      if (Array.isArray(t)) mergeList(tasks.value, t, taskProtectedIds())
    } catch {
      /* 网络抖动时静默跳过，下个周期重试 */
    } finally {
      syncing = false
    }
  }
  /** 用服务端列表就地合并本地数组：更新已存在项、追加新项、移除已删除项，保持数组引用稳定。 */
  function mergeList<T extends { id: string }>(local: T[], remote: T[], protectedIds = new Set<string>()) {
    const remoteIds = new Set(remote.map((r) => r.id))
    for (const r of remote) {
      const existing = local.find((l) => l.id === r.id)
      if (existing) {
        if (!protectedIds.has(r.id)) Object.assign(existing, r)
      } else {
        local.push(r)
      }
    }
    for (let i = local.length - 1; i >= 0; i--) {
      const id = local[i]!.id
      // 刚本地乐观创建、POST 还没回来的记录不能按「服务端没有」删掉，否则新建的任务会闪没。
      if (!remoteIds.has(id) && !pendingTaskIds.has(id)) local.splice(i, 1)
    }
  }
  function startSync() {
    if (syncTimer) return
    syncTimer = setInterval(syncFromServer, 15_000)
    window.addEventListener('focus', syncFromServer)
    document.addEventListener('visibilitychange', syncFromServer)
  }

  /**
   * 把 AI Run 的服务端状态投影到任务列表，不触发任务自动保存。
   *
   * <p>运行状态由 Runner / 服务端控制；如果这里复用 {@link patchTask}，会把整份浏览器旧快照 PUT
   * 回去，反而可能覆盖刚完成的状态。仅改本地状态即可，下一次任务轮询会再以服务端记录校准。
   * 同一任务有多个运行时，活动运行优先；都结束后以最新创建的一次为准。
   */
  function applyAiRunStatuses(runs: AiRun[]) {
    const byTask = new Map<string, AiRun[]>()
    for (const run of runs) byTask.set(run.taskId, [...(byTask.get(run.taskId) ?? []), run])
    for (const [taskId, taskRuns] of byTask) {
      const active = taskRuns.find((run) => ['queued', 'claimed', 'running'].includes(run.status))
      const latest = active ?? [...taskRuns].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
      const task = taskById(taskId)
      if (!task || !latest) continue
      const status = ['queued', 'claimed', 'running'].includes(latest.status)
        ? 'AI 进行中'
        : latest.status === 'succeeded' ? '待验证' : '进行中'
      task.status = status
      task.completedAt = null
    }
  }

  // ---- getters ----
  const areaById = (id: string) => areas.value.find((a) => a.id === id)
  const projectById = (id: string) => projects.value.find((p) => p.id === id)
  const taskById = (id: string) => tasks.value.find((t) => t.id === id)
  const tasksByProject = (pid: string) => tasks.value.filter((t) => t.project === pid)
  const kbById = (id: string) => kbs.value.find((k) => k.id === id)
  const docById = (id: string): Doc | undefined => docs[id]

  /** 领域色（找不到用弱灰）。 */
  const areaColor = (id: string) => areaById(id)?.color ?? '#9A9DA9'

  // ---- 自动保存状态 ----
  /**
   * 「正在保存 / 上次保存成功」的登记簿，key 形如 {@code task:t17} / {@code doc:d3}。
   *
   * <p>所有会自动保存的编辑区（任务描述、子任务描述、知识库文档）都往这里记一笔，
   * 右上角的 {@code SaveHint} 据此显示「自动保存成功 14:32」——不给回执，用户没法
   * 确认东西真的写进去了。
   */
  const saveStates = reactive<Record<string, { saving: boolean; savedAt: string | null }>>({})
  const EMPTY_SAVE = { saving: false, savedAt: null }
  /** 读某个编辑区的保存状态（没登记过就是「没存过也没在存」）。 */
  const saveState = (key: string) => saveStates[key] ?? EMPTY_SAVE
  function markSaving(key: string) {
    saveStates[key] = { saving: true, savedAt: saveStates[key]?.savedAt ?? null }
  }
  /** 一次写透结束：成功才推进「上次保存成功」的时间。 */
  function markSaved(key: string, ok: boolean) {
    saveStates[key] = { saving: false, savedAt: ok ? nowIso() : (saveStates[key]?.savedAt ?? null) }
  }

  // ---- 文档 Markdown 编辑（持久化，对齐原型 wao-docs-v1） ----
  const docEdits = ref<Record<string, DocEdit>>(loadDocEdits())
  function loadDocEdits(): Record<string, DocEdit> {
    try {
      return JSON.parse(localStorage.getItem(DOC_KEY) || '{}')
    } catch {
      return {}
    }
  }
  /** 待写透的文档 patch 计时器（按文档 id 去抖，避免每次按键都请求后端）。 */
  const docPersistTimers: Record<string, ReturnType<typeof setTimeout>> = {}
  /** 有未写透编辑的文档 id —— 协同拉取时不能用服务端版本盖掉这些文档。 */
  const docDirty = reactive<Record<string, boolean>>({})
  /**
   * 合并一次文档编辑（title / text）并持久化；apiLive 时去抖写透后端。
   *
   * <p>去抖 600ms 是「实时保存」与「别把每次按键都送上网」之间的折中：连续输入时
   * 只在停顿处发一次请求，停手不到一秒内容就已经在服务端了。
   */
  function setDocEdit(id: string, patch: DocEdit) {
    docEdits.value = { ...docEdits.value, [id]: { ...docEdits.value[id], ...patch } }
    localStorage.setItem(DOC_KEY, JSON.stringify(docEdits.value))
    if (!apiLive.value) return
    docDirty[id] = true
    markSaving(`doc:${id}`)
    clearTimeout(docPersistTimers[id])
    docPersistTimers[id] = setTimeout(() => {
      const resolved = docOf(id)
      // 落库后只更新 base（版本号要跟上，否则下一次心跳会把自己的内容当成过期的拉回来），
      // 本地覆盖**不清**：清了 docOf 就会改从 blocks 反推正文，编辑器会当成外部改动
      // 重新灌一遍内容，光标当场跳回开头。覆盖由 applyRemoteDoc 在真的收到别人的
      // 版本时才清。
      apiPatchDoc(id, { title: resolved.title, blocks: resolved.blocks })
        .then((saved) => {
          docs[id] = saved
          markSaved(`doc:${id}`, true)
        })
        .catch(() => markSaved(`doc:${id}`, false))
        .finally(() => (docDirty[id] = false))
    }, 600)
  }
  /** 是否有还没写透后端的本地编辑。 */
  const docPending = (id: string) => docDirty[id] === true
  /**
   * 用服务端版本替换本地文档（协同心跳发现别人改过时）。
   *
   * <p>本地正在输入（还有未写透的编辑）时直接跳过 —— 宁可晚一个心跳周期合上，
   * 也不能把用户刚敲的半句话吞掉。
   */
  function applyRemoteDoc(doc: Doc): boolean {
    if (docPending(doc.id)) return false
    const override = docEdits.value[doc.id]
    docs[doc.id] = doc
    // 内容其实一样（多半是自己刚存的那一版转了一圈回来）就别动覆盖，
    // 否则编辑器会因为文本换了个写法而重灌一次内容，光标白跳。
    if (override && override.text != null && override.text !== blocksToText(doc.blocks)) {
      const { [doc.id]: _dropped, ...rest } = docEdits.value
      docEdits.value = rest
      localStorage.setItem(DOC_KEY, JSON.stringify(docEdits.value))
    }
    return true
  }
  /** 解析文档：叠加编辑覆盖，返回标题 / Markdown / 块。 */
  function docOf(id: string): ResolvedDoc {
    const d = docs[id] ?? emptyDoc(id)
    const o = docEdits.value[id]
    const title = o?.title != null ? o.title : d.title
    const text = o?.text != null ? o.text : blocksToText(d.blocks)
    const blocks = o?.text != null ? textToBlocks(o.text) : d.blocks
    return { base: d, title, text, blocks }
  }
  /** 新建自建分组，返回 id；apiLive 时经后端创建。 */
  async function addGroup(): Promise<string> {
    if (apiLive.value) {
      try {
        const g = await apiCreateKbGroup('新建分组')
        myGroups.value.push(g)
        return g.id
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const id = 'g' + Date.now()
    myGroups.value.push({ id, name: '新建分组' })
    return id
  }
  /** 重命名自建分组；apiLive 时写透后端。 */
  function renameGroup(id: string, name: string) {
    const g = myGroups.value.find((x) => x.id === id)
    if (g) g.name = name
    if (apiLive.value) apiRenameKbGroup(id, name).catch(() => {})
  }
  /** 给知识库新增一个文件夹，返回文件夹 id；apiLive 时经后端创建。 */
  async function addFolder(kbId: string): Promise<string | null> {
    const kb = kbById(kbId)
    if (!kb) return null
    if (apiLive.value) {
      try {
        const f = await apiCreateFolder(kbId, '新建文件夹')
        kb.folders.push(f)
        return f.id
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const id = 'f' + Date.now()
    kb.folders.push({ id, name: '新建文件夹', docs: [] })
    return id
  }

  /**
   * 在知识库某文件夹新建空文档，返回新 id；apiLive 时经后端创建（新文档即关联到该知识库 / 文件夹）。
   *
   * <p>标题留空而不是塞「未命名文档」：新建后光标就落在标题上，占位文案由 placeholder 提供，
   * 用户一敲就是自己的标题，不用先把四个字删掉。作者由服务端按会话署名，离线兜底才用当前登录成员。
   */
  async function addDoc(kbId: string, folderId: string): Promise<string | null> {
    const kb = kbById(kbId)
    const folder = kb?.folders.find((f) => f.id === folderId)
    if (!kb || !folder) return null
    if (apiLive.value) {
      try {
        const d = await apiCreateDoc({ kbId, folderId, title: '' })
        docs[d.id] = d
        folder.docs.push(d.id)
        return d.id
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const id = uid('d')
    docs[id] = {
      id, title: '', author: currentAuthor(), created: nowIso(), updated: nowIso(),
      links: kb.project ? [{ type: 'project', id: kb.project }] : [],
      blocks: [], backlinks: [], kbId, folderId, rev: 1,
    }
    folder.docs.push(id)
    return id
  }

  /** 当前登录成员 id；未登录（演示态）时回落到 wg。 */
  function currentAuthor(): string {
    return useSessionStore().currentUser.id || 'wg'
  }

  /**
   * 小记知识库里属于当前登录成员的文件夹，没有就地建一个。
   *
   * 与后端 KnowledgeService.authorFolder 同一口径：文件夹按成员名命名
   * （娃哥的进「娃哥」、娃弟的进「娃弟」），这样谁记的一眼可见。
   */
  function noteFolderOf(kb: KnowledgeBase, folderId?: string): KnowledgeBase['folders'][number] {
    if (folderId) {
      const known = kb.folders.find((f) => f.id === folderId)
      if (known) return known
    }
    const author = currentAuthor()
    const member = useSessionStore().memberById(author)
    // memberById 查不到时会拿 id 顶名字，这种情况按「作者不明」处理，落回默认文件夹。
    const name = member.name && member.name !== author ? member.name : ''
    if (!name) return kb.folders.find((f) => f.id === 'kb-notes-f') ?? kb.folders[0]!
    const existing = kb.folders.find((f) => f.name === name)
    if (existing) return existing
    const folder = { id: folderId ?? `kb-notes-${author}`, name, docs: [] as string[] }
    kb.folders.push(folder)
    return folder
  }

  /** 小记知识库：全局「新建小记」的默认落点，没有就地建一个（离线演示态）。 */
  function notesKb(): KnowledgeBase {
    const existing = kbs.value.find((k) => k.notes)
    if (existing) return existing
    const kb: KnowledgeBase = {
      id: 'kb-notes', name: '小记', icon: 'ri-sticky-note-line', color: '#7A5CC6', project: null,
      desc: '随手记下的想法与片段，不用先想清楚该放进哪个知识库。', notes: true,
      folders: [{ id: 'kb-notes-f', name: '小记', docs: [] }], group: 'g1', perm: '仅自己',
    }
    kbs.value.unshift(kb)
    return kb
  }

  /**
   * 新建一条小记：不用先选知识库，直接落进小记知识库。
   *
   * @param text  小记正文（Markdown）
   * @param title 标题，留空时由正文首行推导
   * @return 新建的文档 id；正文与标题都为空时返回 null
   */
  async function addNote(text: string, title = ''): Promise<string | null> {
    const blocks = textToBlocks(text)
    if (!blocks.length && !title.trim()) return null
    if (apiLive.value) {
      try {
        const d = await apiCreateNote({ title: title.trim(), blocks })
        docs[d.id] = d
        const kb = kbs.value.find((k) => k.id === d.kbId) ?? notesKb()
        noteFolderOf(kb, d.folderId).docs.push(d.id)
        return d.id
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const kb = notesKb()
    const folder = noteFolderOf(kb)
    const id = uid('d')
    docs[id] = {
      id, title: title.trim() || noteTitleOf(text), author: currentAuthor(),
      created: nowIso(), updated: nowIso(), links: [], blocks, backlinks: [],
      kbId: kb.id, folderId: folder.id, rev: 1,
    }
    folder.docs.push(id)
    return id
  }

  /** 小记没填标题时，用正文首行当标题（与后端 titleFrom 口径一致）。 */
  function noteTitleOf(text: string): string {
    const line = text.split('\n').map((l) => l.replace(/^[#>\-!\s]+/, '').trim()).find(Boolean)
    if (!line) return '未命名小记'
    return line.length > 30 ? line.slice(0, 30) + '…' : line
  }

  /** 新建知识库；apiLive 时经后端创建。 */
  async function addKb(payload: CreateKbPayload): Promise<KnowledgeBase | null> {
    if (apiLive.value) {
      try {
        const kb = await apiCreateKb(payload)
        kbs.value.unshift(kb)
        return kb
      } catch {
        return null
      }
    }
    const kb: KnowledgeBase = {
      id: 'kb' + Date.now(), name: payload.name, icon: payload.icon || 'ri-book-2-line',
      color: payload.color || '#4358D6', project: payload.project ?? null,
      desc: payload.desc || '', folders: [{ id: 'f' + Date.now(), name: '文档', docs: [] }],
      group: payload.group, perm: payload.perm,
    }
    kbs.value.unshift(kb)
    return kb
  }

  /** 更新知识库（名称 / 简介 / 图标 / 颜色 / 分组 / 权限）；apiLive 时写透后端。 */
  function updateKb(id: string, patch: PatchKbPayload) {
    const kb = kbById(id)
    if (!kb) return
    Object.assign(kb, patch)
    if (apiLive.value) apiPatchKb(id, patch).catch(() => {})
  }

  /**
   * 删除知识库（连同其文件夹与文档）；apiLive 时写透后端。
   *
   * <p>后端是软删除 —— 东西进回收站，7 天内可以整库恢复（见 ADR-018），
   * 所以这里从列表里拿掉即可，不需要给用户「确认永久删除」的心理负担。
   */
  async function deleteKb(id: string) {
    const kb = kbById(id)
    if (kb?.notes) return
    kbs.value = kbs.value.filter((k) => k.id !== id)
    kb?.folders.forEach((f) => f.docs.forEach((docId) => delete docs[docId]))
    if (apiLive.value) {
      try {
        await apiDeleteKb(id)
      } catch {
        /* 后端删除失败时不回滚 */
      }
    }
    void refreshTrash()
  }

  /** 重命名文件夹；apiLive 时写透后端。 */
  function renameFolder(kbId: string, folderId: string, name: string) {
    const folder = kbById(kbId)?.folders.find((f) => f.id === folderId)
    if (!folder) return
    folder.name = name
    if (apiLive.value) apiRenameFolder(kbId, folderId, name).catch(() => {})
  }

  /** 删除文件夹（连同其下文档）—— 后端移入回收站。 */
  async function deleteFolder(kbId: string, folderId: string) {
    const kb = kbById(kbId)
    const folder = kb?.folders.find((f) => f.id === folderId)
    if (!kb || !folder) return
    folder.docs.forEach((docId) => delete docs[docId])
    kb.folders = kb.folders.filter((f) => f.id !== folderId)
    if (apiLive.value) {
      try {
        await apiDeleteFolder(kbId, folderId)
      } catch {
        /* 后端删除失败时不回滚 */
      }
    }
    void refreshTrash()
  }

  /** 删除文档 —— 后端移入回收站，7 天内可恢复。 */
  async function deleteDoc(id: string) {
    kbs.value.forEach((k) => k.folders.forEach((f) => (f.docs = f.docs.filter((d) => d !== id))))
    delete docs[id]
    const { [id]: _dropped, ...rest } = docEdits.value
    docEdits.value = rest
    localStorage.setItem(DOC_KEY, JSON.stringify(docEdits.value))
    if (apiLive.value) {
      try {
        await apiDeleteDoc(id)
      } catch {
        /* 后端删除失败时不回滚 */
      }
    }
    void refreshTrash()
  }

  // ---- 回收站 ----

  /** 回收站条目（知识库 / 文件夹 / 文档），保留 7 天后由后端自动清理。 */
  const trash = ref<TrashEntry[]>([])

  /** 重新拉取回收站列表。 */
  async function refreshTrash() {
    if (!apiLive.value) return
    try {
      trash.value = await fetchTrash()
    } catch {
      /* 拉取失败时保留现有列表 */
    }
  }

  /** 从回收站恢复一个条目，随后重新拉取知识库与文档（父级可能一起被恢复）。 */
  async function restoreTrash(type: string, id: string) {
    if (!apiLive.value) return
    await apiRestoreTrash(type, id)
    await Promise.all([refreshKnowledge(), refreshTrash()])
  }

  /** 立刻彻底删除一个回收站条目，不等 7 天。 */
  async function purgeTrash(type: string, id: string) {
    if (!apiLive.value) return
    await apiPurgeTrash(type, id)
    await refreshTrash()
  }

  /** 重新拉取知识库与文档（恢复、别处新建之后用）。 */
  async function refreshKnowledge() {
    if (!apiLive.value) return
    try {
      const [kb, dc] = await Promise.all([fetchKbs(), fetchDocs()])
      if (Array.isArray(kb)) kbs.value = kb
      if (Array.isArray(dc)) dc.forEach((d) => (docs[d.id] = d))
    } catch {
      /* 拉取失败时保留现有数据 */
    }
  }

  // ---- task 变更 ----
  function loadTaskDrafts(): Record<string, Task> {
    try {
      return JSON.parse(localStorage.getItem(TASK_DRAFT_KEY) || '{}')
    } catch {
      return {}
    }
  }

  const taskPersistTimers: Record<string, ReturnType<typeof setTimeout>> = {}
  const taskPersistVersions: Record<string, number> = {}
  const taskPersisting = reactive<Record<string, boolean>>({})
  const taskSaveStates = reactive<Record<string, SaveState>>({})
  /** 每个任务上一次写透成功的时刻 —— 「自动保存成功 14:32」里的那个时间。 */
  const taskSavedAts = reactive<Record<string, string>>({})

  function storeTaskDraft(task: Task) {
    taskDrafts.value = { ...taskDrafts.value, [task.id]: clone(task) }
    localStorage.setItem(TASK_DRAFT_KEY, JSON.stringify(taskDrafts.value))
  }

  function clearTaskDraft(id: string) {
    const { [id]: _saved, ...rest } = taskDrafts.value
    taskDrafts.value = rest
    localStorage.setItem(TASK_DRAFT_KEY, JSON.stringify(rest))
  }

  /** 刚被本地改过的任务 id → 时刻。 */
  const taskTouchedAt: Record<string, number> = {}
  /**
   * 本地编辑后的保护窗口。
   *
   * <p>草稿写透成功就会被清掉，但一次轮询可能是在写透**之前**发出的：它带回来的是旧正文，
   * 落地时却盖在更新的本地内容上，编辑器随即整篇重灌 —— 用语音听写时这一下会把正在念的
   * 段落打断并重念一遍。所以最后一次改动之后再护 20 秒，够任何一次在途请求跑完。
   */
  const EDIT_GUARD_MS = 20_000

  function taskProtectedIds() {
    const now = Date.now()
    const recent = Object.keys(taskTouchedAt).filter((id) => now - (taskTouchedAt[id] ?? 0) < EDIT_GUARD_MS)
    return new Set([
      ...Object.keys(taskDrafts.value),
      ...Object.keys(taskPersisting).filter((id) => taskPersisting[id]),
      ...recent,
    ])
  }

  /** 当前任务自动保存状态，供富文本旁明确展示。 */
  const taskSaveState = (id: string): SaveState => taskSaveStates[id] ?? 'idle'
  /** 上一次写透成功的时刻（ISO），没成功过时为 null。 */
  const taskSavedAt = (id: string): string | null => taskSavedAts[id] ?? null

  /**
   * 串行写透最新任务快照。写入期间若又有修改，旧请求完成后立刻续写最新版本；
   * 后台轮询在草稿确认前也不会覆盖本地任务。
   */
  async function drainTask(id: string): Promise<void> {
    if (taskPersisting[id]) return
    const task = taskById(id)
    if (!task) return
    if (!apiLive.value) {
      taskSaveStates[id] = 'saved'
      taskSavedAts[id] = nowIso()
      return
    }
    const version = taskPersistVersions[id] ?? 0
    const snapshot = clone(task)
    taskPersisting[id] = true
    try {
      await apiPutTask(id, snapshot)
      if ((taskPersistVersions[id] ?? 0) === version) {
        clearTaskDraft(id)
        taskSaveStates[id] = 'saved'
        taskSavedAts[id] = nowIso()
      }
    } catch {
      taskSaveStates[id] = 'error'
    } finally {
      taskPersisting[id] = false
      if ((taskPersistVersions[id] ?? 0) !== version) {
        taskSaveStates[id] = 'saving'
        void drainTask(id)
      }
    }
  }

  /** 关闭抽屉或切换上下文时立即启动写透，不再等待去抖计时。 */
  async function flushTask(id: string) {
    clearTimeout(taskPersistTimers[id])
    await drainTask(id)
    while (taskPersisting[id]) await new Promise((resolve) => window.setTimeout(resolve, 25))
  }

  /** 独立追加评论，避免评论与富文本全文自动保存互相覆盖。 */
  async function addTaskComment(id: string, text: string) {
    const task = taskById(id)
    if (!task) return false
    await flushTask(id)
    try {
      const comment = apiLive.value
        ? await apiAddTaskComment(id, text)
        : { id: 'c' + Date.now(), who: currentAuthor(), time: nowIso(), text }
      task.comments.push(comment)
      return true
    } catch { return false }
  }

  /** 评论定位键：优先用服务端给的 id，老评论（没有 id）回落到下标，与后端口径一致。 */
  const commentKey = (comment: Comment, index: number) => comment.id ?? String(index)

  /**
   * 改一条评论的正文。只能改自己的 —— 服务端也会再拦一次。
   *
   * @return 是否改成功
   */
  async function updateTaskComment(id: string, index: number, text: string): Promise<boolean> {
    const task = taskById(id)
    const comment = task?.comments[index]
    if (!task || !comment) return false
    const body = text.trim()
    if (!body) return false
    const previous = comment.text
    comment.text = body
    if (!apiLive.value) return true
    try {
      await apiUpdateTaskComment(id, commentKey(comment, index), body)
      return true
    } catch {
      comment.text = previous
      return false
    }
  }

  /**
   * 删一条评论。只能删自己的。
   *
   * @return 是否删成功
   */
  async function deleteTaskComment(id: string, index: number): Promise<boolean> {
    const task = taskById(id)
    const comment = task?.comments[index]
    if (!task || !comment) return false
    const key = commentKey(comment, index)
    task.comments.splice(index, 1)
    if (!apiLive.value) return true
    try {
      await apiDeleteTaskComment(id, key)
      return true
    } catch {
      task.comments.splice(index, 0, comment)
      return false
    }
  }

  /**
   * 删除一个任务。本地先拿掉，写透失败再放回去 —— 误删一条任务比多等一秒难受得多，
   * 所以先给反馈；真失败了列表会自己恢复。
   *
   * @return 是否删成功
   */
  async function deleteTask(id: string): Promise<boolean> {
    const index = tasks.value.findIndex((t) => t.id === id)
    if (index < 0) return false
    const [removed] = tasks.value.splice(index, 1)
    delete taskTouchedAt[id]
    clearTaskDraft(id)
    if (!apiLive.value) return true
    try {
      await apiDeleteTask(id)
      return true
    } catch {
      if (removed) tasks.value.splice(index, 0, removed)
      return false
    }
  }

  /** 合并字段到任务，可选写入一条活动时间线（对齐原型 patchTask）。 */
  function patchTask(id: string, fields: Partial<Task>, activityText?: string) {
    const t = taskById(id)
    if (!t) return
    taskTouchedAt[id] = Date.now()
    // 完成时间跟着状态走：进入「已完成」记当下，离开则清空（服务端会重新校准）
    if (fields.status !== undefined && fields.status !== t.status && fields.completedAt === undefined) {
      fields = { ...fields, completedAt: fields.status === '已完成' ? nowIso() : null }
    }
    Object.assign(t, fields)
    if (activityText) logActivity(t, activityText)
    persistTask(id)
  }

  /** 新建任务负载：核心字段 + 可选描述 / 内嵌图片 / 落点分组。 */
  type NewTaskPayload = Pick<Task, 'title' | 'project' | 'pr' | 'owner' | 'due'> &
    Pick<Task, 'desc' | 'images' | 'groupId'>

  /** 本地已创建、服务端 id 尚未回来的任务 id —— 后台同步不得把它们当成「已删除」清掉。 */
  const pendingTaskIds = new Set<string>()

  /**
   * 新建任务：先本地插入并立即返回，POST 在后台跑完再用服务端记录替换。
   *
   * <p>之前是 {@code await} 完 POST 才返回，调用方要等一个完整往返（线上 2–3 秒）
   * 弹窗才关闭，看起来像卡住。现在 UI 立刻响应，落库失败则回落成 PUT 重试一次，
   * 仍失败就保留本地记录（下一次任意改动的写透会把它补进库）。
   *
   * @param payload 新建任务负载
   * @return 立即可用的任务对象（后端返回后其字段会被就地更新）
   */
  async function addTask(payload: NewTaskPayload): Promise<Task> {
    const { desc = '', images = [], ...core } = payload
    const local: Task = {
      id: uid('t'), status: '待办', parts: [], desc, steps: [], subs: [], files: [],
      docs: [], linkTasks: [], comments: [], custom: [], images,
      activity: [{ icon: 'ri-add-line', color: '#9A9DA9', text: '创建了任务', time: nowIso(), by: currentAuthor() }],
      ...core,
    }
    tasks.value.unshift(local)
    if (!apiLive.value) return local

    pendingTaskIds.add(local.id)
    apiCreateTask({ ...core, desc, images })
      .then((saved) => {
        const i = tasks.value.findIndex((t) => t.id === local.id)
        if (i >= 0) tasks.value.splice(i, 1, saved)
        // 抽屉可能已经用本地 id 打开了，换成服务端 id 才能继续写透。
        const ui = useUiStore()
        if (ui.openTaskId === local.id) ui.openTask(saved.id)
      })
      .catch(() => {
        // POST 失败时保留用户刚录入的任务，并把它纳入与编辑任务相同的可恢复草稿机制。
        // 这样轮询不会把它误删；随后用幂等 PUT 自动补写一次，仍失败才由保存状态明确提示。
        persistTask(local.id)
      })
      .finally(() => pendingTaskIds.delete(local.id))
    return local
  }

  /** 新建项目；apiLive 时经后端创建，否则本地乐观创建。创建后自动生成一个默认知识库。 */
  async function addProject(payload: { name: string; area: string; goal: string; visAll: boolean }): Promise<Project> {
    let created: Project | null = null
    if (apiLive.value) {
      try {
        created = await apiCreateProject(payload)
        projects.value.unshift(created)
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    if (!created) {
      created = {
        id: 'p' + Date.now(), name: payload.name, icon: 'ri-stack-line', area: payload.area, status: '规划中', health: '良好',
        progress: 0, owner: 'wg', members: payload.visAll ? ['wg', 'wd'] : ['wg'],
        start: today, due: '2026-09-30',
        visibility: payload.visAll ? '组织内 2 人 · 可查看' : '仅自己可见',
        updated: '刚刚', goal: payload.goal || '尚未填写目标——打开项目后可补充。',
        milestones: [], msDates: [], trend: [0, 0], blockers: [], docs: [], files: [],
        activity: [{ icon: 'ri-add-line', color: '#9A9DA9', text: '创建了项目', time: nowIso(), by: currentAuthor() }],
      }
      projects.value.unshift(created)
    }
    if (!created.icon) created.icon = 'ri-stack-line'
    ensureProjectKb(created)
    return created
  }

  /** 为项目创建一个默认知识库（若尚无关联库）。知识库为前端内存态，随项目一起出现。 */
  function ensureProjectKb(p: Project): KnowledgeBase {
    const existing = kbs.value.find((k) => k.project === p.id)
    if (existing) return existing
    const kb: KnowledgeBase = {
      id: 'kb' + Date.now(), name: `${p.name} · 知识库`, icon: 'ri-book-2-line',
      color: areaColor(p.area), project: p.id, desc: '项目资料、会议纪要与参考都沉淀在这里。',
      folders: [{ id: 'f' + Date.now(), name: '文档', docs: [] }],
    }
    kbs.value.unshift(kb)
    return kb
  }

  /** 更新项目字段（名称 / 领域 / 目标 / 图标 / 通知开关）；apiLive 时写透后端。 */
  async function updateProject(
    id: string,
    patch: { name?: string; area?: string; goal?: string; icon?: string; notifyEnabled?: boolean },
  ): Promise<boolean> {
    const p = projectById(id)
    if (!p) return false
    Object.assign(p, patch)
    if (!apiLive.value) return true
    try {
      await apiPatchProject(id, patch)
      return true
    } catch {
      /* 保留本地乐观更新，但要让调用方知道没落库，好提示重试 */
      return false
    }
  }

  /**
   * 项目的任务分组列表；一个都没建过时给一个「未分组」兜底。
   *
   * <p>兜底分组不落库，只是让分组视图一进来就有地方放任务；用户改了名字或加了第二个分组，
   * 才会连同它一起写透（{@link setTaskGroups}）。
   *
   * @param projectId 项目 id
   * @return 至少含一个分组的列表
   */
  function taskGroupsOf(projectId: string): TaskGroup[] {
    const stored = projectById(projectId)?.taskGroups
    return stored?.length ? stored : [{ id: DEFAULT_TASK_GROUP, name: '未分组' }]
  }

  /**
   * 整表替换某个项目的任务分组（新建 / 改名 / 删除都走这里）。
   *
   * @param projectId 项目 id
   * @param groups    完整分组列表，超过上限的部分由服务端截断
   * @return 是否写透成功；离线演示态直接返回 true
   */
  async function setTaskGroups(projectId: string, groups: TaskGroup[]): Promise<boolean> {
    const p = projectById(projectId)
    if (!p) return false
    const next = groups.slice(0, MAX_TASK_GROUPS)
    p.taskGroups = next
    if (!apiLive.value) return true
    try {
      await apiPatchProject(projectId, { taskGroups: next })
      return true
    } catch {
      return false
    }
  }

  /**
   * 把任务挪进某个分组（分组视图里拖拽 / 在任务详情里选）。
   *
   * @param taskId  任务 id
   * @param groupId 目标分组 id
   */
  function moveTaskToGroup(taskId: string, groupId: string) {
    const t = taskById(taskId)
    if (!t || (t.groupId ?? DEFAULT_TASK_GROUP) === groupId) return
    const name = t.project ? taskGroupsOf(t.project).find((g) => g.id === groupId)?.name : ''
    patchTask(taskId, { groupId }, name ? `把任务移到分组「${name}」` : undefined)
  }

  /** 删除项目（连同其任务与关联知识库）；apiLive 时写透后端。 */
  async function deleteProject(id: string) {
    projects.value = projects.value.filter((p) => p.id !== id)
    tasks.value = tasks.value.filter((t) => t.project !== id)
    kbs.value = kbs.value.filter((k) => k.project !== id)
    if (apiLive.value) {
      try {
        await apiDeleteProject(id)
      } catch {
        /* 后端删除失败时不回滚，避免残留幽灵项目困扰 UI */
      }
    }
  }

  /** 把任务先落本地草稿，再以 600ms 去抖、严格串行的方式写透后端。 */
  function persistTask(id: string) {
    const t = taskById(id)
    if (!t) return
    storeTaskDraft(t)
    taskPersistVersions[id] = (taskPersistVersions[id] ?? 0) + 1
    taskSaveStates[id] = apiLive.value ? 'saving' : 'saved'
    clearTimeout(taskPersistTimers[id])
    taskPersistTimers[id] = setTimeout(() => void drainTask(id), 600)
  }

  /** 完成 / 重新打开一个任务（列表勾选用）；两个方向都写进活动时间线。 */
  function toggleComplete(id: string) {
    const t = taskById(id)
    if (!t) return
    const done = t.status !== '已完成'
    patchTask(id, { status: done ? '已完成' : '待办' }, done ? '完成了任务' : '重新打开了任务')
  }

  /** 将任务移动到同项目内目标任务之前（列表拖拽排序，前端顺序态）。 */
  function reorderTask(dragId: string, overId: string) {
    if (dragId === overId) return
    const arr = tasks.value
    const from = arr.findIndex((t) => t.id === dragId)
    if (from < 0) return
    const [moved] = arr.splice(from, 1)
    const to = arr.findIndex((t) => t.id === overId)
    arr.splice(to < 0 ? arr.length : to, 0, moved!)
  }

  /** 勾选步骤 / 子任务；记一条带名字的活动，事后能看出动的是哪一条。 */
  function toggleStep(id: string, idx: number, kind: 'steps' | 'subs') {
    const t = taskById(id)
    const item = t?.[kind][idx]
    if (!t || !item) return
    item.done = !item.done
    const what = kind === 'subs' ? '子任务' : '步骤'
    logActivity(t, `${item.done ? '完成了' : '重新打开了'}${what}「${item.text}」`)
    persistTask(id)
  }

  /**
   * 往任务活动时间线最前面写一条。
   *
   * <p>正文不带主语（「完成了子任务「X」」），操作人记在 `by` 里 —— 主语由展示时按
   * 「看的人是谁」现算，否则别人打开这条任务，看到的也是「你完成了」。
   */
  function logActivity(t: Task, text: string, icon = 'ri-edit-line') {
    t.activity.unshift({ icon, color: '#9A9DA9', text, time: nowIso(), by: currentAuthor() })
  }

  // ---- 子任务（内嵌在任务的 subs 里，不是独立任务实体） ----

  /** 按 id 定位子任务；历史数据没有 id 时回落到 `<taskId>#<下标>` 的合成 id。 */
  function subById(taskId: string, subId: string): TaskStep | undefined {
    const t = taskById(taskId)
    if (!t) return undefined
    return t.subs.find((s, i) => subKey(taskId, s, i) === subId)
  }

  /** 子任务的稳定标识：优先用自身 id，历史数据回落到下标合成键。 */
  function subKey(taskId: string, sub: TaskStep, idx: number): string {
    return sub.id ?? `${taskId}#${idx}`
  }

  /**
   * 新增一个子任务。
   *
   * <p>新增的排在最前面（时间倒序）：输入框就在列表上方，刚敲完的那条应该
   * 出现在眼皮底下，而不是被推到一长串旧条目的末尾。
   *
   * @param taskId 所属任务 id
   * @param text   子任务标题
   * @return 新子任务的标识，标题为空时返回 null
   */
  function addSub(taskId: string, text: string): string | null {
    const t = taskById(taskId)
    const title = text.trim()
    if (!t || !title) return null
    const id = uid('s')
    t.subs.unshift({ id, text: title, done: false, desc: '', images: [] })
    logActivity(t, `添加了子任务「${title}」`)
    persistTask(taskId)
    return id
  }

  /**
   * 更新子任务的标题 / 描述 / 完成态 / 图片。
   *
   * <p>只有完成态变化会写进活动时间线。改名、描述、图片都是逐次按键触发的，
   * 记下来会把时间线刷成一行一个字，反而看不出重点。
   *
   * @param taskId 所属任务 id
   * @param subId  子任务标识
   * @param patch  要合并的字段
   */
  function updateSub(taskId: string, subId: string, patch: Partial<TaskStep>) {
    const t = taskById(taskId)
    const sub = subById(taskId, subId)
    if (!t || !sub) return
    const wasDone = sub.done
    Object.assign(sub, patch)
    if (patch.done !== undefined && patch.done !== wasDone) {
      logActivity(t, `${patch.done ? '完成了' : '重新打开了'}子任务「${sub.text}」`)
    }
    persistTask(taskId)
  }

  /** 删除子任务。 */
  function removeSub(taskId: string, subId: string) {
    const t = taskById(taskId)
    if (!t) return
    const idx = t.subs.findIndex((s, i) => subKey(taskId, s, i) === subId)
    if (idx < 0) return
    const [removed] = t.subs.splice(idx, 1)
    logActivity(t, `删除了子任务「${removed!.text}」`, 'ri-delete-bin-line')
    persistTask(taskId)
  }

  /** 给子任务追加一张图片。 */
  function addSubImage(taskId: string, subId: string, image: ImageRef) {
    const sub = subById(taskId, subId)
    if (!sub) return
    sub.images = [...(sub.images ?? []), image]
    persistTask(taskId)
  }

  /** 移除子任务的第 idx 张图片。 */
  function removeSubImage(taskId: string, subId: string, idx: number) {
    const sub = subById(taskId, subId)
    if (!sub?.images) return
    sub.images = sub.images.filter((_, i) => i !== idx)
    persistTask(taskId)
  }

  // ---- 图床 ----

  /** 单张图片上限 10MB，与后端 ImageService.MAX_BYTES 一致。 */
  const MAX_IMAGE_BYTES = 10 * 1024 * 1024

  /**
   * 把图片存进图床并返回可引用的地址。
   *
   * <p>后端在线时真正上传（图片落主节点 MySQL，任务里只存 URL）；离线演示态回落成
   * 本地 data URL，界面表现一致，只是不会持久化。
   *
   * @param file   图片文件
   * @param source 来源描述，显示在图床列表里
   * @return 图片引用；超过大小上限或读取失败时返回 null
   */
  async function uploadImage(file: File, source?: string): Promise<ImageRef | null> {
    if (!file.type.startsWith('image/') || file.size > MAX_IMAGE_BYTES) return null
    if (apiLive.value) {
      try {
        const item = await apiUploadImage(file, source)
        imgs.value.unshift(item)
        return { name: item.name, url: item.url }
      } catch {
        /* 上传失败时回落成本地 data URL，至少不丢用户刚粘的图 */
      }
    }
    const url = await new Promise<string | null>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => resolve(null)
      reader.readAsDataURL(file)
    })
    if (!url) return null
    const name = file.name || '粘贴的图片'
    // 回落的图也要进图床列表——「所有上传的图都能在文件与图床里看到」不该因为
    // 后端临时不可用就出现缺口，只是这一张不会持久化。
    imgs.value.unshift({
      name, url, size: humanSize(file.size), time: '刚刚',
      from: source || '本地上传（未持久化）', c1: '#E8ECFA', c2: '#DDE3F6',
    })
    return { name, url }
  }

  const MAX_FILE_BYTES = 50 * 1024 * 1024
  async function refreshFiles() {
    if (!apiLive.value) return
    try { files.value = await fetchFiles() } catch { /* 保留当前列表 */ }
  }
  async function uploadFile(file: File) {
    if (!apiLive.value || file.size > MAX_FILE_BYTES) return null
    try {
      const item = await apiUploadFile(file)
      files.value.unshift(item)
      return item
    } catch { return null }
  }
  async function deleteFile(id: string) {
    if (!apiLive.value) return
    await apiDeleteFile(id)
    files.value = files.value.filter((file) => file.id !== id)
  }

  /** 重新拉取图床列表（图床页打开时刷新，让其他标签页 / 其他人上传的图也出现）。 */
  async function refreshImages() {
    if (!apiLive.value) return
    try {
      const list = await fetchImages()
      if (Array.isArray(list)) imgs.value = list
    } catch {
      /* 拉取失败时保留现有列表 */
    }
  }

  /**
   * 从图床删除一张图片。
   *
   * <p>不做引用检查：删掉后仍然引用它的任务描述会显示成破图，这是图床的常规行为
   * （见 ADR-012 的后续说明）。内置演示数据没有 id，不可删。
   *
   * @param id 图片 id
   */
  async function deleteImage(id: string) {
    imgs.value = imgs.value.filter((i) => i.id !== id)
    if (apiLive.value) {
      try {
        await apiDeleteImage(id)
      } catch {
        /* 后端删除失败时不回滚，避免残留幽灵条目困扰 UI */
      }
    }
  }

  /** 人类可读文件大小，与后端 ImageService.humanSize 口径一致。 */
  function humanSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  }

  // ---- 自动化 ----
  const jobById = (id: string) => autos.value.find((j) => j.id === id)

  /** 新建自动化任务；apiLive 时经后端创建，否则本地乐观创建。 */
  async function createJob(payload: CreateJobPayload): Promise<AutomationJob> {
    if (apiLive.value) {
      try {
        const job = await apiCreateJob(payload)
        autos.value.unshift(job)
        return job
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const job: AutomationJob = {
      id: 'j' + Date.now(), name: payload.name, icon: payload.icon || 'ri-flashlight-line',
      schedule: payload.schedule || (payload.cronExpr ?? '仅手动触发'), status: '正常', last: '尚未运行',
      desc: payload.desc || '', source: payload.source || '', target: payload.target || '',
      steps: payload.steps || [], runs: [],
    }
    autos.value.unshift(job)
    return job
  }

  /** 更新自动化任务；apiLive 时写透后端。 */
  async function updateJob(id: string, payload: PatchJobPayload) {
    const j = jobById(id)
    if (!j) return
    Object.assign(j, payload)
    if (apiLive.value) {
      try {
        const updated = await apiPatchJob(id, payload)
        Object.assign(j, updated)
      } catch {
        /* 保留本地乐观更新 */
      }
    }
  }

  /** 删除自动化任务；apiLive 时写透后端。 */
  async function deleteJob(id: string) {
    autos.value = autos.value.filter((j) => j.id !== id)
    if (apiLive.value) {
      try {
        await apiDeleteJob(id)
      } catch {
        /* 后端删除失败时不回滚 */
      }
    }
  }

  /** 手动触发一次运行。 */
  async function runJob(id: string) {
    const j = jobById(id)
    if (!j) return
    if (apiLive.value) {
      try {
        Object.assign(j, await apiRunJob(id))
        return
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const result = j.status === '待修复' ? '失败 · 需要先重新授权' : '成功 · 手动触发'
    j.runs.unshift({ time: '刚刚', executor: '你', result })
    j.last = '刚刚 · ' + result
  }

  /** 暂停自动化任务。 */
  async function pauseJob(id: string) {
    const j = jobById(id)
    if (!j) return
    j.status = '已暂停'
    if (apiLive.value) apiPauseJob(id).catch(() => {})
  }

  /** 恢复已暂停的自动化任务。 */
  async function resumeJob(id: string) {
    const j = jobById(id)
    if (!j) return
    j.status = '正常'
    if (apiLive.value) apiResumeJob(id).catch(() => {})
  }

  /** 重新授权「待修复」的自动化任务。 */
  async function reauthJob(id: string) {
    const j = jobById(id)
    if (!j) return
    j.status = '正常'
    if (apiLive.value) apiReauthJob(id).catch(() => {})
  }

  // ---- 密钥库 ----
  const vaultItemById = (id: string) => vault.value.flatMap((g) => g.items).find((i) => i.id === id)

  /** 新建密钥库分组；apiLive 时经后端创建。 */
  async function addVaultGroup(name: string): Promise<VaultGroup> {
    if (apiLive.value) {
      try {
        const g = await apiCreateVaultGroup(name)
        vault.value.push(g)
        return g
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const g: VaultGroup = { id: 'vg' + Date.now(), group: name, items: [] }
    vault.value.push(g)
    return g
  }

  /** 重命名密钥库分组。 */
  function renameVaultGroup(id: string, name: string) {
    const g = vault.value.find((x) => x.id === id)
    if (g) g.group = name
    if (apiLive.value) apiRenameVaultGroup(id, name).catch(() => {})
  }

  /** 删除密钥库分组。 */
  async function deleteVaultGroup(id: string) {
    vault.value = vault.value.filter((g) => g.id !== id)
    if (apiLive.value) {
      try {
        await apiDeleteVaultGroup(id)
      } catch {
        /* 后端删除失败时不回滚 */
      }
    }
  }

  /** 保存用户拖拽后的密钥库分组顺序。 */
  async function reorderVaultGroups(ids: string[]) {
    const byId = new Map(vault.value.map((group) => [group.id, group]))
    vault.value = ids.map((id) => byId.get(id)).filter((group): group is VaultGroup => !!group)
    if (apiLive.value) await apiReorderVaultGroups(ids)
  }

  /** 新建密钥库条目；apiLive 时经后端创建，密码在传输与落库前均按需加密（服务端处理）。 */
  async function addVaultItem(payload: {
    groupId: string; name: string; icon?: string; user?: string; site?: string; note?: string; secret?: string
  }): Promise<VaultItem | null> {
    const g = vault.value.find((x) => x.id === payload.groupId)
    if (!g) return null
    if (apiLive.value) {
      try {
        const item = await apiCreateVaultItem(payload)
        g.items.push(item)
        return item
      } catch {
        return null
      }
    }
    const item: VaultItem = {
      id: 'vi' + Date.now(), name: payload.name, icon: payload.icon || 'ri-key-2-line',
      user: payload.user || '', site: payload.site || '', note: payload.note || '',
      updated: '刚刚', hasSecret: !!payload.secret,
    }
    g.items.push(item)
    return item
  }

  /** 更新密钥库条目。 */
  async function updateVaultItem(id: string, payload: {
    name?: string; icon?: string; user?: string; site?: string; note?: string; secret?: string; groupId?: string
  }) {
    const item = vaultItemById(id)
    if (!item) return
    Object.assign(item, payload, { updated: '刚刚', hasSecret: payload.secret ? true : item.hasSecret })
    if (apiLive.value) {
      try {
        const updated = await apiPatchVaultItem(id, payload)
        Object.assign(item, updated)
      } catch {
        /* 保留本地乐观更新 */
      }
    }
  }

  /** 删除密钥库条目。 */
  async function deleteVaultItem(id: string) {
    for (const g of vault.value) g.items = g.items.filter((i) => i.id !== id)
    if (apiLive.value) {
      try {
        await apiDeleteVaultItem(id)
      } catch {
        /* 后端删除失败时不回滚 */
      }
    }
  }

  /** 解密并返回一次密码明文（仅 apiLive 时可用；离线演示态没有真实密码可查看）。 */
  async function revealVaultItem(id: string): Promise<string | null> {
    if (!apiLive.value) return null
    try {
      const res = await apiRevealVaultItem(id)
      return res.secret
    } catch {
      return null
    }
  }

  return {
    today, areas, projects, tasks, kbs, docs, autos, files, imgs, vault,
    schedule, suggestions, goals, myGroups, apiLive, hydrate,
    areaById, projectById, taskById, tasksByProject, kbById, docById, areaColor,
    docEdits, setDocEdit, docOf, docPending, saveState, markSaving, markSaved,
    applyRemoteDoc, addDoc, addNote, notesKb,
    addGroup, renameGroup, addFolder, renameFolder, deleteFolder, deleteDoc,
    addKb, updateKb, deleteKb, trash, refreshTrash, restoreTrash, purgeTrash, refreshKnowledge,
    patchTask, toggleComplete, toggleStep, reorderTask, addTask, addProject,
    taskSaveState, taskSavedAt, flushTask, addTaskComment, updateTaskComment, deleteTaskComment, deleteTask,
    subById, subKey, addSub, updateSub, removeSub, addSubImage, removeSubImage,
    uploadImage, refreshImages, deleteImage, MAX_IMAGE_BYTES,
    uploadFile, refreshFiles, deleteFile, MAX_FILE_BYTES,
    updateProject, deleteProject, ensureProjectKb, syncFromServer, applyAiRunStatuses,
    taskGroupsOf, setTaskGroups, moveTaskToGroup,
    jobById, createJob, updateJob, deleteJob, runJob, pauseJob, resumeJob, reauthJob,
    vaultItemById, addVaultGroup, renameVaultGroup, deleteVaultGroup, reorderVaultGroups,
    addVaultItem, updateVaultItem, deleteVaultItem, revealVaultItem,
  }
})
