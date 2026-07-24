import { reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import * as F from '@/data/fixtures'
import {
  apiCreateProject,
  apiCreateTask,
  apiDeleteProject,
  apiPatchProject,
  apiPutTask,
  fetchAreas,
  fetchDocs,
  fetchEvents,
  fetchFiles,
  fetchGoals,
  fetchImages,
  fetchJobs,
  fetchKbs,
  fetchProjects,
  fetchSchedule,
  fetchSuggestions,
  fetchTasks,
  fetchVault,
} from '@/api/board'
import { blocksToText, textToBlocks } from '@/utils/doc'
import type { Doc, DocBlock, KnowledgeBase, Project, Task } from '@/types'

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
    const [a, p, t, kb, dc, jb, fl, im, va, ev, sc, sg, gl] = await Promise.all([
      settle(fetchAreas()), settle(fetchProjects()), settle(fetchTasks()), settle(fetchKbs()),
      settle(fetchDocs()), settle(fetchJobs()), settle(fetchFiles()), settle(fetchImages()),
      settle(fetchVault()), settle(fetchEvents()), settle(fetchSchedule()),
      settle(fetchSuggestions()), settle(fetchGoals()),
    ])
    if (a?.length) areas.value = a
    if (p?.length) projects.value = p
    if (t?.length) tasks.value = t
    if (kb?.length) kbs.value = kb
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
      if (!remoteIds.has(local[i]!.id)) local.splice(i, 1)
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
  /** 合并一次文档编辑（title / text）并持久化。 */
  function setDocEdit(id: string, patch: DocEdit) {
    docEdits.value = { ...docEdits.value, [id]: { ...docEdits.value[id], ...patch } }
    localStorage.setItem(DOC_KEY, JSON.stringify(docEdits.value))
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
  /** 新建自建分组，返回 id。 */
  function addGroup(): string {
    const id = 'g' + Date.now()
    myGroups.value.push({ id, name: '新建分组' })
    return id
  }
  /** 给知识库新增一个文件夹，返回文件夹 id。 */
  function addFolder(kbId: string): string | null {
    const kb = kbById(kbId)
    if (!kb) return null
    const id = 'f' + Date.now()
    kb.folders.push({ id, name: '新建文件夹', docs: [] })
    return id
  }

  /** 在知识库某文件夹新建空文档，返回新 id。 */
  function addDoc(kbId: string, folderId: string): string | null {
    const kb = kbById(kbId)
    const folder = kb?.folders.find((f) => f.id === folderId)
    if (!kb || !folder) return null
    const id = 'd' + Date.now()
    docs[id] = {
      id, title: '未命名文档', author: 'wg', created: '刚刚', updated: '刚刚',
      links: kb.project ? [{ type: 'project', id: kb.project }] : [],
      blocks: [], backlinks: [],
    }
    folder.docs.push(id)
    return id
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

  /** 新建任务；apiLive 时经后端创建（用服务端返回 id），否则本地乐观创建。 */
  async function addTask(payload: NewTaskPayload): Promise<Task> {
    const { desc = '', images = [], ...core } = payload
    if (apiLive.value) {
      try {
        const t = await apiCreateTask({ ...core, desc, images })
        tasks.value.unshift(t)
        return t
      } catch {
        /* 后端异常时回落本地 */
      }
    }
    const t: Task = {
      id: 't' + Date.now(), status: '待办', parts: [], desc, steps: [], subs: [], files: [],
      docs: [], linkTasks: [], comments: [], custom: [], images,
      activity: [{ icon: 'ri-add-line', color: '#9A9DA9', text: '你创建了任务', time: '刚刚' }],
      ...core,
    }
    tasks.value.unshift(t)
    return t
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

  /** 完成 / 重新打开一个任务（列表勾选用）。 */
  function toggleComplete(id: string) {
    const t = taskById(id)
    if (!t) return
    patchTask(id, { status: t.status === '已完成' ? '待办' : '已完成' })
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

  /** 勾选步骤 / 子任务。 */
  function toggleStep(id: string, idx: number, kind: 'steps' | 'subs') {
    const t = taskById(id)
    const item = t?.[kind][idx]
    if (item) {
      item.done = !item.done
      persistTask(id)
    }
  }

  return {
    today, areas, projects, tasks, kbs, docs, autos, files, imgs, vault,
    calEvents, schedule, suggestions, goals, myGroups, apiLive, hydrate,
    areaById, projectById, taskById, tasksByProject, kbById, docById, areaColor,
    docEdits, setDocEdit, docOf, addDoc, addGroup, addFolder,
    patchTask, toggleComplete, toggleStep, reorderTask, addTask, addProject,
    updateProject, deleteProject, ensureProjectKb, syncFromServer,
  }
})
