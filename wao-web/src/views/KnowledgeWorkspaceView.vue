<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRefs, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useTaskViewMemory } from '@/composables/taskViewMemory'
import { useBrowsingState, oneOf, validSearch } from '@/composables/browsingState'
import { useSessionStore } from '@/stores/session'
import { useNow } from '@/composables/now'
import { absoluteTime, relativeTime } from '@/utils/time'
import { directoryRows, groupLevelExpansion } from '@/utils/knowledgeDirectory'
import type { DirectoryNode } from '@/utils/knowledgeDirectory'
import KbDetailView from './KbDetailView.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoEmptyState from '@/components/ui/WaoEmptyState.vue'
import WaoField from '@/components/ui/WaoField.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import KnowledgeIconPicker from '@/components/knowledge/KnowledgeIconPicker.vue'
import KnowledgeDirectoryTree from '@/components/knowledge/KnowledgeDirectoryTree.vue'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { usePageVisibility } from '@/composables/pageVisibility'
import { useNarrowWorkspace } from '@/composables/viewport'

const data = useDataStore(), route = useRoute(), router = useRouter(), session = useSessionStore()
const now = useNow(60_000)
const selected = computed(() => String(route.params.id || route.query.kb || ''))
const selectedName = computed(() => libraries.value.find(kb => kb.id === selected.value)?.name || '全部内容')
const libraries = computed(() => data.kbs.filter(kb => !kb.notes))
const libraryEntries = computed(() => libraries.value.map(kb => ({
  kb, count: new Set(kb.folders.flatMap(folder => folder.docs)).size,
})))
const memory = useTaskViewMemory('knowledge-workspace', () => ({ collapsed: false }))
const directoryMemory = useTaskViewMemory('knowledge-directory', () => ({ collapsed: false }))
const directoryWidth = ref(262)
const documentFocus = ref(false)
const folderOpen = ref<Record<string, Record<string, boolean>>>({})
const { query, view } = toRefs(useBrowsingState('knowledge-home', { query: '', view: 'directory' }, {
  query: validSearch, view: oneOf('directory', 'recent'),
}))
const keyword = computed(() => query.value.trim().toLocaleLowerCase())
const searchInput = ref<HTMLInputElement | null>(null)
function clearSearch() { query.value = ''; void nextTick(() => searchInput.value?.focus({ preventScroll: true })) }
const rowLimit = ref<Record<string, number>>({})
const recentLimit = ref(30)
// Body draft writes replace docEdits; retain the title projection when only body text changed.
const titleIndex = computed<Map<string, string>>((previous) => {
  const next = new Map<string, string>()
  for (const library of libraries.value) for (const folder of library.folders) for (const id of folder.docs) {
    next.set(id, (data.docEdits[id]?.title ?? data.docById(id)?.title) || '未命名文档')
  }
  if (previous?.size === next.size && [...next].every(([id, title]) => previous.get(id) === title)) return previous
  return next
})
const normalizedTitle = (id: string) => titleIndex.value.get(id) || '未命名文档'
const documentIcon = (id: string) => ({ spreadsheet: 'ri-table-line', drawing: 'ri-artboard-line', document: 'ri-file-text-line' })[data.docById(id)?.pageType ?? 'document']
const documentTime = (id: string) => data.docById(id)?.updated || data.docById(id)?.created || ''

// Keep only metadata in the directory index; opening the home never resolves a document body.
const catalog = computed(() => libraryEntries.value.map(({ kb, count }) => {
  const rows = directoryRows(kb, normalizedTitle, {}, '')
  return { kb, rows, count, defaults: groupLevelExpansion(kb) }
}))
const totalDocuments = computed(() => libraryEntries.value.reduce((count, item) => count + item.count, 0))
const index = computed(() => catalog.value.map(item => {
  const wholeLibrary = item.kb.name.toLocaleLowerCase().includes(keyword.value)
  const rows = !keyword.value || wholeLibrary ? item.rows : directoryRows(item.kb, normalizedTitle, {}, query.value, undefined, { includeMatchedDescendants: true })
  return { ...item, rows, wholeLibrary }
}).filter(item => item.rows.length || !keyword.value || item.wholeLibrary))
const resultCount = computed(() => index.value.reduce((count, item) => count + item.rows.filter(node => node.kind === 'doc').length, 0))
const treeGroups = computed(() => index.value.map(item => {
  const expanded = { ...item.defaults, ...folderOpen.value[item.kb.id] }
  const treeQuery = item.wholeLibrary ? '' : query.value
  return { ...item, expanded, rows: keyword.value && item.wholeLibrary ? item.rows : directoryRows(item.kb, normalizedTitle, expanded, treeQuery, undefined, { includeMatchedDescendants: true }) }
}))
const allExpanded = computed(() => treeGroups.value.every(item => item.rows.every(node => !node.hasChildren || item.expanded[node.key] !== false)))
const recentDocuments = computed(() => index.value.flatMap(item => item.rows
  .filter(node => node.kind === 'doc')
  .map(node => ({ node, kb: item.kb, updated: documentTime(node.id) })))
  .sort((left, right) => (Date.parse(right.updated) || 0) - (Date.parse(left.updated) || 0)))
function toggleNode(kbId: string, key: string, expanded: boolean) {
  folderOpen.value[kbId] = { ...folderOpen.value[kbId], [key]: expanded }
}
function toggleAll() {
  const expand = !allExpanded.value
  for (const item of catalog.value) folderOpen.value[item.kb.id] = Object.fromEntries(item.rows.map(node => [node.key, expand]))
}
function visibleRows(kbId: string, rows: DirectoryNode[]) { return rows.slice(0, rowLimit.value[kbId] ?? 80) }
function showMore(kbId: string) { rowLimit.value[kbId] = (rowLimit.value[kbId] ?? 80) + 100 }

const workspace = ref<HTMLElement | null>(null)
const narrow = useNarrowWorkspace(workspace, 900)
const mobileLibrary = ref(false), mobileDirectory = ref(false)
const pageVisible = usePageVisibility()
const paneId = useId()
let navigationTrigger: HTMLElement | null = null
const mobileLibraryButton = ref<HTMLButtonElement | null>(null), mobileDirectoryButton = ref<HTMLButtonElement | null>(null)
const librariesPanel = ref<HTMLElement | null>(null), directoryPanel = ref<HTMLElement | null>(null)
const mobileNavigationOpen = computed(() => narrow.value && (mobileLibrary.value || mobileDirectory.value))
const librariesExpanded = computed(() => narrow.value ? mobileLibrary.value : !memory.value.collapsed)
const directoryExpanded = computed(() => narrow.value ? mobileDirectory.value : !directoryMemory.value.collapsed)
function toggleLibraries(event?: MouseEvent) {
  if (narrow.value) {
    if (mobileLibrary.value) { closeMobile(true); return }
    navigationTrigger = event?.currentTarget as HTMLElement | null
    mobileLibrary.value = !mobileLibrary.value; mobileDirectory.value = false
    if (mobileLibrary.value) void nextTick(() => librariesPanel.value?.querySelector<HTMLElement>('.pane-toggle')?.focus())
  }
  else memory.value.collapsed = !memory.value.collapsed
}
function toggleDirectory(event?: MouseEvent) {
  if (narrow.value) {
    if (mobileDirectory.value) { closeMobile(true); return }
    navigationTrigger = event?.currentTarget as HTMLElement | null
    mobileDirectory.value = !mobileDirectory.value; mobileLibrary.value = false
    if (mobileDirectory.value) void nextTick(() => directoryPanel.value?.querySelector<HTMLElement>('.pane-toggle')?.focus())
  }
  else directoryMemory.value.collapsed = !directoryMemory.value.collapsed
}
function closeMobile(returnFocus = false) {
  const wasOpen = mobileLibrary.value || mobileDirectory.value
  mobileLibrary.value = false; mobileDirectory.value = false
  if (wasOpen && returnFocus) void nextTick(restoreNavigationFocus)
}
function restoreNavigationFocus() {
  if (!narrow.value || !pageVisible.value) return
  const target = navigationTrigger?.isConnected ? navigationTrigger : mobileLibraryButton.value
  target?.focus({ preventScroll: true })
}
const navigationDismiss = useOverlayDismiss(() => mobileNavigationOpen.value, () => closeMobile(true))
function trapNavigation(event: KeyboardEvent) {
  if (!mobileNavigationOpen.value || !navigationDismiss.isTopmost() || event.defaultPrevented || event.key !== 'Tab') return
  const pane = mobileLibrary.value ? librariesPanel.value : directoryPanel.value
  const buttons = [...(pane?.querySelectorAll<HTMLElement>('button:not(:disabled):not([tabindex="-1"]), a[href], input:not(:disabled)') ?? [])]
  const first = buttons[0], last = buttons[buttons.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
async function open(kb = '', doc = '') {
  const restoreFocus = mobileNavigationOpen.value
  closeMobile()
  await router.push({ path: '/knowledge', query: { ...(kb ? { kb } : { home: '1' }), ...(doc ? { doc } : {}) } })
  if (restoreFocus) await nextTick(restoreNavigationFocus)
}
function onShortcut(event: KeyboardEvent) {
  if (selected.value || creating.value || event.isComposing || event.altKey || event.shiftKey) return
  if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'j') {
    event.preventDefault(); closeMobile()
    void nextTick(() => { searchInput.value?.focus(); searchInput.value?.select() })
  }
}
onMounted(() => window.addEventListener('keydown', onShortcut))
onBeforeUnmount(() => {
  createGeneration++
  window.removeEventListener('keydown', onShortcut)
})
watch(narrow, () => closeMobile())
watch(query, () => { rowLimit.value = {}; recentLimit.value = 30 })
watch(pageVisible, () => closeMobile())
watch(() => route.fullPath, () => closeMobile(true))
watch(selected, () => { documentFocus.value = false; closeMobile() })
watch(() => data.kbById(selected.value), kb => { if (!kb) documentFocus.value = false }, { flush: 'sync' })
let createGeneration = 0
watch(() => `${session.account?.accountId ?? ''}:${session.userId}`, () => {
  folderOpen.value = {}; rowLimit.value = {}; recentLimit.value = 30; documentFocus.value = false; closeMobile()
  createGeneration++
  creating.value = false; saving.value = false; name.value = ''; description.value = ''; icon.value = 'wao-kb-icon-notebook'; error.value = ''
}, { flush: 'sync' })

const creating = ref(false), saving = ref(false), name = ref(''), description = ref(''), icon = ref('wao-kb-icon-notebook'), error = ref('')
async function startCreate() {
  if (saving.value) return
  closeMobile(true)
  await nextTick()
  name.value = ''; description.value = ''; icon.value = 'wao-kb-icon-notebook'; error.value = ''; creating.value = true
}
async function create() {
  if (!name.value.trim() || saving.value) return
  const generation = ++createGeneration
  saving.value = true; error.value = ''
  try {
    const kb = await data.addKb({ name: name.value.trim(), desc: description.value.trim(), icon: icon.value })
    if (generation !== createGeneration) return
    if (!kb) throw new Error('create failed')
    creating.value = false; open(kb.id)
  } catch { if (generation === createGeneration) error.value = '创建失败，输入已保留，请重试。' }
  finally { if (generation === createGeneration) saving.value = false }
}
</script>

<template>
  <div ref="workspace" class="knowledge-workspace bounded-workspace" :class="{ 'document-focused': documentFocus }">
    <div v-if="narrow && !documentFocus" class="mobile-workspace-bar mobile-toolbar" role="navigation" aria-label="知识库浏览">
      <button ref="mobileLibraryButton" type="button" aria-label="选择知识库" :aria-controls="`${paneId}-libraries`" :aria-expanded="mobileLibrary" @click="toggleLibraries"><i class="ri-book-2-line" aria-hidden="true" /><span>{{ selectedName }}</span><i class="ri-arrow-down-s-line" aria-hidden="true" /></button>
      <button v-if="!selected" ref="mobileDirectoryButton" type="button" aria-label="查看全部目录" :aria-controls="`${paneId}-directory`" :aria-expanded="mobileDirectory" @click="toggleDirectory"><i class="ri-list-check" aria-hidden="true" />目录</button>
      <button type="button" class="mobile-add" aria-label="新增知识库" @click="startCreate"><i class="ri-add-line" aria-hidden="true" /></button>
    </div>
    <button v-if="mobileNavigationOpen && !documentFocus" type="button" class="mobile-backdrop navigation-mask" aria-label="关闭知识库导航" tabindex="-1" @click="navigationDismiss.isTopmost() && closeMobile(true)" />
    <aside v-show="!documentFocus && (!narrow || mobileLibrary)" :id="`${paneId}-libraries`" ref="librariesPanel" class="libraries" :class="{ collapsed: !librariesExpanded }" :role="narrow ? 'dialog' : undefined" :aria-modal="narrow ? true : undefined" aria-label="知识库清单" @keydown="trapNavigation">
      <header>
        <strong v-if="librariesExpanded">知识库</strong>
        <button v-if="librariesExpanded && !narrow" class="library-add icon-button" type="button" aria-label="新增知识库" title="新增知识库" @click="startCreate"><i class="ri-add-line" aria-hidden="true" /></button>
        <button type="button" class="pane-toggle icon-button" :aria-label="librariesExpanded ? '收起知识库清单' : '展开知识库清单'" :aria-expanded="librariesExpanded" @click="toggleLibraries"><i class="ri-side-bar-line" aria-hidden="true" /></button>
      </header>
      <nav v-show="librariesExpanded" class="library-list" aria-label="选择知识库">
        <button type="button" :class="{ active: !selected }" :aria-current="!selected ? 'page' : undefined" @click="open()"><i class="ri-home-4-line" aria-hidden="true" /><span>全部内容</span><small>{{ totalDocuments }}</small></button>
        <p class="navigation-label">我的知识库 <span>{{ libraries.length }}</span></p>
        <button v-for="item in libraryEntries" :key="item.kb.id" type="button" :title="item.kb.name" :class="{ active: selected === item.kb.id }" :aria-current="selected === item.kb.id ? 'page' : undefined" @click="open(item.kb.id)"><i :class="item.kb.icon" aria-hidden="true" /><span>{{ item.kb.name }}</span><small>{{ item.count }}</small></button>
        <p v-if="!libraries.length && data.loadStates.knowledge === 'loaded'" class="navigation-empty">在这里整理你的知识</p>
      </nav>
    </aside>
    <aside v-if="!selected" v-show="!narrow || mobileDirectory" :id="`${paneId}-directory`" ref="directoryPanel" class="home-directory" :class="{ collapsed: !directoryExpanded }" :style="{ width: directoryExpanded ? `${directoryWidth}px` : '44px' }" :role="narrow ? 'dialog' : undefined" :aria-modal="narrow ? true : undefined" aria-label="全部知识库目录" @keydown="trapNavigation">
      <header class="directory-header"><button type="button" class="pane-toggle icon-button" :aria-label="directoryExpanded ? '收起知识库目录' : '展开知识库目录'" :aria-expanded="directoryExpanded" @click="toggleDirectory"><i class="ri-side-bar-line" aria-hidden="true" /></button><strong v-if="directoryExpanded">全部目录</strong><button v-if="directoryExpanded" type="button" class="icon-button" :disabled="!catalog.length || !!keyword" :aria-label="allExpanded ? '折叠全部目录' : '展开全部目录'" :title="allExpanded ? '折叠全部目录' : '展开全部目录'" @click="toggleAll"><i :class="allExpanded ? 'ri-collapse-diagonal-line' : 'ri-expand-diagonal-line'" aria-hidden="true" /></button></header>
      <div v-show="directoryExpanded" class="all-tree">
        <PaneResizeHandle v-if="!narrow" :storage-key="`wao:directory-width:${session.currentUser.id}`" label="知识库目录宽度" :default-width="262" :max="520" @resize="directoryWidth = $event" />
        <p v-if="keyword" class="tree-search-hint">“{{ query.trim() }}” 的匹配目录</p>
        <section v-for="item in treeGroups" :key="item.kb.id" class="tree-library">
          <button type="button" class="tree-library-title" :title="item.kb.name" @click="open(item.kb.id)"><i :class="item.kb.icon" aria-hidden="true" /><span>{{ item.kb.name }}</span><i class="ri-arrow-right-s-line" aria-hidden="true" /></button>
          <KnowledgeDirectoryTree :nodes="item.rows" :expanded="item.expanded" :label="`${item.kb.name}的目录`" :searching="!!keyword" :icon-of="documentIcon" @toggle="(key, expanded) => toggleNode(item.kb.id, key, expanded)" @open="open(item.kb.id, $event)" />
        </section>
        <p v-if="!treeGroups.length && keyword" class="navigation-empty">没有匹配的目录</p>
      </div>
    </aside>
    <main class="workspace-content" :inert="mobileNavigationOpen || undefined">
      <KbDetailView v-if="selected" :navigation-hidden="mobileNavigationOpen" @focus-change="documentFocus = $event" />
      <section v-else class="knowledge-home">
        <div class="home-inner">
          <header class="home-header">
            <div class="home-title"><span class="home-title-icon" aria-hidden="true"><i class="ri-book-open-line" /></span><div><h1>知识库</h1><p>{{ libraries.length }} 个知识库<span aria-hidden="true"> · </span>{{ totalDocuments }} 篇文档</p></div><WaoButton class="home-create" @click="startCreate"><i class="ri-add-line" aria-hidden="true" />新增知识库</WaoButton></div>
            <div class="home-discovery">
              <label class="home-search"><i class="ri-search-line" aria-hidden="true" /><input ref="searchInput" v-model="query" type="search" aria-label="搜索知识库、文件夹或文档" aria-keyshortcuts="Control+j Meta+j" placeholder="搜索知识库、文件夹或文档" maxlength="200" /><kbd aria-hidden="true">Ctrl J</kbd></label>
              <div class="home-views" role="group" aria-label="内容展示方式"><button type="button" :class="{ active: view === 'directory' }" :aria-pressed="view === 'directory'" @click="view = 'directory'"><i class="ri-list-check" aria-hidden="true" />全部内容</button><button type="button" :class="{ active: view === 'recent' }" :aria-pressed="view === 'recent'" @click="view = 'recent'"><i class="ri-time-line" aria-hidden="true" />最近更新</button></div>
            </div>
            <p v-if="keyword" class="search-summary" role="status">找到 {{ index.length }} 个知识库中的 {{ resultCount }} 篇文档<button type="button" @click="clearSearch">清除搜索</button></p>
          </header>
          <WaoEmptyState v-if="(data.loadStates.knowledge === 'loading' || data.loadStates.knowledge === 'idle') && !libraries.length" title="正在读取知识库…" icon="ri-loader-4-line" role="status" />
          <WaoEmptyState v-else-if="data.loadStates.knowledge === 'error'" title="知识库读取失败" :description="data.recoveringDomains?.has('knowledge') ? '连接暂时不稳定，正在自动重新读取…' : libraries.length ? '以下为上次读取的目录，请重试获取最新内容。' : '请检查连接后重试。'" icon="ri-error-warning-line" role="alert"><template v-if="!data.recoveringDomains?.has('knowledge')" #actions><WaoButton @click="data.retryDomain('knowledge')">重新读取知识库</WaoButton></template></WaoEmptyState>
          <WaoEmptyState v-else-if="!index.length" :title="keyword ? '没有匹配的内容' : '还没有知识库'" :description="keyword ? '换个关键词，或清除搜索查看全部内容。' : '创建一个知识库，开始整理文档。'"><template #actions><WaoButton v-if="keyword" @click="clearSearch">清除搜索</WaoButton><WaoButton v-else @click="startCreate">新增知识库</WaoButton></template></WaoEmptyState>
          <template v-if="view === 'directory'">
            <section v-for="item in index" :key="item.kb.id" class="index-library">
              <header class="index-library-header"><button type="button" class="library-title" @click="open(item.kb.id)"><i :class="item.kb.icon" aria-hidden="true" /><span>{{ item.kb.name }}</span><i class="ri-arrow-right-s-line" aria-hidden="true" /></button><span class="library-count">{{ item.count }} 篇文档</span></header>
              <p v-if="item.kb.desc" class="library-description">{{ item.kb.desc }}</p>
              <div v-for="node in visibleRows(item.kb.id, item.rows)" :key="node.key" class="index-row" :class="{ 'index-folder': node.kind === 'folder' }" :style="{ '--index-depth': Math.min(node.depth, 8) }">
                <button v-if="node.kind === 'doc'" type="button" :title="node.title" @click="open(item.kb.id, node.id)"><i :class="documentIcon(node.id)" aria-hidden="true" /><span class="index-title">{{ node.title }}</span><span class="row-leader" aria-hidden="true" /><time v-if="documentTime(node.id)" :title="absoluteTime(documentTime(node.id))">{{ relativeTime(documentTime(node.id), now) }}</time></button>
                <span v-else class="index-folder-label" :title="node.title"><i class="ri-folder-line" aria-hidden="true" /><span class="index-title">{{ node.title }}</span></span>
              </div>
              <WaoButton v-if="item.rows.length > (rowLimit[item.kb.id] ?? 80)" variant="ghost" class="show-more" @click="showMore(item.kb.id)">显示更多内容（还有 {{ item.rows.length - (rowLimit[item.kb.id] ?? 80) }} 项）<i class="ri-arrow-down-s-line" aria-hidden="true" /></WaoButton>
              <p v-if="!item.rows.length && data.loadStates.knowledge === 'loaded'" class="empty">暂无文档，<button type="button" @click="open(item.kb.id)">打开知识库开始整理</button></p>
            </section>
          </template>
          <section v-else-if="index.length" class="recent-documents" aria-label="最近更新的文档">
            <div v-for="item in recentDocuments.slice(0, recentLimit)" :key="`${item.kb.id}:${item.node.id}`" class="recent-row"><button type="button" :title="item.node.title" @click="open(item.kb.id, item.node.id)"><i class="recent-icon" :class="documentIcon(item.node.id)" aria-hidden="true" /><span class="recent-main"><span class="index-title">{{ item.node.title }}</span><small>{{ item.kb.name }}</small></span><time v-if="item.updated" :title="absoluteTime(item.updated)">{{ relativeTime(item.updated, now) }}</time></button></div>
            <WaoButton v-if="recentDocuments.length > recentLimit" variant="ghost" class="show-more" @click="recentLimit += 50">显示更多文档<i class="ri-arrow-down-s-line" aria-hidden="true" /></WaoButton>
            <p v-if="!recentDocuments.length" class="empty">{{ keyword ? '没有匹配的文档，试试全部内容。' : '还没有文档，从知识库开始记录吧。' }}</p>
          </section>
        </div>
      </section>
    </main>
    <SettingsDialogShell v-if="creating" title="新增知识库" width="520px" height="auto" :close-disabled="saving" @close="creating = false">
      <form class="create-form" @submit.prevent="create"><WaoField label="名称" required v-slot="field"><input :id="field.id" v-model="name" :disabled="saving" required autofocus /></WaoField><WaoField label="简介" v-slot="field"><textarea :id="field.id" v-model="description" :disabled="saving" rows="2" /></WaoField><KnowledgeIconPicker v-model="icon" :disabled="saving" /><p v-if="error" role="alert">{{ error }}</p><WaoButton type="submit" :loading="saving" :disabled="!name.trim()">{{ saving ? '创建中…' : '创建' }}</WaoButton></form>
    </SettingsDialogShell>
  </div>
</template>

<style scoped>
/* AppLayout and TabWindow provide the available height, including the real footer. */
.knowledge-workspace { position: relative; display: flex; flex: 1; width: 100%; min-width: 0; min-height: 0; overflow: hidden; color: var(--wao-text); background: var(--wao-bg-card); }
button { color: inherit; font: inherit; cursor: pointer; }
button:focus-visible, input:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
button:disabled { cursor: not-allowed; opacity: .5; }
.libraries { display: flex; flex-direction: column; flex: 0 0 208px; width: 208px; border-right: 1px solid var(--wao-line); background: var(--wao-bg-sub); overflow: hidden; }
.libraries.collapsed { flex-basis: 44px; width: 44px; }
.libraries header, .directory-header { display: flex; align-items: center; gap: 2px; min-height: 52px; padding: 0 var(--wao-space-1); flex-shrink: 0; }
.libraries header strong, .directory-header strong { flex: 1; font-size: var(--wao-font-body); }
.libraries.collapsed header, .home-directory.collapsed .directory-header { padding: 0; }
.icon-button { display: inline-flex; justify-content: center; align-items: center; flex: 0 0 32px; min-width: 32px; min-height: 32px; padding: 0; border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-text-3); }
.icon-button:hover:not(:disabled) { color: var(--wao-primary-text); background: var(--wao-primary-soft); }
.pane-toggle { min-width: 36px; min-height: 36px; }
.collapsed .pane-toggle { min-width: 44px; min-height: 44px; }
.library-list { overflow: auto; display: flex; flex-direction: column; gap: 2px; padding: 0 var(--wao-space-1) var(--wao-space-2); }
.library-list > button { display: flex; align-items: center; gap: var(--wao-space-1); text-align: left; flex-shrink: 0; min-height: 36px; padding: 6px var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: transparent; }
.library-list > button:hover { background: var(--wao-bg-hover); }
.library-list > button.active { background: var(--wao-primary-soft); color: var(--wao-primary-text); font-weight: var(--wao-weight-heading); }
.library-list > button > i { flex-shrink: 0; }
.library-list > button span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.library-list small, .navigation-label, .navigation-empty, .tree-search-hint { color: var(--wao-text-3); font-size: var(--wao-font-caption); font-weight: 400; }
.library-list small { font-variant-numeric: tabular-nums; }
.navigation-label { display: flex; justify-content: space-between; margin: var(--wao-space-3) var(--wao-space-1) var(--wao-space-1); }
.navigation-empty, .tree-search-hint { padding: var(--wao-space-1); overflow-wrap: anywhere; }
.home-directory { position: relative; display: flex; flex-direction: column; flex-shrink: 0; min-width: 0; border-right: 1px solid var(--wao-line); background: var(--wao-bg-card); }
.all-tree { overflow: auto; min-height: 0; padding: 0 var(--wao-space-1) var(--wao-space-2); }
.tree-library + .tree-library { margin-top: var(--wao-space-2); }
.tree-library-title { display: flex; align-items: center; gap: var(--wao-space-1); width: 100%; min-height: 36px; padding: 6px var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: transparent; text-align: left; font-weight: var(--wao-weight-heading); }
.tree-library-title:hover { background: var(--wao-bg-hover); }
.tree-library-title span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workspace-content { flex: 1; min-width: 0; min-height: 0; overflow: hidden; container: kb-workspace / inline-size; }
.workspace-content :deep(.kb-detail) { height: 100%; }
.knowledge-home { height: 100%; overflow: auto; padding: var(--wao-space-4) clamp(20px, 5vw, 72px) var(--wao-space-4); }
.home-inner { max-width: 1080px; margin-inline: auto; }
.home-title { display: flex; gap: var(--wao-space-2); align-items: center; min-width: 0; }
h1 { font-size: var(--wao-font-title); font-weight: var(--wao-weight-heading); margin: 0; }
.home-title p { margin: 4px 0 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.home-title-icon { display: inline-flex; width: 44px; height: 44px; align-items: center; justify-content: center; color: var(--wao-primary-text); background: var(--wao-primary-soft); border-radius: var(--wao-radius-card); font-size: var(--wao-font-title); }
.home-create { margin-left: auto; flex-shrink: 0; }
.home-discovery { display: flex; flex-wrap: wrap; align-items: center; gap: var(--wao-space-2); margin-top: var(--wao-space-3); padding-bottom: var(--wao-space-2); border-bottom: 1px solid var(--wao-line); }
.home-search { flex: 1 1 260px; min-width: 0; display: flex; align-items: center; gap: var(--wao-space-1); min-height: var(--wao-control-height); padding: 0 12px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-3); }
.home-search:focus-within { border-color: var(--wao-primary); }
.home-search input { flex: 1; width: 100%; min-width: 0; min-height: 38px; padding: 0; border: 0; outline: 0; background: transparent; color: var(--wao-text); font: inherit; font-size: var(--wao-font-body); }
.home-search kbd { flex-shrink: 0; color: var(--wao-text-3); font: inherit; font-size: var(--wao-font-caption); white-space: nowrap; }
.home-views { display: flex; gap: 4px; }
.home-views button { display: inline-flex; align-items: center; gap: 6px; min-height: 36px; padding: 6px 10px; border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-text-3); white-space: nowrap; }
.home-views button:hover { background: var(--wao-bg-hover); }
.home-views button.active { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.search-summary { display: flex; flex-wrap: wrap; align-items: center; gap: var(--wao-space-1); margin: var(--wao-space-2) 0 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.search-summary button, .empty button { padding: 0; border: 0; background: transparent; color: var(--wao-primary-text); text-decoration: underline; text-underline-offset: 3px; }
.index-library { margin-top: var(--wao-space-3); padding-bottom: var(--wao-space-2); }
.index-library + .index-library { border-top: 1px solid var(--wao-line); padding-top: var(--wao-space-3); }
.index-library-header { display: flex; align-items: center; gap: var(--wao-space-2); min-width: 0; margin-bottom: var(--wao-space-1); }
.library-title { display: inline-flex; gap: var(--wao-space-1); align-items: center; min-width: 0; max-width: 100%; padding: 0; border: 0; background: transparent; min-height: 36px; text-align: left; font-size: var(--wao-font-section); font-weight: var(--wao-weight-heading); }
.library-title:hover { color: var(--wao-primary-text); }
.library-title > span { min-width: 0; overflow-wrap: anywhere; }
.library-title > i { flex-shrink: 0; }
.library-count { flex-shrink: 0; margin-left: auto; color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.library-description { margin: 0 0 var(--wao-space-2); color: var(--wao-text-3); font-size: var(--wao-font-help); overflow-wrap: anywhere; }
.index-row { padding-inline-start: calc(var(--index-depth) * 20px); min-width: 0; }
.index-row button, .index-folder-label { display: flex; gap: var(--wao-space-1); align-items: center; width: 100%; min-width: 0; min-height: 38px; padding: 6px var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: transparent; text-align: left; }
.index-row button:hover, .recent-row button:hover { color: var(--wao-primary-text); background: var(--wao-bg-hover); }
.index-row i { flex: 0 0 auto; color: var(--wao-text-3); }
.index-folder-label { color: var(--wao-text-3); font-weight: var(--wao-weight-heading); }
.index-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-leader { align-self: center; flex: 1; min-width: 16px; border-bottom: 1px dotted var(--wao-line-strong); margin-inline: var(--wao-space-1); }
time { color: var(--wao-text-3); font-size: var(--wao-font-caption); flex-shrink: 0; white-space: nowrap; font-variant-numeric: tabular-nums; }
.empty { margin: var(--wao-space-1) 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.show-more { margin-top: var(--wao-space-1); }
.recent-documents { margin-top: var(--wao-space-2); }
.recent-row + .recent-row { border-top: 1px solid var(--wao-line); }
.recent-row button { display: flex; gap: var(--wao-space-2); align-items: center; width: 100%; min-width: 0; min-height: 64px; padding: 10px var(--wao-space-1); text-align: left; border: 0; border-radius: var(--wao-radius-control); background: transparent; }
.recent-icon { color: var(--wao-text-3); font-size: var(--wao-font-section); }
.recent-main { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; }
.recent-main small { color: var(--wao-text-3); font-size: var(--wao-font-caption); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.create-form { padding: var(--wao-space-3); display: grid; gap: var(--wao-space-2); }
.create-form > [role='alert'] { color: var(--wao-danger-strong); margin: 0; font-size: var(--wao-font-help); }
.mobile-workspace-bar { display: none; }
.mobile-backdrop { position: absolute; inset: 44px 0 0; z-index: 5; padding: 0; border: 0; background: var(--wao-mask-bg); }
@container app-main (max-width: 1200px) { .knowledge-home { padding-inline: var(--wao-space-3); } .home-search kbd { display: none; } }
@container app-main (max-width: 900px) {
  .knowledge-workspace { flex-direction: column; }
  .mobile-workspace-bar { display: flex; justify-content: space-between; gap: var(--wao-space-1); flex: 0 0 44px; min-width: 0; border-bottom: 1px solid var(--wao-line); padding-inline: var(--wao-space-1); }
  .mobile-workspace-bar button { display: inline-flex; align-items: center; gap: 6px; min-width: 0; min-height: 44px; padding: 0 var(--wao-space-1); border: 0; background: transparent; }
  .mobile-workspace-bar button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mobile-workspace-bar .mobile-add { min-width: 44px; margin-left: auto; flex-shrink: 0; }
  .libraries, .home-directory { position: absolute; inset: 44px auto 0 0; width: min(300px, calc(100% - 32px)) !important; z-index: 6; box-shadow: var(--wao-shadow-pop); }
  .libraries.collapsed, .home-directory.collapsed { display: none; }
  .home-directory :deep(.pane-resize-handle) { display: none; }
  .knowledge-home { padding: var(--wao-space-3) var(--wao-space-2); }
  .home-title { flex-wrap: wrap; gap: var(--wao-space-1); }
  .home-create { padding-inline: var(--wao-space-1); }
  .home-discovery { gap: var(--wao-space-1); }
  .home-search { flex-basis: 100%; }
  .home-search input { font-size: var(--wao-font-reading); }
  .home-title-icon { display: none; }
  .index-row { padding-inline-start: calc(min(var(--index-depth), 3) * 12px); }
  .row-leader { display: none; }
  .index-row .index-title { flex: 1; }
  .index-row time { max-width: 96px; overflow: hidden; text-overflow: ellipsis; }
  .library-count { font-size: var(--wao-font-caption); }
}
@container app-main (max-width: 390px) { .recent-row button { flex-wrap: wrap; gap: var(--wao-space-1); } .recent-row time { margin-left: 24px; } }
@media (pointer: coarse) { .icon-button, .pane-toggle, .library-list > button, .home-views button, .tree-library-title, .library-title, .index-row button { min-height: 44px; } }
</style>
