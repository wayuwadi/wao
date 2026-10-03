<script setup lang="ts">
import { useTopShortcutsStore, resourceShortcut } from '@/stores/topShortcuts'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRefs, watch } from 'vue'
import { useBrowsingState, validSearch, validPage } from '@/composables/browsingState'
import { useClientPagination } from '@/composables/clientPagination'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useProjectListLayoutStore } from '@/stores/projectListLayout'
import { useDeco } from '@/composables/deco'
import type { Project } from '@/types'
import { projectCompletion } from '@/utils/projectCompletion'
import { captureCompanionTicket, respondWithCompanion } from '@/composables/quietCompanions'

const data = useDataStore()
const ui = useUiStore()
const session = useSessionStore()
const layout = useProjectListLayoutStore()
const route = useRoute()
const shortcuts = useTopShortcutsStore()
const router = useRouter()
const { decoProj } = useDeco()

// 「我的任务」已迁到左侧「个人 · TODO」；老地址平滑跳转，书签不失效。
if (route.query.view === 'mytasks') router.replace('/todo')

// ---------- 项目视图 ----------
const { q, projectPage } = toRefs(useBrowsingState('projects', { q: '', projectPage: 1 }, { q: validSearch, projectPage: validPage }))
const emptySpace = ref<HTMLElement>()
const trulyEmpty = computed(() => data.loadStates.projects === 'loaded' && data.projects.length === 0 && !q.value.trim())
watch([trulyEmpty, emptySpace], ([empty, el]) => {
  if (empty && el) respondWithCompanion('empty', captureCompanionTicket(el))
}, { flush: 'post' })
// 项目状态已下线：项目只看任务推进，不再手工标「进行中 / 有风险」。
const groupBy = computed(() => layout.state.grouping)
const sortBy = computed(() => layout.state.sort)
const openDrop = ref<'group' | 'sort' | null>(null)
const projectGroups = computed(() => layout.state.groups)
const collapsedGroups = computed(() => layout.state.collapsedGroupIds)
const dragProjectId = ref<string | null>(null)
const dragGroupId = ref<string | null>(null)
const groupHoverId = ref<string | null>(null)
const contextMenu = ref<{ x: number; y: number; projectId?: string; groupId?: string } | null>(null)
const contextMenuEl = ref<HTMLElement | null>(null)
const showGroupDialog = ref(false)
const newGroupName = ref('')
const editingGroupId = ref<string | null>(null)
const groupDialogError = ref('')
const notice = ref('')
let suppressProjectClick = false

watch(contextMenu, async (menu) => {
  if (!menu) return
  await nextTick()
  contextMenuEl.value?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
})

watch([() => data.loadStates.areas, () => data.loadStates.projects, () => session.userId], () => {
  if (data.loadStates.areas === 'loaded' && data.loadStates.projects === 'loaded' && session.userId) {
    void layout.load(data.areas, data.projects)
  }
}, { immediate: true })
watch([() => data.areas.map((area) => `${area.id}:${area.name}:${area.color}`).join('|'),
  () => data.projects.map((project) => `${project.id}:${project.area}`).join('|'), () => layout.state.loaded], () => {
  if (layout.state.loaded) layout.reconcile(data.areas, data.projects)
})

const filteredProjects = computed(() => {
  const kw = q.value.trim().toLowerCase()
  const list = data.projects.filter(
    (p) => !kw || p.name.toLowerCase().includes(kw) || p.goal.toLowerCase().includes(kw),
  )
  const sorts: Record<string, (a: Project, b: Project) => number> = {
    按截止日期: (a, b) => (a.due || '9').localeCompare(b.due || '9'),
    按进度: (a, b) => (projectCompletion(data.tasksByProject(b.id)).completionRate ?? -1)
      - (projectCompletion(data.tasksByProject(a.id)).completionRate ?? -1),
    按名称: (a, b) => a.name.localeCompare(b.name, 'zh'),
  }
  if (sortBy.value === '手动排序') {
    return layout.sortProjects(list)
  }
  return list.slice().sort(sorts[sortBy.value]!)
})
const { pageCount: projectPageCount, visible: visibleProjects } = useClientPagination(filteredProjects, [q, sortBy], 10, { page: projectPage, ready: () => data.loadStates.projects === 'loaded' && layout.state.loaded })

const projGroups = computed(() => {
  const list = visibleProjects.value
  let groups: { id: string; label: string; dot: string; items: Project[]; hide?: boolean }[]
  if (groupBy.value === '按分组') {
    groups = projectGroups.value.map((group) => ({
      id: group.id, label: group.label, dot: group.color,
      items: list.filter((project) => group.projectIds.includes(project.id)).sort((a, b) => {
        if (sortBy.value !== '手动排序') return 0
        return group.projectIds.indexOf(a.id) - group.projectIds.indexOf(b.id)
      }),
    }))
  } else {
    groups = [{ id: 'all', label: '全部项目', dot: '#9A9DA9', items: list, hide: true }]
  }
  return groups.map((g) => ({
    id: g.id, label: g.label, dot: g.dot,
    count: g.hide ? filteredProjects.value.length : projectGroups.value.find((group) => group.id === g.id)?.projectIds.filter((id) => filteredProjects.value.some((project) => project.id === id)).length ?? g.items.length,
    showHead: !g.hide,
    items: g.items.map(decoProj),
  }))
})

// ---------- 交互 ----------
function openProject(id: string) {
  router.push(`/projects/${id}`)
}
function startCreateProject(groupId?: string) {
  contextMenu.value = null
  layout.setCreateGroupTarget(groupId ?? null)
  ui.openModal('project')
}
function refreshProjectList() {
  void data.hydrate()
}
function onProjectCardClick(id: string) {
  if (suppressProjectClick) { suppressProjectClick = false; return }
  openProject(id)
}

function onProjectContextMenu(event: MouseEvent, projectId: string) {
  const groupId = projectGroups.value.find((group) => group.projectIds.includes(projectId))?.id
  onContextMenu(event, projectId, groupId)
}

function onContextMenu(event: MouseEvent, projectId?: string, groupId?: string) {
  event.preventDefault()
  const width = 238
  const height = projectId ? Math.min(430, 230 + 31 * projectGroups.value.length) : groupId ? 220 : 170
  contextMenu.value = {
    x: Math.max(10, Math.min(event.clientX, window.innerWidth - width - 10)),
    y: Math.max(10, Math.min(event.clientY, window.innerHeight - height - 10)),
    projectId, groupId,
  }
}

function saveGroup() {
  const label = newGroupName.value.trim()
  if (!label) return
  if (projectGroups.value.some((group) => group.id !== editingGroupId.value && group.label.toLocaleLowerCase() === label.toLocaleLowerCase())) {
    groupDialogError.value = '已有同名分组，请换一个名称。'
    return
  }
  const saved = editingGroupId.value
    ? layout.renameGroup(editingGroupId.value, label)
    : layout.addGroup(label)
  if (!saved) {
    groupDialogError.value = '分组没有保存，请检查名称后重试。'
    return
  }
  if (editingGroupId.value) {
    notice.value = `分组已重命名为「${label}」。`
  } else {
    notice.value = `已添加分组「${label}」。`
  }
  editingGroupId.value = null
  newGroupName.value = ''
  showGroupDialog.value = false
  groupDialogError.value = ''
}

function moveProjectToGroup(projectId: string, groupId: string) {
  const target = projectGroups.value.find((group) => group.id === groupId)
  if (!target) return
  const moved = layout.moveProject(projectId, groupId)
  if (moved) notice.value = `「${data.projects.find((item) => item.id === projectId)?.name ?? '项目'}」已移至「${target.label}」。`
}

function startGroupDialog() {
  editingGroupId.value = null
  newGroupName.value = ''
  groupDialogError.value = ''
  showGroupDialog.value = true
}
function renameGroup(groupId: string) {
  const group = projectGroups.value.find((item) => item.id === groupId)
  if (!group) return
  editingGroupId.value = groupId
  newGroupName.value = group.label
  groupDialogError.value = ''
  showGroupDialog.value = true
  closeContextMenu()
}
async function copyProjectLink(projectId: string) {
  try {
    await navigator.clipboard.writeText(new URL(`/projects/${projectId}`, location.origin).toString())
    notice.value = '项目链接已复制。'
  } catch { notice.value = '浏览器未允许复制链接，请从地址栏复制项目地址。' }
  closeContextMenu()
}

function reorderProject(dragId: string, overId: string, groupId: string, after: boolean) {
  if (!dragId || !layout.state.loaded || dragId === overId) return
  suppressProjectClick = true
  window.setTimeout(() => { suppressProjectClick = false }, 500)
  if (groupId === 'all') layout.reorderProjectInList(dragId, overId, after)
  else layout.moveProject(dragId, groupId, overId, after)
}

function dropProjectOnGroup(groupId: string) {
  if (!dragProjectId.value || !layout.state.loaded) return
  suppressProjectClick = true
  window.setTimeout(() => { suppressProjectClick = false }, 500)
  moveProjectToGroup(dragProjectId.value, groupId)
  dragProjectId.value = null
  groupHoverId.value = null
}

function reorderGroup(targetId: string, after = false) {
  const sourceId = dragGroupId.value
  if (!sourceId || !layout.state.loaded || sourceId === targetId) return
  layout.reorderGroup(sourceId, targetId, after)
}

function startProjectDrag(event: DragEvent, project: Project) {
  if (!layout.state.loaded) { event.preventDefault(); return }
  dragProjectId.value = project.id
  dragGroupId.value = null
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', `project:${project.id}`)
  }
  shortcuts.start(resourceShortcut('project', project.id, project.name), event)
}

function startGroupDrag(event: DragEvent, groupId: string) {
  if (!layout.state.loaded) { event.preventDefault(); return }
  dragGroupId.value = groupId
  dragProjectId.value = null
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', `project-group:${groupId}`)
  }
}

function dropOnGroup(event: DragEvent, groupId: string) {
  if (dragProjectId.value) dropProjectOnGroup(groupId)
  else if (dragGroupId.value) {
    const current = event.currentTarget as HTMLElement
    const target = current.closest('.group-section') ?? current
    const rect = target.getBoundingClientRect()
    reorderGroup(groupId, event.clientY > rect.top + rect.height / 2)
  }
}

function dropOnProject(event: DragEvent, overId: string, groupId: string) {
  if (dragProjectId.value) {
    const target = event.currentTarget as HTMLElement
    const rect = target.getBoundingClientRect()
    reorderProject(dragProjectId.value, overId, groupId, event.clientY > rect.top + rect.height / 2)
  } else if (dragGroupId.value) {
    dropOnGroup(event, groupId)
  }
}

function toggleGroup(groupId: string) {
  layout.toggleGroup(groupId)
}

function closeContextMenu() { contextMenu.value = null }
function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { closeContextMenu(); showGroupDialog.value = false }
}
onMounted(() => { document.addEventListener('pointerdown', closeContextMenu); document.addEventListener('keydown', onGlobalKeydown) })
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeContextMenu)
  document.removeEventListener('keydown', onGlobalKeydown)
  layout.clearCreateGroupTarget()
})
</script>

<template>
  <div class="projects">
    <!-- header -->
    <div class="ptop">
      <div class="ptitle">项目</div>
      <span class="ptip">{{ filteredProjects.length }} 个项目 · 软件项目与人生大事都值得正式立项</span>
      <div class="spacer"></div>
      <button class="btn-primary" :disabled="data.loadStates.projects !== 'loaded' || !layout.state.loaded" @click="startCreateProject()">
        <i class="ri-add-line"></i>新建项目
      </button>
    </div>

    <div v-if="data.loadStates.projects === 'loading'" class="load-state" role="status" aria-live="polite">
      <i class="ri-loader-4-line spin"></i>
      <b>正在加载项目…</b>
      <span>项目数据返回前不会展示示例内容。</span>
    </div>
    <div v-else-if="data.loadStates.projects === 'error'" class="load-state error" role="alert">
      <i class="ri-error-warning-line"></i>
      <b>项目加载失败</b>
      <span>{{ data.loadErrors.projects || '暂时无法读取项目，请稍后重试' }}</span>
      <button type="button" @click="data.hydrate()"><i class="ri-refresh-line"></i>重新加载</button>
    </div>

    <!-- 项目视图 -->
    <template v-else>
      <div class="toolbar">
        <div class="spacer"></div>
        <div v-if="openDrop" class="drop-mask" @click="openDrop = null"></div>
        <div class="search">
          <i class="ri-search-line"></i>
          <input v-model="q" placeholder="搜索项目名称或目标…" />
        </div>
        <div class="drop-wrap">
          <button class="tool-btn" :disabled="!layout.state.loaded" @click="openDrop = openDrop === 'group' ? null : 'group'">
            <i class="ri-node-tree"></i>{{ groupBy }}<i class="ri-arrow-down-s-line chev"></i>
          </button>
          <div v-if="openDrop === 'group'" class="drop-menu">
            <button
              v-for="o in (['不分组', '按分组'] as const)"
              :key="o"
              class="drop-item"
              @click="(layout.setGrouping(o), (openDrop = null))"
            >
              <span>{{ o }}</span><i v-if="o === groupBy" class="ri-check-line"></i>
            </button>
          </div>
        </div>
        <div class="drop-wrap">
          <button class="tool-btn" :disabled="!layout.state.loaded" @click="openDrop = openDrop === 'sort' ? null : 'sort'">
            <i class="ri-arrow-up-down-line"></i>{{ sortBy === '按进度' ? '按任务收尾率' : sortBy }}<i class="ri-arrow-down-s-line chev"></i>
          </button>
          <div v-if="openDrop === 'sort'" class="drop-menu">
            <button
              v-for="o in (['按截止日期', '按进度', '按名称', '手动排序'] as const)"
              :key="o"
              class="drop-item"
              @click="(layout.setSort(o), (openDrop = null))"
            >
              <span>{{ o === '按进度' ? '按任务收尾率' : o }}</span><i v-if="o === sortBy" class="ri-check-line"></i>
            </button>
          </div>
        </div>
      </div>

      <div v-if="layout.state.loading || layout.state.saving || layout.state.error || layout.state.dirty" class="project-layout-status" role="status" aria-live="polite">
        <span v-if="layout.state.loading"><i class="ri-loader-4-line spin"></i>正在读取个人分组与排序…</span>
        <span v-else-if="layout.state.saving"><i class="ri-loader-4-line spin"></i>个人布局保存中…</span>
        <span v-else-if="layout.state.error" role="alert"><i class="ri-error-warning-line"></i>{{ layout.state.loaded ? '个人布局保存失败' : '个人布局读取失败' }}：{{ layout.state.error }}</span>
        <span v-else-if="layout.state.dirty"><i class="ri-cloud-off-line"></i>个人布局尚未保存</span>
        <button v-if="layout.state.error || layout.state.dirty" type="button" @click="layout.retry(data.areas, data.projects)">{{ layout.state.loaded ? '重试保存' : '重新读取' }}</button>
      </div>

      <div class="project-canvas" @contextmenu.prevent="onContextMenu($event)">
      <div class="groups">
        <section v-for="g in projGroups" :key="g.id" class="group-section"
          :class="{ 'group-drag-over': groupHoverId === g.id }"
          @dragover.prevent="groupHoverId = g.id"
          @dragleave.self="groupHoverId = null"
          @drop.prevent="dropOnGroup($event, g.id)">
          <div v-if="g.showHead" class="group-head" :draggable="layout.state.loaded"
            @contextmenu.stop="onContextMenu($event, undefined, g.id)"
            @dragstart.stop="startGroupDrag($event, g.id)" @dragend="dragGroupId = null; groupHoverId = null"
            @dragover.prevent @drop.stop.prevent="dropOnGroup($event, g.id)">
            <button class="collapse-group" type="button" :disabled="!layout.state.loaded" :aria-label="collapsedGroups.includes(g.id) ? `展开${g.label}` : `折叠${g.label}`" @click="toggleGroup(g.id)">
              <i :class="collapsedGroups.includes(g.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>
            </button>
            <span class="group-dot" :style="{ background: g.dot }"></span>{{ g.label }}
            <span class="group-count wao-tabular">{{ g.count }}</span>
            <span class="group-reorder-hint"><i class="ri-drag-move-2-line"></i>拖动排序</span>
          </div>
          <div v-if="!collapsedGroups.includes(g.id)" class="card-grid" @dragover.prevent @drop.prevent="dropOnGroup($event, g.id)">
            <div v-for="p in g.items" :key="p.id" class="proj-card" :class="{ dragging: dragProjectId === p.id, 'card-drop-target': dragProjectId && dragProjectId !== p.id }" :draggable="layout.state.loaded"
              @contextmenu.stop="onProjectContextMenu($event, p.id)"
              @dragstart.stop="startProjectDrag($event, p)"
              @dragend="dragProjectId = null; dragGroupId = null; groupHoverId = null; shortcuts.end"
              @dragover.prevent.stop="groupHoverId = g.id"
              @drop.prevent.stop="dropOnProject($event, p.id, g.id)"
              @click="onProjectCardClick(p.id)">
              <div class="pc-top">
                <div class="pc-icon" :style="{ background: p.areaColor }">
                  <i v-if="p.icon" :class="p.icon"></i>
                  <template v-else>{{ p.initialChar }}</template>
                </div>
                <div class="pc-name-wrap">
                  <div class="pc-name">{{ p.name }}</div>
                  <div class="pc-sub">{{ p.areaName }} · 负责人 {{ p.ownerName }}</div>
                </div>
              </div>
              <div class="pc-goal">{{ p.goalShort }}</div>
              <div class="pc-meta">
                <span class="pc-m" :title="'任务实体各计一次；主任务已完成但内嵌子项未全部完成时仍未收尾。'"><i class="ri-checkbox-circle-line"></i>{{ data.taskLoadState === 'loaded' ? p.completionLabel : data.taskLoadState === 'error' ? '任务计数读取失败' : '任务计数加载中…' }}</span>
                <span class="pc-m" :style="{ color: p.dueColor }"><i class="ri-calendar-line"></i>{{ p.dueStr }}</span>
                <span class="spacer"></span>
                <span class="pc-avs">
                  <span v-for="(mv, i) in p.memberAvs" :key="i" class="pc-av" :style="{ background: mv.color }">{{ mv.initial }}</span>
                </span>
              </div>
              <div v-if="data.taskLoadState === 'loaded' && p.completion.subTotal" class="pc-m" title="内嵌清单子项单独计数，不加入任务总数；包括全部层级。">
                内嵌子项未收尾 {{ p.completion.subOpen }}／共 {{ p.completion.subTotal }} 项
              </div>
            </div>
          </div>
        </section>
      <div v-if="filteredProjects.length === 0" class="empty">
          <i class="ri-filter-3-line"></i>
          <div><span ref="emptySpace">{{ trulyEmpty ? '还没有项目，可以从右上角新建第一个' : '没有符合条件的项目——试试清除搜索' }}</span></div>
        </div>
      </div>
      </div>
      <WaoPagination v-if="projectPageCount > 1" v-model:page="projectPage" :page-count="projectPageCount" :total="filteredProjects.length" aria-label="项目查询结果分页" />
    </template>

    <div v-if="contextMenu" ref="contextMenuEl" class="context-menu" role="menu" :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }" @pointerdown.stop @contextmenu.prevent>
      <div class="context-caption">{{ contextMenu.projectId ? '项目操作' : contextMenu.groupId ? '分组操作' : '项目空间' }}</div>
      <button role="menuitem" :disabled="!layout.state.loaded" @click="startCreateProject(contextMenu?.groupId)"><i class="ri-add-circle-line"></i>新建项目<span>Shift N</span></button>
      <button role="menuitem" :disabled="!layout.state.loaded" @click="startGroupDialog(); closeContextMenu()"><i class="ri-folder-add-line"></i>新建分组</button>
      <template v-if="contextMenu.projectId">
        <div class="context-separator"></div>
        <button role="menuitem" @click="openProject(contextMenu.projectId!); closeContextMenu()"><i class="ri-arrow-right-up-line"></i>打开项目</button>
        <button role="menuitem" @click="copyProjectLink(contextMenu.projectId!)"><i class="ri-link"></i>复制项目链接</button>
        <div class="context-subtitle">移动到分组</div>
        <button v-for="group in projectGroups" :key="group.id" role="menuitem" class="context-group" :disabled="!layout.state.loaded || group.id === contextMenu.groupId" @click="moveProjectToGroup(contextMenu!.projectId!, group.id); closeContextMenu()">
          <span class="group-dot" :style="{ background: group.color }"></span>{{ group.label }}<i v-if="group.id === contextMenu.groupId" class="ri-check-line"></i>
        </button>
      </template>
      <template v-else-if="contextMenu.groupId">
        <div class="context-separator"></div>
        <button role="menuitem" :disabled="!layout.state.loaded" @click="renameGroup(contextMenu!.groupId!)"><i class="ri-edit-line"></i>重命名分组</button>
        <button role="menuitem" :disabled="!layout.state.loaded" @click="toggleGroup(contextMenu!.groupId!); closeContextMenu()"><i class="ri-collapse-diagonal-line"></i>{{ collapsedGroups.includes(contextMenu.groupId) ? '展开分组' : '折叠分组' }}</button>
      </template>
      <div class="context-separator"></div>
      <button role="menuitem" @click="refreshProjectList(); closeContextMenu()"><i class="ri-refresh-line"></i>刷新项目列表</button>
    </div>

    <div v-if="showGroupDialog" class="group-dialog-backdrop" @click.self="showGroupDialog = false">
      <form class="group-dialog" @submit.prevent="saveGroup">
        <div class="dialog-icon"><i class="ri-folder-add-line"></i></div>
        <h2>{{ editingGroupId ? '重命名分组' : '新建分组' }}</h2>
        <p>自定义分组只调整你的项目视图，不会修改项目所属领域或影响其他成员。</p>
        <label for="project-group-name">分组名称</label>
        <input id="project-group-name" v-model="newGroupName" autofocus maxlength="40" placeholder="例如：近期重点" @input="groupDialogError = ''" />
        <p v-if="groupDialogError" class="dialog-error" role="alert">{{ groupDialogError }}</p>
        <div class="dialog-actions"><button type="button" class="dialog-cancel" @click="showGroupDialog = false">取消</button><button type="submit" class="dialog-save" :disabled="!newGroupName.trim() || !layout.state.loaded">{{ editingGroupId ? '保存名称' : '添加分组' }}</button></div>
      </form>
    </div>
    <div v-if="notice" class="project-notice" role="status"><i class="ri-information-line"></i>{{ notice }}<button aria-label="关闭" @click="notice = ''"><i class="ri-close-line"></i></button></div>
  </div>
</template>

<style scoped>
.projects {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}

.ptop {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.ptitle {
  font-size: 20px;
  font-weight: 700;
}
.ptip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.btn-primary {
  height: 33px;
  padding: 0 14px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn-primary i {
  font-size: 15px;
}
.load-state {
  min-height: 220px;
  margin-top: 14px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 12px;
  color: var(--wao-text-weak);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
}
.load-state > i { color: var(--wao-primary-text); font-size: 24px; }
.load-state b { color: var(--wao-text); }
.load-state.error > i { color: #bc4141; }
.load-state button {
  margin-top: 4px;
  border: 1px solid var(--wao-line-strong);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  padding: 7px 11px;
  cursor: pointer;
}
.spin { animation: project-spin 0.9s linear infinite; }
@keyframes project-spin { to { transform: rotate(360deg); } }

.toolbar {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.drop-mask {
  position: fixed;
  inset: 0;
  z-index: 69;
}
.search {
  position: relative;
}
.search i {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--wao-text-weak);
  font-size: 14px;
}
.search input {
  height: 32px;
  width: 210px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 10px 0 32px;
  font-size: 13px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.search input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.drop-wrap {
  position: relative;
}
.tool-btn {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.tool-btn:hover {
  background: var(--wao-bg-hover);
}
.tool-btn i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.tool-btn .chev {
  font-size: 13px;
}
.drop-menu {
  position: absolute;
  right: 0;
  top: 36px;
  z-index: 70;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop);
  padding: 5px;
  width: 130px;
  animation: waoPop 0.14s ease;
}
.drop-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 7px 9px;
  font-size: 13px;
  color: var(--wao-text);
  cursor: pointer;
}
.drop-item span {
  flex: 1;
  text-align: left;
}
.drop-item i {
  font-size: 14px;
  color: var(--wao-primary-text);
}
.drop-item:hover {
  background: var(--wao-bg-hover-2);
}

.chips {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  border-radius: 999px;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.chip.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: #c7d0f1;
  font-weight: 600;
}
.chip-count {
  opacity: 0.65;
}

.groups {
  margin-top: 2px;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 2px 0;
  font-size: 12.5px;
  color: var(--wao-text-3);
  font-weight: 600;
}
.group-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
}
.group-count {
  color: var(--wao-text-faint);
  font-weight: 400;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.proj-card {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 15px 16px 13px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.proj-card:hover {
  border-color: #c9cdda;
  box-shadow: 0 3px 12px rgba(16, 18, 28, 0.07);
}
.pc-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pc-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 16px;
  flex-shrink: 0;
}
.pc-icon i {
  font-size: 19px;
}
.pc-name-wrap {
  flex: 1;
  min-width: 0;
}
.pc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pc-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.pc-status {
  font-size: 11px;
  border-radius: 999px;
  padding: 2px 9px;
  flex-shrink: 0;
}
.pc-goal {
  font-size: 12px;
  color: var(--wao-text-3);
  line-height: 1.7;
  min-height: 40px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pc-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.pc-m {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pc-m i {
  font-size: 13px;
}
.pc-avs {
  display: flex;
}
.pc-av {
  width: 19px;
  height: 19px;
  border-radius: 50%;
  color: #fff;
  font-size: 9.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #fff;
  margin-left: -5px;
}
.empty {
  margin-top: 14px;
  padding: 44px;
  text-align: center;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.empty i {
  font-size: 22px;
  color: var(--wao-text-faint-2);
}
.empty div {
  margin-top: 8px;
}

.project-layout-status {
  min-height: 26px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 3px 2px 5px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.project-layout-status span { display: inline-flex; align-items: center; gap: 6px; }
.project-layout-status [role='alert'] { color: #c45a5a; }
.project-layout-status button { padding: 3px 8px; border: 1px solid var(--wao-line-control); border-radius: 6px; color: var(--wao-primary-text); background: var(--wao-bg-card); font: inherit; cursor: pointer; }
.project-layout-status button:hover { border-color: var(--wao-primary); }
.project-canvas { min-height: 180px; outline: none; }
.group-section {
  border: 1px solid transparent;
  border-radius: 12px;
  transition: background .16s ease, border-color .16s ease;
}
.group-section.group-drag-over {
  border-color: var(--wao-primary);
  background: var(--wao-primary-tint);
}
.group-head {
  min-height: 42px;
  padding: 9px 8px 0;
  border-bottom: 1px solid var(--wao-line);
  cursor: grab;
  user-select: none;
}
.group-head:active { cursor: grabbing; }
.collapse-group {
  width: 25px;
  height: 25px;
  padding: 0;
  display: inline-grid;
  place-items: center;
  border: 0;
  border-radius: 6px;
  color: var(--wao-text-weak);
  background: transparent;
  cursor: pointer;
}
.collapse-group:disabled { cursor: not-allowed; opacity: .6; }
.collapse-group:hover { color: var(--wao-text); background: var(--wao-bg-hover); }
.collapse-group i { font-size: 16px; }
.group-reorder-hint {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--wao-text-faint);
  font-size: 11px;
  font-weight: 400;
  opacity: 0;
  transition: opacity .15s ease;
}
.group-head:hover .group-reorder-hint { opacity: 1; }
.group-reorder-hint i { font-size: 13px; }
.proj-card { position: relative; transition: transform .16s ease, border-color .16s ease, opacity .16s ease; }
.proj-card[draggable='true'] { cursor: grab; }
.proj-card:active { cursor: grabbing; }
.proj-card.dragging { opacity: .4; transform: scale(.98); }
.proj-card.card-drop-target:hover { border-color: var(--wao-primary); box-shadow: 0 0 0 2px color-mix(in srgb, var(--wao-primary) 15%, transparent); }

.context-menu {
  position: fixed;
  z-index: 220;
  width: 238px;
  max-height: min(430px, calc(100vh - 20px));
  overflow-y: auto;
  padding: 6px;
  border: 1px solid var(--wao-line-strong);
  border-radius: 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop .12s ease-out;
}
.context-caption, .context-subtitle {
  padding: 7px 9px 5px;
  color: var(--wao-text-faint);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: .02em;
}
.context-subtitle { padding-top: 6px; }
.context-menu > button {
  width: 100%;
  min-height: 36px;
  padding: 0 9px;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 0;
  border-radius: 7px;
  color: var(--wao-text);
  background: transparent;
  text-align: left;
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
}
.context-menu > button:hover, .context-menu > button:focus-visible { background: var(--wao-bg-hover-2); outline: none; }
.context-menu > button:disabled { opacity: .5; cursor: not-allowed; }
.context-menu > button > i { width: 16px; color: var(--wao-primary-text); font-size: 15px; }
.context-menu > button > span:not(.group-dot) { margin-left: auto; color: var(--wao-text-faint); font-size: 10px; }
.context-menu .context-group { min-height: 31px; padding-left: 15px; }
.context-menu .context-group > i { margin-left: auto; }
.context-separator { height: 1px; margin: 5px 3px; background: var(--wao-line); }

.group-dialog-backdrop {
  position: fixed;
  z-index: 230;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 18px;
  background: rgba(9, 12, 20, .56);
  backdrop-filter: blur(3px);
}
.group-dialog {
  width: min(420px, 100%);
  padding: 25px;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop .16s ease-out;
}
.dialog-icon { width: 39px; height: 39px; display: grid; place-items: center; border-radius: 11px; background: var(--wao-primary-tint); color: var(--wao-primary-text); font-size: 19px; }
.group-dialog h2 { margin: 15px 0 5px; color: var(--wao-text); font-size: 18px; }
.group-dialog p { margin: 0 0 19px; color: var(--wao-text-weak); font-size: 12px; line-height: 1.6; }
.group-dialog label { display: block; margin-bottom: 6px; color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.group-dialog input { width: 100%; height: 39px; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 8px; padding: 0 11px; background: var(--wao-bg-page); color: var(--wao-text); outline: none; font: inherit; font-size: 13px; }
.group-dialog input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--wao-primary) 15%, transparent); }
.dialog-error { margin: 8px 0 0 !important; color: #c45a5a !important; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 21px; }
.dialog-actions button { min-height: 35px; border-radius: 8px; padding: 0 13px; font: inherit; font-size: 12px; cursor: pointer; }
.dialog-cancel { border: 1px solid var(--wao-line-control); color: var(--wao-text-2); background: var(--wao-bg-card); }
.dialog-save { border: 1px solid var(--wao-primary); color: #fff; background: var(--wao-primary); }
.dialog-save:disabled { opacity: .5; cursor: not-allowed; }
.project-notice { position: fixed; z-index: 180; right: 22px; bottom: 22px; max-width: min(420px, calc(100vw - 32px)); display: flex; align-items: center; gap: 9px; padding: 11px 12px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); color: var(--wao-text-2); box-shadow: var(--wao-shadow-pop); font-size: 12px; animation: waoFadeUp .18s ease; }
.project-notice > i { color: var(--wao-primary-text); font-size: 15px; }
.project-notice button { margin-left: 4px; padding: 2px; border: 0; background: transparent; color: var(--wao-text-weak); cursor: pointer; }

@media (max-width: 760px) {
  .projects { padding: 16px 12px 28px; }
  .toolbar { gap: 7px; }
  .search { flex: 1 1 100%; }
  .search input { width: 100%; height: 38px; }
  .tool-btn, .btn-primary { min-height: 36px; }
  .card-grid { grid-template-columns: 1fr; }
  .group-reorder-hint { opacity: 1; }
  .context-menu { width: min(238px, calc(100vw - 20px)); }
}
</style>
