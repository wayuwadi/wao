import { reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import * as F from '@/data/fixtures'
import {
  apiCreateDoc,
  apiCreateFolder,
  apiCreateJob,
  apiCreateKb,
  apiCreateKbGroup,
  apiCreateProject,
  apiCreateTask,
  apiCreateVaultGroup,
  apiCreateVaultItem,
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
  apiRenameKbGroup,
  apiRenameVaultGroup,
  apiResumeJob,
  apiRevealVaultItem,
  apiRunJob,
  fetchAreas,
  fetchDocs,
  fetchEvents,
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
  fetchVault,
  type CreateJobPayload,
  type CreateKbPayload,
  type PatchJobPayload,
  type PatchKbPayload,
} from '@/api/board'
import { blocksToText, textToBlocks } from '@/utils/doc'
import { useUiStore } from '@/stores/ui'
import type {
  AutomationJob, Doc, DocBlock, ImageRef, KnowledgeBase, Project, Task, TaskStep, VaultGroup, VaultItem,
} from '@/types'

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
const clone = <T>(v: T): T => structuredClone(v)

/**
 * 本地 id 生成器。
 *
 * <p>纯 `Date.now()` 在同一毫秒内会撞号 —— 连续新增几个子任务就可能拿到同一个 id，
 * 之后按 id 定位会全部命中第一条。加一个单调递增的序号把这种情况排除掉。
 */
let idSeq = 0
const uid = (prefix: string) => `${prefix}${Date.now()}${(idSeq++).toString(36)}`

export const useDataStore = defineStore('data', () => {
  const today = F.TODAY
  const areas = ref(clone(F.AREAS))
  const projects = ref(clone(F.PROJECTS))
  const tasks = ref(clone(F.TASKS))
  const kbs = ref(clone(F.KBS))
  const docs = reactive<Record<string, Doc>>(clone(F.DOCS))
  const autos = ref(clone(F.AUTOS))
  const files = ref(clone(F.FILES))
  const imgs = ref(clone(F.IMGS))
  const vault = ref(clone(F.VAULT))
  const calEvents = ref(clone(F.CALDAV))
  const schedule = ref(clone(F.SCHED))
  const suggestions = ref(clone(F.SUGG))
  const goals = ref(clone(F.GOALS))
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
    const [a, p, t, kb, kg, dc, jb, fl, im, va, ev, sc, sg, gl] = await Promise.all([
      settle(fetchAreas()), settle(fetchProjects()), settle(fetchTasks()), settle(fetchKbs()),
      settle(fetchKbGroups()), settle(fetchDocs()), settle(fetchJobs()), settle(fetchFiles()),
      settle(fetchImages()), settle(fetchVault()), settle(fetchEvents()), settle(fetchSchedule()),
      settle(fetchSuggestions()), settle(fetchGoals()),
    ])
    if (a?.length) areas.value = a
    if (p?.length) projects.value = p
    if (t?.length) tasks.value = t
    if (kb?.length) kbs.value = kb
    if (kg?.length) myGroups.value = kg
    if (dc?.length) dc.forEach((d) => (docs[d.id] = d))
    if (jb?.length) autos.value = jb
    if (fl?.length) files.value = fl
    if (im?.length) imgs.value = im
    if (va?.length) vault.value = va
    if (ev?.length) calEvents.value = ev
    if (sc?.length) schedule.value = sc
    if (sg?.length) suggestions.value = sg
    if (gl?.length) goals.value = gl
    // 核心读路径任一可达即认为后端在线；写路径（POST/PUT）随之启用。
    apiLive.value = a !== null && p !== null && t !== null
    // 每个项目都应有一个默认知识库（后端就绪前为前端内存态），刷新后由此保持。
    projects.value.forEach((proj) => ensureProjectKb(proj))
    if (apiLive.value) startSync()
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
      if (Array.isArray(t)) mergeList(tasks.value, t)
    } catch {
      /* 网络抖动时静默跳过，下个周期重试 */
    } finally {
      syncing = false
    }
  }
  /** 用服务端列表就地合并本地数组：更新已存在项、追加新项、移除已删除项，保持数组引用稳定。 */
  function mergeList<T extends { id: string }>(local: T[], remote: T[]) {
    const remoteIds = new Set(remote.map((r) => r.id))
    for (const r of remote) {
      const existing = local.find((l) => l.id === r.id)
      if (existing) Object.assign(existing, r)
      else local.push(r)
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

  // ---- getters ----
  const areaById = (id: string) => areas.value.find((a) => a.id === id)
  const projectById = (id: string) => projects.value.find((p) => p.id === id)
  const taskById = (id: string) => tasks.value.find((t) => t.id === id)
  const tasksByProject = (pid: string) => tasks.value.filter((t) => t.project === pid)
  const kbById = (id: string) => kbs.value.find((k) => k.id === id)
  const docById = (id: string): Doc | undefined => docs[id]

  /** 领域色（找不到用弱灰）。 */
  const areaColor = (id: string) => areaById(id)?.color ?? '#9A9DA9'

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
  /** 合并一次文档编辑（title / text）并持久化；apiLive 时去抖写透后端。 */
  function setDocEdit(id: string, patch: DocEdit) {
    docEdits.value = { ...docEdits.value, [id]: { ...docEdits.value[id], ...patch } }
    localStorage.setItem(DOC_KEY, JSON.stringify(docEdits.value))
    if (!apiLive.value) return
    clearTimeout(docPersistTimers[id])
    docPersistTimers[id] = setTimeout(() => {
      const resolved = docOf(id)
      apiPatchDoc(id, { title: resolved.title, blocks: resolved.blocks }).catch(() => {})
    }, 600)
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

  /** 在知识库某文件夹新建空文档，返回新 id；apiLive 时经后端创建（新文档即关联到该知识库 / 文件夹）。 */
  async function addDoc(kbId: string, folderId: string): Promise<string | null> {
    const kb = kbById(kbId)
    const folder = kb?.folders.find((f) => f.id === folderId)
    if (!kb || !folder) return null
    if (apiLive.value) {
      try {
        const d = await apiCreateDoc({ kbId, folderId, title: '未命名文档', author: 'wg' })
        docs[d.id] = d
        folder.docs.push(d.id)
        return d.id
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const id = 'd' + Date.now()
    docs[id] = {
      id, title: '未命名文档', author: 'wg', created: '刚刚', updated: '刚刚',
      links: kb.project ? [{ type: 'project', id: kb.project }] : [],
      blocks: [], backlinks: [],
    }
    folder.docs.push(id)
    return id
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

  /** 删除知识库（连同其文件夹与文档）；apiLive 时写透后端。 */
  async function deleteKb(id: string) {
    const kb = kbById(id)
    kbs.value = kbs.value.filter((k) => k.id !== id)
    kb?.folders.forEach((f) => f.docs.forEach((docId) => delete docs[docId]))
    if (apiLive.value) {
      try {
        await apiDeleteKb(id)
      } catch {
        /* 后端删除失败时不回滚 */
      }
    }
  }

  // ---- task 变更 ----
  /** 合并字段到任务，可选写入一条活动时间线（对齐原型 patchTask）。 */
  function patchTask(id: string, fields: Partial<Task>, activityText?: string) {
    const t = taskById(id)
    if (!t) return
    Object.assign(t, fields)
    if (activityText) t.activity.unshift({ icon: 'ri-edit-line', color: '#9A9DA9', text: activityText, time: '刚刚' })
    persistTask(id)
  }

  /** 新建任务负载：核心字段 + 可选描述 / 内嵌图片。 */
  type NewTaskPayload = Pick<Task, 'title' | 'project' | 'pr' | 'owner' | 'due'> &
    Pick<Task, 'desc' | 'images'>

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
      activity: [{ icon: 'ri-add-line', color: '#9A9DA9', text: '你创建了任务', time: '刚刚' }],
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
      .catch(() => apiPutTask(local.id, local).catch(() => {}))
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
        activity: [{ icon: 'ri-add-line', color: '#9A9DA9', text: '你创建了项目', time: '刚刚' }],
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

  /** 更新项目字段（名称 / 领域 / 目标 / 图标）；apiLive 时写透后端。 */
  async function updateProject(id: string, patch: { name?: string; area?: string; goal?: string; icon?: string }) {
    const p = projectById(id)
    if (!p) return
    Object.assign(p, patch)
    if (apiLive.value) {
      try {
        await apiPatchProject(id, patch)
      } catch {
        /* 保留本地乐观更新 */
      }
    }
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

  /** 把某任务的当前完整状态写透到后端（fire-and-forget）。 */
  function persistTask(id: string) {
    if (!apiLive.value) return
    const t = taskById(id)
    if (t) apiPutTask(id, t).catch(() => {})
  }

  /** 完成 / 重新打开一个任务（列表勾选用）；两个方向都写进活动时间线。 */
  function toggleComplete(id: string) {
    const t = taskById(id)
    if (!t) return
    const done = t.status !== '已完成'
    patchTask(id, { status: done ? '已完成' : '待办' }, done ? '你完成了任务' : '你重新打开了任务')
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
    logActivity(t, `${item.done ? '你完成了' : '你重新打开了'}${what}「${item.text}」`)
    persistTask(id)
  }

  /** 往任务活动时间线最前面写一条。 */
  function logActivity(t: Task, text: string, icon = 'ri-edit-line') {
    t.activity.unshift({ icon, color: '#9A9DA9', text, time: '刚刚' })
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
   * @param taskId 所属任务 id
   * @param text   子任务标题
   * @return 新子任务的标识，标题为空时返回 null
   */
  function addSub(taskId: string, text: string): string | null {
    const t = taskById(taskId)
    const title = text.trim()
    if (!t || !title) return null
    const id = uid('s')
    t.subs.push({ id, text: title, done: false, desc: '', images: [] })
    logActivity(t, `你添加了子任务「${title}」`)
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
      logActivity(t, `${patch.done ? '你完成了' : '你重新打开了'}子任务「${sub.text}」`)
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
    logActivity(t, `你删除了子任务「${removed!.text}」`, 'ri-delete-bin-line')
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
    j.runs.unshift(['刚刚', result])
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
    calEvents, schedule, suggestions, goals, myGroups, apiLive, hydrate,
    areaById, projectById, taskById, tasksByProject, kbById, docById, areaColor,
    docEdits, setDocEdit, docOf, addDoc, addGroup, renameGroup, addFolder,
    addKb, updateKb, deleteKb,
    patchTask, toggleComplete, toggleStep, reorderTask, addTask, addProject,
    subById, subKey, addSub, updateSub, removeSub, addSubImage, removeSubImage,
    uploadImage, refreshImages, deleteImage, MAX_IMAGE_BYTES,
    updateProject, deleteProject, ensureProjectKb, syncFromServer,
    jobById, createJob, updateJob, deleteJob, runJob, pauseJob, resumeJob, reauthJob,
    vaultItemById, addVaultGroup, renameVaultGroup, deleteVaultGroup,
    addVaultItem, updateVaultItem, deleteVaultItem, revealVaultItem,
  }
})
