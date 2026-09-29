import { reactive, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useSessionStore } from './session'
import type { Area, Project } from '@/types'

export const PROJECT_LIST_LAYOUT_PREFERENCE = 'project-layout-v1:overview'

export type ProjectListSort = '按截止日期' | '按进度' | '按名称' | '手动排序'
export type ProjectListGrouping = '按分组' | '不分组'

export interface ProjectListGroup {
  id: string
  label: string
  color: string
  /** 来自人生领域的默认分组；改名只影响当前成员的项目列表布局。 */
  areaId?: string
  projectIds: string[]
}

export interface ProjectListLayoutValue {
  version: 1
  groups: ProjectListGroup[]
  projectOrder: string[]
  collapsedGroupIds: string[]
  grouping: ProjectListGrouping
  sort: ProjectListSort
}

interface ProjectListLayoutState extends ProjectListLayoutValue {
  loaded: boolean
  loading: boolean
  saving: boolean
  dirty: boolean
  error: string
  revision: number
}

const GROUP_COLORS = ['#4f63df', '#c68d25', '#bd5d99', '#2a9d8f', '#5789c8']
let groupIdSequence = 0
const emptyLayout = (): ProjectListLayoutState => ({
  version: 1,
  groups: [],
  projectOrder: [],
  collapsedGroupIds: [],
  grouping: '按分组',
  sort: '按截止日期',
  loaded: false,
  loading: false,
  saving: false,
  dirty: false,
  error: '',
  revision: 0,
})

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function stringIds(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return [...new Set(value.filter((id): id is string => typeof id === 'string' && id.length > 0 && id.length <= 128))]
}

function safeColor(value: unknown, fallback: string): string {
  return typeof value === 'string' && /^#[\da-f]{6}$/i.test(value) ? value : fallback
}

function defaultLayout(areas: Area[], projects: Project[]): ProjectListLayoutValue {
  const groups: ProjectListGroup[] = areas.map((area, index) => ({
    id: area.id,
    areaId: area.id,
    label: area.name,
    color: safeColor(area.color, GROUP_COLORS[index % GROUP_COLORS.length]!),
    projectIds: [],
  }))
  if (!groups.length) groups.push({ id: 'project-default', label: '未分组', color: GROUP_COLORS[0]!, projectIds: [] })
  for (const project of projects) {
    const group = groups.find((item) => item.areaId === project.area) ?? groups[0]!
    group.projectIds.push(project.id)
  }
  return {
    version: 1,
    groups,
    projectOrder: projects.map((project) => project.id),
    collapsedGroupIds: [],
    grouping: '按分组',
    sort: '按截止日期',
  }
}

function parseLayout(raw: string): ProjectListLayoutValue {
  const value: unknown = JSON.parse(raw)
  if (!isRecord(value) || value.version !== 1 || !Array.isArray(value.groups) || !Array.isArray(value.projectOrder)) {
    throw new Error('项目分组设置格式异常')
  }
  const seenGroups = new Set<string>()
  const groups = value.groups.flatMap((item, index): ProjectListGroup[] => {
    if (!isRecord(item) || typeof item.id !== 'string' || typeof item.label !== 'string') return []
    const id = item.id.trim()
    const label = item.label.trim().slice(0, 40)
    if (!id || !label || seenGroups.has(id)) return []
    seenGroups.add(id)
    const areaId = typeof item.areaId === 'string' && item.areaId.length <= 128 ? item.areaId : undefined
    return [{
      id,
      label,
      color: safeColor(item.color, GROUP_COLORS[index % GROUP_COLORS.length]!),
      ...(areaId ? { areaId } : {}),
      projectIds: stringIds(item.projectIds),
    }]
  })
  return {
    version: 1,
    groups,
    projectOrder: stringIds(value.projectOrder),
    collapsedGroupIds: stringIds(value.collapsedGroupIds),
    grouping: value.grouping === '不分组' ? '不分组' : '按分组',
    sort: ['按进度', '按名称', '手动排序'].includes(String(value.sort)) ? value.sort as ProjectListSort : '按截止日期',
  }
}

function normalizedLayout(value: ProjectListLayoutValue, areas: Area[], projects: Project[]): ProjectListLayoutValue {
  const knownProjectIds = new Set(projects.map((project) => project.id))
  const result: ProjectListLayoutValue = {
    ...value,
    groups: value.groups.map((group) => ({ ...group, projectIds: [...group.projectIds] })),
    projectOrder: [...value.projectOrder],
    collapsedGroupIds: [...value.collapsedGroupIds],
  }
  for (const area of areas) {
    if (result.groups.some((group) => group.areaId === area.id)) continue
    result.groups.push({
      id: area.id,
      areaId: area.id,
      label: area.name,
      color: safeColor(area.color, GROUP_COLORS[result.groups.length % GROUP_COLORS.length]!),
      projectIds: [],
    })
  }
  if (!result.groups.length) result.groups.push({ id: 'project-default', label: '未分组', color: GROUP_COLORS[0]!, projectIds: [] })

  const assigned = new Set<string>()
  for (const group of result.groups) {
    group.projectIds = group.projectIds.filter((id) => {
      if (!knownProjectIds.has(id) || assigned.has(id)) return false
      assigned.add(id)
      return true
    })
  }
  for (const project of projects) {
    if (assigned.has(project.id)) continue
    const group = result.groups.find((item) => item.areaId === project.area) ?? result.groups[0]!
    group.projectIds.push(project.id)
    assigned.add(project.id)
  }
  result.projectOrder = result.projectOrder.filter((id) => knownProjectIds.has(id))
  const ordered = new Set(result.projectOrder)
  result.projectOrder.push(...projects.map((project) => project.id).filter((id) => !ordered.has(id)))
  result.collapsedGroupIds = result.collapsedGroupIds.filter((id) => result.groups.some((group) => group.id === id))
  return result
}

function persistedValue(state: ProjectListLayoutState): ProjectListLayoutValue {
  return {
    version: 1,
    groups: state.groups.map((group) => ({ ...group, projectIds: [...group.projectIds] })),
    projectOrder: [...state.projectOrder],
    collapsedGroupIds: [...state.collapsedGroupIds],
    grouping: state.grouping,
    sort: state.sort,
  }
}

/** 项目列表分组、归属与顺序是成员级布局偏好，不改共享项目的领域字段。 */
export const useProjectListLayoutStore = defineStore('project-list-layout', () => {
  const session = useSessionStore()
  const state = reactive<ProjectListLayoutState>(emptyLayout())
  let generation = 0
  let pendingCreateGroupId: string | null = null

  watch(() => session.userId, () => {
    generation++
    Object.assign(state, emptyLayout())
    pendingCreateGroupId = null
  }, { flush: 'sync' })

  const draftKey = (memberId: string) => `wao-project-layout-v1:${memberId}`

  async function load(areas: Area[], projects: Project[], force = false) {
    if (state.loading || state.saving || (!force && state.loaded)) return
    const memberId = session.userId
    if (!memberId) {
      state.error = '登录身份暂未就绪，请重新加载项目列表'
      return
    }
    const version = generation
    state.loading = true
    state.error = ''
    Object.assign(state, defaultLayout(areas, projects), { loading: true, loaded: false, saving: false, dirty: false, error: '', revision: state.revision })
    try {
      const raw = await fetchPreference(PROJECT_LIST_LAYOUT_PREFERENCE)
      if (version !== generation) return
      const draft = localStorage.getItem(draftKey(memberId))
      const source = draft ? parseLayout(draft) : raw ? parseLayout(raw) : defaultLayout(areas, projects)
      const restored = normalizedLayout(source, areas, projects)
      state.groups = restored.groups
      state.projectOrder = restored.projectOrder
      state.collapsedGroupIds = restored.collapsedGroupIds
      state.grouping = restored.grouping
      state.sort = restored.sort
      state.loaded = true
      state.dirty = !!draft
      state.error = ''
      if (state.dirty) await save()
    } catch (error) {
      if (version === generation) state.error = error instanceof Error ? error.message : '项目分组设置读取失败'
    } finally {
      if (version === generation) state.loading = false
    }
  }

  function sortProjects(projects: Project[]): Project[] {
    const ranks = new Map(state.projectOrder.map((id, index) => [id, index]))
    return [...projects].sort((a, b) => (ranks.get(a.id) ?? Number.MAX_SAFE_INTEGER) - (ranks.get(b.id) ?? Number.MAX_SAFE_INTEGER))
  }

  function persistDraft(memberId: string): boolean {
    try {
      localStorage.setItem(draftKey(memberId), JSON.stringify(persistedValue(state)))
      return true
    } catch {
      state.error = '浏览器未能保存本机草稿；请保持页面打开并重试保存'
      return false
    }
  }

  async function save() {
    const memberId = session.userId
    if (!memberId || !state.loaded || state.saving) return
    const version = generation
    state.saving = true
    state.error = ''
    try {
      while (state.dirty && version === generation) {
        const revision = state.revision
        await savePreference(PROJECT_LIST_LAYOUT_PREFERENCE, JSON.stringify(persistedValue(state)))
        if (version !== generation) return
        if (revision === state.revision) {
          state.dirty = false
          try { localStorage.removeItem(draftKey(memberId)) } catch { /* 已保存到服务端，清理本机草稿失败不影响结果 */ }
        }
      }
    } catch (error) {
      if (version === generation) state.error = error instanceof Error ? error.message : '项目分组设置保存失败'
    } finally {
      if (version === generation) state.saving = false
    }
  }

  function update(change: (value: ProjectListLayoutValue) => void) {
    if (!state.loaded) return false
    const next = persistedValue(state)
    change(next)
    state.groups = next.groups
    state.projectOrder = next.projectOrder
    state.collapsedGroupIds = next.collapsedGroupIds
    state.grouping = next.grouping
    state.sort = next.sort
    state.revision++
    state.dirty = true
    state.error = ''
    const memberId = session.userId
    if (memberId) persistDraft(memberId)
    void save()
    return true
  }

  function setGrouping(grouping: ProjectListGrouping) {
    update((value) => { value.grouping = grouping })
  }

  function setSort(sort: ProjectListSort) {
    update((value) => { value.sort = sort })
  }

  function addGroup(label: string) {
    const value = label.trim().slice(0, 40)
    if (!value || state.groups.some((group) => group.label.toLocaleLowerCase() === value.toLocaleLowerCase())) return false
    return update((layout) => {
      const id = `custom-${globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${groupIdSequence++}`}`
      layout.groups.push({ id, label: value, color: GROUP_COLORS[layout.groups.length % GROUP_COLORS.length]!, projectIds: [] })
      layout.grouping = '按分组'
    })
  }

  function renameGroup(groupId: string, label: string) {
    const value = label.trim().slice(0, 40)
    if (!value || state.groups.some((group) => group.id !== groupId && group.label.toLocaleLowerCase() === value.toLocaleLowerCase())) return false
    return update((layout) => {
      const group = layout.groups.find((item) => item.id === groupId)
      if (group) group.label = value
    })
  }

  function toggleGroup(groupId: string) {
    return update((layout) => {
      layout.collapsedGroupIds = layout.collapsedGroupIds.includes(groupId)
        ? layout.collapsedGroupIds.filter((id) => id !== groupId)
        : [...layout.collapsedGroupIds, groupId]
    })
  }

  function reorderGroup(sourceId: string, targetId: string, after = false) {
    if (sourceId === targetId || !state.groups.some((group) => group.id === sourceId) || !state.groups.some((group) => group.id === targetId)) return false
    return update((layout) => {
      const from = layout.groups.findIndex((group) => group.id === sourceId)
      const target = layout.groups.findIndex((group) => group.id === targetId)
      if (from < 0 || target < 0) return
      const [source] = layout.groups.splice(from, 1)
      const adjustedTarget = layout.groups.findIndex((group) => group.id === targetId)
      layout.groups.splice(adjustedTarget + (after ? 1 : 0), 0, source!)
      layout.grouping = '按分组'
    })
  }

  function moveProject(projectId: string, targetGroupId: string, beforeProjectId?: string, after = false) {
    if (!state.groups.some((group) => group.id === targetGroupId)) return false
    return update((layout) => {
      for (const group of layout.groups) group.projectIds = group.projectIds.filter((id) => id !== projectId)
      const target = layout.groups.find((group) => group.id === targetGroupId)!
      const targetIndex = beforeProjectId ? target.projectIds.indexOf(beforeProjectId) : -1
      target.projectIds.splice(targetIndex < 0 ? target.projectIds.length : targetIndex + (after ? 1 : 0), 0, projectId)

      layout.projectOrder = layout.projectOrder.filter((id) => id !== projectId)
      const orderIndex = beforeProjectId ? layout.projectOrder.indexOf(beforeProjectId) : -1
      layout.projectOrder.splice(orderIndex < 0 ? layout.projectOrder.length : orderIndex + (after ? 1 : 0), 0, projectId)
      layout.grouping = '按分组'
      layout.sort = '手动排序'
    })
  }

  function reorderProjectInList(projectId: string, beforeProjectId: string, after = false) {
    if (projectId === beforeProjectId || !state.projectOrder.includes(projectId) || !state.projectOrder.includes(beforeProjectId)) return false
    return update((layout) => {
      layout.projectOrder = layout.projectOrder.filter((id) => id !== projectId)
      const targetIndex = layout.projectOrder.indexOf(beforeProjectId)
      layout.projectOrder.splice(targetIndex + (after ? 1 : 0), 0, projectId)
      const ranks = new Map(layout.projectOrder.map((id, index) => [id, index]))
      for (const group of layout.groups) {
        group.projectIds.sort((a, b) => (ranks.get(a) ?? Number.MAX_SAFE_INTEGER) - (ranks.get(b) ?? Number.MAX_SAFE_INTEGER))
      }
      layout.sort = '手动排序'
    })
  }

  function reconcile(areas: Area[], projects: Project[]) {
    if (!state.loaded) return
    const before = JSON.stringify(persistedValue(state))
    const next = normalizedLayout(persistedValue(state), areas, projects)
    if (before === JSON.stringify(next)) return
    update((layout) => Object.assign(layout, next))
  }

  function setCreateGroupTarget(groupId: string | null) {
    pendingCreateGroupId = groupId
  }

  async function consumeCreateGroupTarget(projectId: string) {
    const groupId = pendingCreateGroupId
    pendingCreateGroupId = null
    if (groupId) await moveProject(projectId, groupId)
  }

  function clearCreateGroupTarget() {
    pendingCreateGroupId = null
  }

  async function retry(areas: Area[], projects: Project[]) {
    if (!state.loaded) await load(areas, projects, true)
    else if (state.dirty) await save()
  }

  return {
    state,
    load,
    sortProjects,
    setGrouping,
    setSort,
    addGroup,
    renameGroup,
    toggleGroup,
    reorderGroup,
    moveProject,
    reorderProjectInList,
    reconcile,
    setCreateGroupTarget,
    consumeCreateGroupTarget,
    clearCreateGroupTarget,
    retry,
  }
})
