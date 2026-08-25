<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { fetchProjects } from '@/api/board'
import {
  createWorkspace,
  deleteWorkspace,
  fetchWorkspace,
  fetchWorkspaces,
  saveWorkspace,
  type WorkspaceDetail,
  type WorkspaceGroup,
  type WorkspaceLink,
  type WorkspaceSummary,
} from '@/api/workspaces'
import type { Project } from '@/types'

type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'error'

const LAST_SPACE_KEY = 'wao-workspace-last'
const CREATE_DRAFT_KEY = 'wao-workspace-create-draft'
const EDIT_DRAFT_PREFIX = 'wao-workspace-edit-draft:'

const summaries = ref<WorkspaceSummary[]>([])
const projects = ref<Project[]>([])
const activeId = ref('')
const active = ref<WorkspaceDetail | null>(null)
const loading = ref(true)
const detailLoading = ref(false)
const loadError = ref('')
const detailError = ref('')
const search = ref('')
const editing = ref(false)
const editModel = ref<WorkspaceDetail | null>(null)
const saveState = ref<SaveState>('idle')
const saveError = ref('')
const suppressDraftWatch = ref(false)
const createOpen = ref(false)
const creating = ref(false)
const createError = ref('')
const createForm = reactive(readCreateDraft())

const normalizedSearch = computed(() => search.value.trim().toLocaleLowerCase())
const visibleGroups = computed(() => {
  if (!active.value) return []
  return active.value.groups
    .map((group) => ({
      ...group,
      links: group.links.filter(matchesSearch),
    }))
    .filter((group) => !normalizedSearch.value || group.links.length > 0)
})
const favoriteLinks = computed(() =>
  (active.value?.groups.flatMap((group) => group.links) ?? [])
    .filter((link) => link.favorite && matchesSearch(link))
    .sort((left, right) => left.order - right.order),
)
const totalLinks = computed(
  () => active.value?.groups.reduce((sum, group) => sum + group.links.length, 0) ?? 0,
)
const saveLabel = computed(
  () =>
    ({
      idle: '已同步',
      dirty: '有未保存修改',
      saving: '正在保存',
      saved: '已保存',
      error: '保存失败',
    })[saveState.value],
)

watch(
  createForm,
  (value) => {
    if (value.name || value.description || value.projectId) {
      localStorage.setItem(CREATE_DRAFT_KEY, JSON.stringify(value))
    } else {
      localStorage.removeItem(CREATE_DRAFT_KEY)
    }
  },
  { deep: true },
)

watch(
  editModel,
  (value) => {
    if (suppressDraftWatch.value || !editing.value || !value || saveState.value === 'saving') return
    saveState.value = 'dirty'
    localStorage.setItem(editDraftKey(value.id), JSON.stringify(value))
  },
  { deep: true },
)

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [spaces, projectResult] = await Promise.all([
      fetchWorkspaces(),
      fetchProjects().catch(() => []),
    ])
    summaries.value = spaces
    projects.value = projectResult
    const remembered = localStorage.getItem(LAST_SPACE_KEY)
    const initial = spaces.find((item) => item.id === remembered)?.id ?? spaces[0]?.id ?? ''
    if (initial) await loadDetail(initial)
  } catch (error) {
    loadError.value = messageOf(error, '工作空间读取失败')
  } finally {
    loading.value = false
  }
}

async function loadDetail(id: string) {
  detailLoading.value = true
  detailError.value = ''
  try {
    active.value = await fetchWorkspace(id)
    activeId.value = id
    localStorage.setItem(LAST_SPACE_KEY, id)
    search.value = ''
  } catch (error) {
    detailError.value = messageOf(error, '空间内容读取失败')
  } finally {
    detailLoading.value = false
  }
}

async function selectSpace(event: Event) {
  const id = (event.target as HTMLSelectElement).value
  if (id === activeId.value) return
  if (
    editing.value &&
    ['dirty', 'error'].includes(saveState.value) &&
    !window.confirm('当前空间有未保存修改，确认切换并保留本机草稿吗？')
  ) {
    ;(event.target as HTMLSelectElement).value = activeId.value
    return
  }
  editing.value = false
  editModel.value = null
  await loadDetail(id)
}

function startEdit() {
  if (!active.value) return
  const raw = localStorage.getItem(editDraftKey(active.value.id))
  let draft: WorkspaceDetail | null = null
  if (raw) {
    try {
      const candidate = JSON.parse(raw) as WorkspaceDetail
      if (candidate.id === active.value.id && Array.isArray(candidate.groups)) draft = candidate
    } catch {
      localStorage.removeItem(editDraftKey(active.value.id))
    }
  }
  suppressDraftWatch.value = true
  editModel.value = clone(draft ?? active.value)
  editing.value = true
  saveState.value = draft ? 'dirty' : 'idle'
  saveError.value = ''
  void nextTick(() => {
    suppressDraftWatch.value = false
  })
}

function cancelEdit() {
  if (['dirty', 'error'].includes(saveState.value) && !window.confirm('放弃当前修改和本机草稿吗？'))
    return
  if (active.value) localStorage.removeItem(editDraftKey(active.value.id))
  editing.value = false
  editModel.value = null
  saveState.value = 'idle'
}

async function persist() {
  const model = editModel.value
  if (!model || saveState.value === 'saving') return
  saveState.value = 'saving'
  saveError.value = ''
  try {
    const saved = await saveWorkspace(model.id, {
      name: model.name,
      description: model.description,
      projectId: model.projectId || null,
      groups: model.groups,
    })
    active.value = saved
    suppressDraftWatch.value = true
    editModel.value = clone(saved)
    await nextTick()
    localStorage.removeItem(editDraftKey(saved.id))
    suppressDraftWatch.value = false
    upsertSummary(saved)
    saveState.value = 'saved'
  } catch (error) {
    saveError.value = messageOf(error, '保存失败，本机草稿已保留')
    saveState.value = 'error'
  }
}

function addGroup() {
  if (!editModel.value || editModel.value.groups.length >= 12) return
  editModel.value.groups.push({
    id: temporaryId('wg'),
    name: '',
    icon: 'ri-folder-3-line',
    order: editModel.value.groups.length,
    links: [],
  })
}

function removeGroup(index: number) {
  const group = editModel.value?.groups[index]
  if (
    !group ||
    (group.links.length &&
      !window.confirm(`删除“${group.name || '未命名分组'}”及其中 ${group.links.length} 个链接？`))
  )
    return
  editModel.value?.groups.splice(index, 1)
}

function addLink(group: WorkspaceGroup) {
  if (!editModel.value || linkCount(editModel.value.groups) >= 240) return
  group.links.push({
    id: temporaryId('wl'),
    title: '',
    url: 'https://',
    description: '',
    environment: '',
    icon: 'ri-links-line',
    order: group.links.length,
    favorite: false,
  })
}

function removeLink(group: WorkspaceGroup, index: number) {
  group.links.splice(index, 1)
}

function move<T>(items: T[], index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= items.length) return
  const [item] = items.splice(index, 1)
  if (item) items.splice(target, 0, item)
}

function openCreate() {
  createOpen.value = true
  createError.value = ''
}

async function submitCreate() {
  if (!createForm.name.trim() || creating.value) return
  creating.value = true
  createError.value = ''
  try {
    const created = await createWorkspace({
      name: createForm.name,
      description: createForm.description,
      projectId: createForm.projectId || null,
    })
    active.value = created
    activeId.value = created.id
    localStorage.setItem(LAST_SPACE_KEY, created.id)
    upsertSummary(created)
    createOpen.value = false
    Object.assign(createForm, { name: '', description: '', projectId: '' })
    localStorage.removeItem(CREATE_DRAFT_KEY)
    startEdit()
  } catch (error) {
    createError.value = messageOf(error, '创建失败，草稿仍在本机')
  } finally {
    creating.value = false
  }
}

async function removeSpace() {
  const target = active.value
  if (!target || !window.confirm(`删除工作空间“${target.name}”？这只会删除 WAO 中的导航资料。`))
    return
  try {
    await deleteWorkspace(target.id)
    localStorage.removeItem(editDraftKey(target.id))
    summaries.value = summaries.value.filter((item) => item.id !== target.id)
    active.value = null
    activeId.value = ''
    editing.value = false
    const next = summaries.value[0]
    if (next) await loadDetail(next.id)
    else localStorage.removeItem(LAST_SPACE_KEY)
  } catch (error) {
    detailError.value = messageOf(error, '删除失败，请重试')
  }
}

function upsertSummary(space: WorkspaceDetail) {
  const summary: WorkspaceSummary = {
    id: space.id,
    name: space.name,
    description: space.description,
    projectId: space.projectId,
    projectName: space.projectName,
    groupCount: space.groups.length,
    linkCount: linkCount(space.groups),
    updatedAt: space.updatedAt,
  }
  summaries.value = [summary, ...summaries.value.filter((item) => item.id !== space.id)]
}

function matchesSearch(link: WorkspaceLink) {
  const query = normalizedSearch.value
  return (
    !query ||
    `${link.title}\n${link.url}\n${link.description}\n${link.environment}`
      .toLocaleLowerCase()
      .includes(query)
  )
}
function displayHost(url: string) {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}
function editDraftKey(id: string) {
  return `${EDIT_DRAFT_PREFIX}${id}`
}
function linkCount(groups: WorkspaceGroup[]) {
  return groups.reduce((sum, group) => sum + group.links.length, 0)
}
function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}
function temporaryId(prefix: string) {
  return `${prefix}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 9)}`
}
function messageOf(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}
function readCreateDraft() {
  try {
    const value = JSON.parse(localStorage.getItem(CREATE_DRAFT_KEY) ?? '{}') as Record<
      string,
      unknown
    >
    return {
      name: typeof value.name === 'string' ? value.name : '',
      description: typeof value.description === 'string' ? value.description : '',
      projectId: typeof value.projectId === 'string' ? value.projectId : '',
    }
  } catch {
    return { name: '', description: '', projectId: '' }
  }
}

onMounted(loadAll)
</script>

<template>
  <main class="workspace-page">
    <header class="workspace-hero">
      <div>
        <span class="eyebrow">MY WORK SYSTEM</span>
        <h1>工作台</h1>
        <p>把环境、后台、知识页和项目工具收进自己的空间，需要时一眼找到。</p>
      </div>
      <button class="primary-button" type="button" @click="openCreate">
        <i class="ri-add-line"></i>新建空间
      </button>
    </header>

    <section v-if="loading" class="state-card">
      <i class="ri-loader-4-line spin"></i><b>正在读取你的工作空间…</b>
    </section>
    <section v-else-if="loadError" class="state-card error">
      <i class="ri-error-warning-line"></i><b>{{ loadError }}</b
      ><button @click="loadAll">重新读取</button>
    </section>
    <section v-else-if="!summaries.length" class="empty-card">
      <div class="empty-icon"><i class="ri-layout-grid-line"></i></div>
      <h2>从第一个工作空间开始</h2>
      <p>可以按项目、公司或工作场景建空间，再把真实页面按组收进来。每位成员只看到自己的内容。</p>
      <button class="primary-button" type="button" @click="openCreate">
        <i class="ri-add-line"></i>创建工作空间
      </button>
    </section>

    <template v-else>
      <section class="space-toolbar">
        <label class="space-switcher">
          <span>当前空间</span>
          <select :value="activeId" :disabled="detailLoading" @change="selectSpace">
            <option v-for="space in summaries" :key="space.id" :value="space.id">
              {{ space.name }} · {{ space.linkCount }} 个链接
            </option>
          </select>
        </label>
        <div v-if="!editing" class="toolbar-actions">
          <button class="ghost-button" type="button" :disabled="!active" @click="startEdit">
            <i class="ri-edit-line"></i>整理空间
          </button>
          <button
            class="icon-button danger"
            type="button"
            title="删除当前空间"
            :disabled="!active"
            @click="removeSpace"
          >
            <i class="ri-delete-bin-line"></i>
          </button>
        </div>
        <div v-else class="toolbar-actions edit-actions">
          <span class="save-state" :class="saveState"
            ><i
              :class="
                saveState === 'saving'
                  ? 'ri-loader-4-line spin'
                  : saveState === 'error'
                    ? 'ri-error-warning-line'
                    : 'ri-cloud-line'
              "
            ></i
            >{{ saveLabel }}</span
          >
          <button
            class="ghost-button"
            type="button"
            :disabled="saveState === 'saving'"
            @click="cancelEdit"
          >
            取消
          </button>
          <button
            class="primary-button"
            type="button"
            :disabled="saveState === 'saving'"
            @click="persist"
          >
            <i class="ri-save-line"></i>{{ saveState === 'error' ? '重试保存' : '保存' }}
          </button>
        </div>
      </section>

      <section v-if="detailLoading" class="state-card">
        <i class="ri-loader-4-line spin"></i><b>正在打开空间…</b>
      </section>
      <section v-else-if="detailError" class="state-card error">
        <i class="ri-error-warning-line"></i><b>{{ detailError }}</b
        ><button @click="loadDetail(activeId)">重试</button>
      </section>

      <template v-else-if="active && !editing">
        <section class="space-intro">
          <div>
            <span v-if="active.projectName" class="project-pill"
              ><i class="ri-stack-line"></i>{{ active.projectName }}</span
            >
            <h2>{{ active.name }}</h2>
            <p>{{ active.description || '还没有空间说明，可以在“整理空间”中补充。' }}</p>
          </div>
          <div class="space-stats">
            <span
              ><b>{{ active.groups.length }}</b> 个分组</span
            ><span
              ><b>{{ totalLinks }}</b> 个链接</span
            >
          </div>
        </section>

        <label v-if="totalLinks" class="search-box"
          ><i class="ri-search-line"></i
          ><input v-model="search" placeholder="搜索标题、地址、环境或备注" /><button
            v-if="search"
            type="button"
            title="清空搜索"
            @click="search = ''"
          >
            <i class="ri-close-line"></i></button
        ></label>

        <section v-if="favoriteLinks.length" class="favorite-section">
          <div class="section-title">
            <i class="ri-star-fill"></i>
            <h3>常用收藏</h3>
          </div>
          <div class="link-grid compact">
            <a
              v-for="link in favoriteLinks"
              :key="link.id"
              class="link-card"
              :href="link.url"
              target="_blank"
              rel="noopener noreferrer"
              ><i :class="link.icon"></i
              ><span
                ><b>{{ link.title }}</b
                ><small>{{ link.environment || displayHost(link.url) }}</small></span
              ><i class="ri-arrow-right-up-line open-icon"></i
            ></a>
          </div>
        </section>

        <section v-for="group in visibleGroups" :key="group.id" class="group-section">
          <div class="section-title">
            <i :class="group.icon"></i>
            <h3>{{ group.name }}</h3>
            <span>{{ group.links.length }}</span>
          </div>
          <div v-if="group.links.length" class="link-grid">
            <a
              v-for="link in group.links"
              :key="link.id"
              class="link-card"
              :href="link.url"
              target="_blank"
              rel="noopener noreferrer"
            >
              <i :class="link.icon"></i>
              <span
                ><b>{{ link.title }}</b
                ><small>{{ link.environment || displayHost(link.url) }}</small
                ><em v-if="link.description">{{ link.description }}</em></span
              >
              <i class="ri-arrow-right-up-line open-icon"></i>
            </a>
          </div>
          <div v-else-if="!search" class="group-empty">这个分组还没有链接</div>
        </section>
        <section v-if="search && !visibleGroups.length" class="state-card">
          <i class="ri-search-eye-line"></i><b>没有匹配“{{ search }}”的链接</b>
        </section>
        <section v-else-if="!active.groups.length" class="state-card">
          <i class="ri-folder-add-line"></i><b>空间还是空的，点击“整理空间”添加分组和链接</b>
        </section>
      </template>

      <form v-else-if="editModel" class="workspace-editor" @submit.prevent="persist">
        <section class="editor-meta">
          <label
            ><span>空间名称</span
            ><input v-model="editModel.name" maxlength="60" required placeholder="例如：能良工作"
          /></label>
          <label
            ><span>关联项目 <small>可选</small></span
            ><select v-model="editModel.projectId">
              <option :value="null">不关联项目</option>
              <option v-for="project in projects" :key="project.id" :value="project.id">
                {{ project.name }}
              </option>
            </select></label
          >
          <label class="wide"
            ><span>空间说明</span
            ><textarea
              v-model="editModel.description"
              maxlength="400"
              rows="2"
              placeholder="这个空间解决什么问题、适合放哪些入口"
            ></textarea>
          </label>
        </section>
        <p class="draft-hint">
          <i class="ri-shield-check-line"></i
          >修改会立即保存在本机草稿中，只有点击“保存”才会同步到服务端。
        </p>
        <p v-if="saveError" class="inline-error">
          <i class="ri-error-warning-line"></i>{{ saveError }}
        </p>

        <section
          v-for="(group, groupIndex) in editModel.groups"
          :key="group.id"
          class="group-editor"
        >
          <div class="group-editor-head">
            <i :class="group.icon"></i>
            <input
              v-model="group.name"
              maxlength="40"
              required
              placeholder="分组名称"
              :aria-label="`第 ${groupIndex + 1} 个分组名称`"
            />
            <input
              v-model="group.icon"
              maxlength="64"
              placeholder="ri-folder-3-line"
              aria-label="分组图标"
            />
            <button
              type="button"
              title="上移分组"
              :disabled="groupIndex === 0"
              @click="move(editModel.groups, groupIndex, -1)"
            >
              <i class="ri-arrow-up-line"></i>
            </button>
            <button
              type="button"
              title="下移分组"
              :disabled="groupIndex === editModel.groups.length - 1"
              @click="move(editModel.groups, groupIndex, 1)"
            >
              <i class="ri-arrow-down-line"></i>
            </button>
            <button type="button" class="danger" title="删除分组" @click="removeGroup(groupIndex)">
              <i class="ri-delete-bin-line"></i>
            </button>
          </div>
          <div v-for="(link, linkIndex) in group.links" :key="link.id" class="link-editor">
            <label
              ><span>标题</span
              ><input
                v-model="link.title"
                maxlength="100"
                required
                placeholder="例如：Nacos 测试环境"
            /></label>
            <label class="url-field"
              ><span>地址</span
              ><input
                v-model="link.url"
                maxlength="2048"
                required
                inputmode="url"
                placeholder="https://…"
            /></label>
            <label
              ><span>环境</span
              ><input v-model="link.environment" maxlength="30" placeholder="测试 / 预发 / 生产"
            /></label>
            <label
              ><span>备注</span
              ><input v-model="link.description" maxlength="300" placeholder="用途或使用提示"
            /></label>
            <label
              ><span>图标</span
              ><input v-model="link.icon" maxlength="64" placeholder="ri-links-line"
            /></label>
            <label class="favorite-toggle"
              ><input v-model="link.favorite" type="checkbox" /><span
                ><i class="ri-star-line"></i>收藏</span
              ></label
            >
            <div class="row-actions">
              <button
                type="button"
                title="上移链接"
                :disabled="linkIndex === 0"
                @click="move(group.links, linkIndex, -1)"
              >
                <i class="ri-arrow-up-line"></i>
              </button>
              <button
                type="button"
                title="下移链接"
                :disabled="linkIndex === group.links.length - 1"
                @click="move(group.links, linkIndex, 1)"
              >
                <i class="ri-arrow-down-line"></i>
              </button>
              <button
                type="button"
                class="danger"
                title="删除链接"
                @click="removeLink(group, linkIndex)"
              >
                <i class="ri-delete-bin-line"></i>
              </button>
            </div>
          </div>
          <button
            class="add-row"
            type="button"
            :disabled="linkCount(editModel.groups) >= 240"
            @click="addLink(group)"
          >
            <i class="ri-add-line"></i>添加链接
          </button>
        </section>
        <button
          class="add-group"
          type="button"
          :disabled="editModel.groups.length >= 12"
          @click="addGroup"
        >
          <i class="ri-folder-add-line"></i>添加分组
        </button>
      </form>
    </template>

    <div v-if="createOpen" class="modal-backdrop" @click.self="createOpen = false">
      <form class="create-modal" @submit.prevent="submitCreate">
        <div class="modal-head">
          <div>
            <span class="eyebrow">NEW SPACE</span>
            <h2>新建工作空间</h2>
          </div>
          <button type="button" title="关闭" @click="createOpen = false">
            <i class="ri-close-line"></i>
          </button>
        </div>
        <label
          ><span>空间名称</span
          ><input
            v-model="createForm.name"
            maxlength="60"
            required
            autofocus
            placeholder="例如：能良工作"
        /></label>
        <label
          ><span>关联项目 <small>可选</small></span
          ><select v-model="createForm.projectId">
            <option value="">暂不关联</option>
            <option v-for="project in projects" :key="project.id" :value="project.id">
              {{ project.name }}
            </option>
          </select></label
        >
        <label
          ><span>空间说明</span
          ><textarea
            v-model="createForm.description"
            maxlength="400"
            rows="3"
            placeholder="放哪些入口、用于什么场景"
          ></textarea>
        </label>
        <small class="draft-note">未提交内容会保存在本机</small>
        <p v-if="createError" class="inline-error">
          <i class="ri-error-warning-line"></i>{{ createError }}
        </p>
        <div class="modal-actions">
          <button
            type="button"
            class="ghost-button"
            :disabled="creating"
            @click="createOpen = false"
          >
            稍后再建</button
          ><button class="primary-button" :disabled="creating || !createForm.name.trim()">
            <i :class="creating ? 'ri-loader-4-line spin' : 'ri-add-line'"></i
            >{{ creating ? '正在创建' : '创建空间' }}
          </button>
        </div>
      </form>
    </div>
  </main>
</template>

<style scoped>
.workspace-page {
  min-height: 100%;
  padding: 30px 34px 70px;
  background: linear-gradient(155deg, var(--wao-bg-page) 0%, var(--wao-bg-sub) 100%);
  color: var(--wao-text);
}
.workspace-hero,
.space-toolbar,
.space-intro,
.section-title,
.group-editor-head,
.modal-head,
.modal-actions,
.toolbar-actions,
.space-stats {
  display: flex;
  align-items: center;
}
.workspace-hero {
  justify-content: space-between;
  gap: 24px;
  margin: 0 auto 22px;
  max-width: 1240px;
}
.workspace-hero h1 {
  font-size: 38px;
  letter-spacing: -1.5px;
  margin: 5px 0 4px;
}
.workspace-hero p,
.space-intro p {
  margin: 0;
  color: var(--wao-text-3);
}
.eyebrow {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 1.8px;
  color: #876d46;
}
.primary-button,
.ghost-button,
.icon-button,
.state-card button,
.add-row,
.add-group {
  border: 0;
  border-radius: 10px;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
}
.primary-button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: #25272d;
  color: white;
  padding: 11px 16px;
  box-shadow: 0 8px 20px #25272d24;
}
.primary-button:disabled,
.ghost-button:disabled,
.group-editor button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.ghost-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line-control);
  padding: 9px 13px;
  color: var(--wao-text-2);
}
.icon-button {
  width: 39px;
  height: 39px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line-control);
}
.danger {
  color: #b64b4b !important;
}
.state-card,
.empty-card {
  max-width: 1240px;
  margin: 20px auto;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  padding: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--wao-text-3);
}
.state-card.error {
  color: #a33c3c;
}
.state-card button {
  padding: 7px 10px;
}
.empty-card {
  min-height: 350px;
  flex-direction: column;
  text-align: center;
}
.empty-card h2 {
  margin: 7px 0 0;
}
.empty-card p {
  max-width: 520px;
  line-height: 1.7;
  color: var(--wao-text-3);
}
.empty-icon {
  width: 70px;
  height: 70px;
  border-radius: 22px;
  background: #f2eadc;
  color: #8b7046;
  display: grid;
  place-items: center;
  font-size: 31px;
}
.space-toolbar {
  max-width: 1240px;
  margin: 0 auto 14px;
  justify-content: space-between;
  gap: 16px;
  background: color-mix(in srgb, var(--wao-bg-card) 79%, transparent);
  backdrop-filter: blur(12px);
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  padding: 12px 14px;
}
.space-switcher {
  display: flex;
  align-items: center;
  gap: 10px;
}
.space-switcher span {
  font-size: 12px;
  font-weight: 700;
  color: var(--wao-text-3);
}
.space-switcher select,
.editor-meta select,
.create-modal select {
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 9px 34px 9px 11px;
  min-width: 230px;
  font: inherit;
}
.toolbar-actions {
  gap: 8px;
}
.save-state {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  font-size: 12px;
  color: var(--wao-text-3);
  margin-right: 4px;
}
.save-state.dirty {
  color: #9a6c20;
}
.save-state.error {
  color: #b13f3f;
}
.save-state.saved {
  color: #3f7e5b;
}
.space-intro {
  max-width: 1180px;
  margin: 0 auto 16px;
  padding: 26px 30px;
  justify-content: space-between;
  gap: 20px;
  background: #24262b;
  color: #fff;
  border-radius: 19px;
  box-shadow: 0 18px 40px #23242a22;
}
.space-intro h2 {
  font-size: 30px;
  margin: 8px 0 5px;
}
.space-intro p {
  color: #c6c8ce;
}
.project-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 5px 9px;
  border-radius: 99px;
  background: #ffffff18;
  color: #ded3bd;
}
.space-stats {
  gap: 22px;
}
.space-stats span {
  display: flex;
  flex-direction: column;
  color: #aaadb4;
  font-size: 11px;
}
.space-stats b {
  color: white;
  font-size: 24px;
}
.search-box {
  max-width: 700px;
  margin: 18px auto;
  display: flex;
  align-items: center;
  gap: 9px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 0 13px;
}
.search-box input {
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text);
  padding: 12px 0;
  font: inherit;
}
.search-box button {
  border: 0;
  background: none;
  cursor: pointer;
}
.favorite-section,
.group-section {
  max-width: 1180px;
  margin: 24px auto;
}
.section-title {
  gap: 8px;
  margin: 0 2px 11px;
  color: var(--wao-text-2);
}
.section-title > i {
  color: #947546;
}
.section-title h3 {
  margin: 0;
  font-size: 16px;
}
.section-title span {
  font-size: 11px;
  color: var(--wao-text-weak);
}
.favorite-section .section-title > i {
  color: #d19d37;
}
.link-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.link-card {
  min-width: 0;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 18px;
  gap: 10px;
  align-items: center;
  padding: 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  text-decoration: none;
  border: 1px solid var(--wao-line);
  border-radius: 13px;
  transition: 0.16s ease;
}
.link-card:hover {
  transform: translateY(-2px);
  border-color: #c8b28d;
  box-shadow: 0 10px 25px #45403812;
}
.link-card > i:first-child {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: #f2eee6;
  color: #82663e;
  font-size: 18px;
}
.link-card span {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.link-card b,
.link-card small,
.link-card em {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.link-card small {
  margin-top: 3px;
  color: var(--wao-text-weak);
}
.link-card em {
  margin-top: 5px;
  color: var(--wao-text-3);
  font-size: 11px;
  font-style: normal;
}
.open-icon {
  color: var(--wao-text-faint);
}
.group-empty {
  padding: 24px;
  background: color-mix(in srgb, var(--wao-bg-card) 55%, transparent);
  border: 1px dashed var(--wao-line-control);
  border-radius: 12px;
  color: var(--wao-text-weak);
  text-align: center;
}
.workspace-editor {
  max-width: 1180px;
  margin: 0 auto;
}
.editor-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 15px;
  padding: 20px;
}
.editor-meta label,
.create-modal label,
.link-editor label {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.editor-meta label > span,
.create-modal label > span,
.link-editor label > span {
  font-size: 12px;
  font-weight: 700;
  color: var(--wao-text-3);
}
.editor-meta .wide {
  grid-column: 1/-1;
}
.editor-meta input,
.editor-meta textarea,
.group-editor input,
.link-editor input,
.create-modal input,
.create-modal textarea {
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 10px 11px;
  font: inherit;
  outline: none;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.editor-meta input:focus,
.editor-meta textarea:focus,
.group-editor input:focus,
.link-editor input:focus,
.create-modal input:focus,
.create-modal textarea:focus {
  border-color: #a98b5c;
  box-shadow: 0 0 0 3px #a98b5c1c;
}
.draft-hint,
.inline-error {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.draft-hint {
  color: #6e746e;
  margin: 11px 4px;
}
.inline-error {
  color: #b13d3d;
  background: var(--wao-danger-bg);
  border-radius: 9px;
  padding: 9px 11px;
}
.group-editor {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 15px;
  padding: 14px;
  margin: 12px 0;
}
.group-editor-head {
  gap: 7px;
}
.group-editor-head > i {
  width: 34px;
  text-align: center;
  color: #8a6d40;
}
.group-editor-head > input:nth-of-type(1) {
  flex: 1;
  font-weight: 700;
}
.group-editor-head > input:nth-of-type(2) {
  width: 170px;
}
.group-editor button,
.row-actions button,
.modal-head button {
  border: 0;
  background: var(--wao-bg-hover-2);
  color: var(--wao-text-2);
  border-radius: 8px;
  width: 34px;
  height: 34px;
  cursor: pointer;
}
.link-editor {
  display: grid;
  grid-template-columns: 1fr 1.7fr 0.65fr 1.2fr 0.75fr auto auto;
  gap: 8px;
  align-items: end;
  margin-top: 12px;
  padding: 12px;
  background: var(--wao-bg-sub);
  border-radius: 11px;
}
.link-editor input {
  width: 100%;
  box-sizing: border-box;
  background: var(--wao-bg-card);
}
.favorite-toggle {
  align-items: center !important;
  justify-content: center;
  padding-bottom: 9px;
}
.favorite-toggle > input {
  width: auto;
}
.favorite-toggle > span {
  display: flex;
  gap: 4px;
}
.row-actions {
  display: flex;
  gap: 4px;
  padding-bottom: 3px;
}
.add-row,
.add-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 12px;
  margin-top: 11px;
  background: #f0eee9;
  color: #65583f;
}
.add-group {
  background: #282a30;
  color: #fff;
  margin: 2px 0 30px;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: #17181c80;
  display: grid;
  place-items: center;
  padding: 16px;
}
.create-modal {
  width: min(500px, calc(100vw - 32px));
  box-sizing: border-box;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border-radius: 18px;
  padding: 22px;
  box-shadow: 0 30px 80px #0004;
  display: grid;
  gap: 14px;
}
.modal-head {
  justify-content: space-between;
}
.modal-head h2 {
  margin: 4px 0;
}
.modal-head button {
  font-size: 18px;
}
.draft-note {
  color: var(--wao-text-weak);
}
.modal-actions {
  justify-content: flex-end;
  gap: 8px;
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 1000px) {
  .link-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .link-editor {
    grid-template-columns: 1fr 1.5fr 1fr;
  }
  .link-editor .url-field {
    grid-column: span 2;
  }
  .row-actions {
    grid-column: span 2;
    justify-content: flex-end;
  }
}
@media (max-width: 650px) {
  .workspace-page {
    padding: 18px 12px 90px;
  }
  .workspace-hero {
    align-items: flex-start;
  }
  .workspace-hero h1 {
    font-size: 31px;
  }
  .workspace-hero p {
    font-size: 13px;
  }
  .workspace-hero > .primary-button {
    padding: 10px;
    font-size: 0;
  }
  .workspace-hero > .primary-button i {
    font-size: 19px;
  }
  .space-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .space-switcher {
    align-items: stretch;
    flex-direction: column;
    gap: 5px;
  }
  .space-switcher select {
    width: 100%;
    min-width: 0;
  }
  .toolbar-actions {
    justify-content: flex-end;
  }
  .space-intro {
    align-items: flex-start;
    flex-direction: column;
    padding: 21px;
  }
  .link-grid {
    grid-template-columns: 1fr;
  }
  .editor-meta {
    grid-template-columns: 1fr;
  }
  .editor-meta .wide {
    grid-column: auto;
  }
  .group-editor-head {
    flex-wrap: wrap;
  }
  .group-editor-head > input:nth-of-type(1) {
    min-width: calc(100% - 46px);
  }
  .group-editor-head > input:nth-of-type(2) {
    width: 100%;
    order: 3;
  }
  .link-editor {
    grid-template-columns: 1fr;
  }
  .link-editor .url-field,
  .row-actions {
    grid-column: auto;
  }
  .favorite-toggle {
    align-items: flex-start !important;
  }
  .row-actions {
    justify-content: flex-start;
  }
  .edit-actions {
    flex-wrap: wrap;
  }
  .save-state {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
