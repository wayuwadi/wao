<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, toRefs, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useTaskViewMemory } from '@/composables/taskViewMemory'
import KbDetailView from './KbDetailView.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoEmptyState from '@/components/ui/WaoEmptyState.vue'
import { useBrowsingState, validSearch } from '@/composables/browsingState'
import WaoField from '@/components/ui/WaoField.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import KnowledgeIconPicker from '@/components/knowledge/KnowledgeIconPicker.vue'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'
import { useSessionStore } from '@/stores/session'
import { directoryRows, groupLevelExpansion } from '@/utils/knowledgeDirectory'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { usePageVisibility } from '@/composables/pageVisibility'
const data = useDataStore(), route = useRoute(), router = useRouter()
const memory = useTaskViewMemory('knowledge-workspace', () => ({ collapsed: false }))
const selected = computed(() => String(route.params.id || route.query.kb || ''))
const libraries = computed(() => data.kbs.filter(k => !k.notes))
const session = useSessionStore()
const directoryMemory = useTaskViewMemory('knowledge-directory', () => ({ collapsed: false }))
const directoryWidth = ref(262)
const folderOpen = ref<Record<string, boolean>>({})
const narrow = ref(false)
const pageVisible = usePageVisibility()
const mobilePane = ref<'libraries' | 'directory' | null>(null)
const mobileToolbar = ref<HTMLElement>()
const libraryPane = ref<HTMLElement>()
const directoryPane = ref<HTMLElement>()
const paneId = useId()
let navigationTrigger: HTMLElement | null = null
function restoreNavigationFocus() {
  if (!narrow.value) return
  const target = navigationTrigger?.isConnected ? navigationTrigger : mobileToolbar.value?.querySelector<HTMLElement>('button')
  target?.focus({ preventScroll: true })
}
function closeNavigation(restoreFocus = true) {
  if (!mobilePane.value) return
  mobilePane.value = null
  if (restoreFocus) void nextTick(restoreNavigationFocus)
}
const navigationDismiss = useOverlayDismiss(() => narrow.value && !!mobilePane.value, closeNavigation)
if (typeof window.matchMedia === 'function') {
  const media = window.matchMedia('(max-width: 900px)')
  narrow.value = media.matches
  const resize = (event: MediaQueryListEvent) => { closeNavigation(false); narrow.value = event.matches }
  media.addEventListener('change', resize)
  onBeforeUnmount(() => media.removeEventListener('change', resize))
}
const librariesCollapsed = computed(() => narrow.value ? mobilePane.value !== 'libraries' : memory.value.collapsed)
const directoryCollapsed = computed(() => narrow.value ? mobilePane.value !== 'directory' : directoryMemory.value.collapsed)
async function toggleNavigation(pane: 'libraries' | 'directory', event: MouseEvent) {
  if (!narrow.value) {
    if (pane === 'libraries') memory.value.collapsed = !memory.value.collapsed
    else directoryMemory.value.collapsed = !directoryMemory.value.collapsed
    return
  }
  if (mobilePane.value === pane) { closeNavigation(); return }
  navigationTrigger = event.currentTarget as HTMLElement
  mobilePane.value = pane
  await nextTick()
  const element = pane === 'libraries' ? libraryPane.value : directoryPane.value
  element?.querySelector<HTMLElement>('.pane-toggle')?.focus({ preventScroll: true })
}
function trapNavigation(event: KeyboardEvent) {
  if (!narrow.value || !mobilePane.value || !navigationDismiss.isTopmost() || event.defaultPrevented || event.key !== 'Tab') return
  const pane = mobilePane.value === 'libraries' ? libraryPane.value : directoryPane.value
  const buttons = [...(pane?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  const first = buttons[0], last = buttons[buttons.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch([() => session.account?.accountId, () => session.userId, pageVisible], () => closeNavigation(false))
watch(() => route.fullPath, () => closeNavigation())
const { query } = toRefs(useBrowsingState('knowledge-home', { query: '' }, { query: validSearch }))
const index = computed(() => {
  const search = query.value.trim().toLocaleLowerCase()
  return libraries.value.map(kb => {
    const matchesLibrary = kb.name.toLocaleLowerCase().includes(search)
    return { kb, matchesLibrary, rows: directoryRows(kb, id => data.docById(id)?.title || '未命名文档', {}, matchesLibrary ? '' : query.value) }
  }).filter(item => item.rows.length || item.matchesLibrary)
})
async function open(kb = '', doc = '') {
  const restoreFocus = narrow.value && !!mobilePane.value
  closeNavigation(false)
  await router.push({ path: '/knowledge', query: { ...(kb ? { kb } : { home: '1' }), ...(doc ? { doc } : {}) } })
  if (restoreFocus) await nextTick(restoreNavigationFocus)
}
const creating = ref(false), saving = ref(false), name = ref(''), description = ref(''), icon = ref('wao-kb-icon-notebook'), error = ref('')
async function startCreate() { closeNavigation(); await nextTick(); name.value = ''; description.value = ''; icon.value = 'wao-kb-icon-notebook'; error.value = ''; creating.value = true }
async function create() {
  if (!name.value.trim() || saving.value) return
  saving.value = true; error.value = ''
  try {
    const kb = await data.addKb({ name: name.value.trim(), desc: description.value.trim(), icon: icon.value })
    if (!kb) throw new Error('create failed')
    creating.value = false; open(kb.id)
  } catch { error.value = '创建失败，输入已保留，请重试。' }
  finally { saving.value = false }
}
</script>
<template>
  <div class="knowledge-workspace">
    <nav v-if="narrow" ref="mobileToolbar" class="mobile-toolbar" aria-label="知识库浏览">
      <button type="button" :aria-expanded="mobilePane === 'libraries'" :aria-controls="`${paneId}-libraries`" @click="toggleNavigation('libraries', $event)"><i class="ri-book-2-line" aria-hidden="true" />知识库清单</button>
      <button v-if="!selected" type="button" :aria-expanded="mobilePane === 'directory'" :aria-controls="`${paneId}-directory`" @click="toggleNavigation('directory', $event)"><i class="ri-folder-line" aria-hidden="true" />全部目录</button>
      <button type="button" class="mobile-add" aria-label="新增知识库" @click="startCreate"><i class="ri-add-line" aria-hidden="true" /></button>
    </nav>
    <div v-if="narrow && mobilePane" class="navigation-mask" aria-hidden="true" @click="navigationDismiss.isTopmost() && closeNavigation()" />
    <aside v-show="!narrow || mobilePane === 'libraries'" :id="`${paneId}-libraries`" ref="libraryPane" class="libraries" :class="{ collapsed: librariesCollapsed }" :role="narrow ? 'dialog' : undefined" :aria-modal="narrow ? true : undefined" aria-label="知识库清单" @keydown="trapNavigation">
      <header><strong v-if="!librariesCollapsed">知识库</strong><button v-if="!librariesCollapsed && !narrow" class="library-add" type="button" aria-label="新增知识库" title="新增知识库" @click="startCreate"><i class="ri-add-line" /></button><button class="pane-toggle" :aria-label="librariesCollapsed ? '展开知识库清单' : '收起知识库清单'" :aria-expanded="!librariesCollapsed" @click="toggleNavigation('libraries', $event)"><i :class="narrow ? 'ri-close-line' : 'ri-side-bar-line'" /></button></header>
      <div v-show="!librariesCollapsed" class="library-list">
        <button :class="{ active: !selected }" @click="open()"><i class="ri-home-4-line" />全部内容</button>
        <button v-for="kb in libraries" :key="kb.id" :title="kb.name" :class="{ active: selected === kb.id }" @click="open(kb.id)"><i :class="kb.icon" /><span>{{ kb.name }}</span></button>
      </div>
    </aside>
    <aside v-if="!selected" v-show="!narrow || mobilePane === 'directory'" :id="`${paneId}-directory`" ref="directoryPane" class="home-directory" :class="{ collapsed: directoryCollapsed }" :style="{ width: directoryCollapsed ? '44px' : `${directoryWidth}px` }" :role="narrow ? 'dialog' : undefined" :aria-modal="narrow ? true : undefined" aria-label="全部目录" @keydown="trapNavigation">
      <button class="pane-toggle" :aria-label="directoryCollapsed ? '展开知识库目录' : '收起知识库目录'" :aria-expanded="!directoryCollapsed" @click="toggleNavigation('directory', $event)"><i :class="narrow ? 'ri-close-line' : 'ri-side-bar-line'" /></button>
      <div v-show="!directoryCollapsed" class="all-tree">
        <PaneResizeHandle v-if="!narrow" :storage-key="`wao:directory-width:${session.currentUser.id}`" label="知识库目录宽度" :default-width="262" :max="520" @resize="directoryWidth = $event" />
        <strong>全部目录</strong>
        <section v-for="kb in libraries" :key="kb.id">
          <button class="library-title" @click="open(kb.id)">{{ kb.name }}</button>
          <button v-for="node in directoryRows(kb, id => data.docById(id)?.title || '未命名文档', { ...groupLevelExpansion(kb), ...folderOpen }, '')" :key="node.key" class="tree-node" :style="{ paddingInlineStart: `${node.depth * 16}px` }" @click="node.kind === 'doc' ? open(kb.id, node.id) : folderOpen[node.key] = !(folderOpen[node.key] ?? groupLevelExpansion(kb)[node.key] ?? true)"><i :class="node.kind === 'doc' ? 'ri-file-text-line' : 'ri-folder-line'" /><span>{{ node.title }}</span></button>
        </section>
      </div>
    </aside>
    <main class="workspace-content" :inert="narrow && mobilePane ? true : undefined">
      <KbDetailView v-if="selected" :navigation-hidden="!!mobilePane" />
      <section v-else class="knowledge-home">
        <header><h1>知识库</h1><p>全部内容</p><WaoField label="查找文件" v-slot="field"><input :id="field.id" v-model="query" type="search" placeholder="搜索知识库、文件夹或文档" /></WaoField></header>
        <WaoEmptyState v-if="data.loadStates.knowledge === 'loading' || data.loadStates.knowledge === 'idle'" title="正在读取知识库…" icon="ri-loader-4-line" role="status" />
        <WaoEmptyState v-else-if="data.loadStates.knowledge === 'error'" title="知识库读取失败" :description="libraries.length ? '以下为上次读取的目录，请重试获取最新内容。' : '请检查连接后重试。'" icon="ri-error-warning-line" role="alert">
          <template #actions><WaoButton @click="data.retryDomain('knowledge')">重新读取知识库</WaoButton></template>
        </WaoEmptyState>
        <WaoEmptyState v-else-if="!index.length" :title="query.trim() ? '没有匹配的内容' : '还没有知识库'" :description="query.trim() ? '换个关键词，或清除搜索查看全部内容。' : '创建一个知识库，开始整理文档。'">
          <template #actions><WaoButton v-if="query.trim()" @click="query = ''">清除搜索</WaoButton><WaoButton v-else @click="startCreate">新增知识库</WaoButton></template>
        </WaoEmptyState>
        <section v-for="item in index" :key="item.kb.id" class="index-library">
          <button class="library-title" @click="open(item.kb.id)"><i :class="item.kb.icon" />{{ item.kb.name }}<i class="ri-arrow-right-s-line" /></button>
          <div v-for="node in item.rows" :key="node.key" class="index-row" :style="{ paddingInlineStart: `${node.depth * 20}px` }">
            <button v-if="node.kind === 'doc'" @click="open(item.kb.id, node.id)"><i class="ri-file-text-line" />{{ node.title }}</button>
            <span v-else><i class="ri-folder-line" />{{ node.title }}</span>
          </div>
          <p v-if="!item.rows.length && data.loadStates.knowledge === 'loaded'" class="empty">暂无文档</p>
        </section>
      </section>
    </main>
    <SettingsDialogShell v-if="creating" title="新增知识库" width="520px" height="auto" :close-disabled="saving" @close="creating = false">
      <form class="create-form" @submit.prevent="create">
        <WaoField label="名称" required v-slot="field"><input :id="field.id" v-model="name" :disabled="saving" required autofocus /></WaoField>
        <WaoField label="简介" v-slot="field"><textarea :id="field.id" v-model="description" :disabled="saving" rows="2" /></WaoField>
        <KnowledgeIconPicker v-model="icon" :disabled="saving" />
        <p v-if="error" role="alert">{{ error }}</p>
        <WaoButton type="submit" :disabled="saving || !name.trim()">{{ saving ? '创建中…' : '创建' }}</WaoButton>
      </form>
    </SettingsDialogShell>
  </div>
</template>
<style scoped>
.knowledge-workspace { position:relative; display:flex; height:calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h)); min-width:0; overflow:hidden; color:var(--wao-text); background:var(--wao-bg-card); }
.libraries { flex:0 0 208px; width:208px; border-right:1px solid var(--wao-line); background:var(--wao-bg-sub); overflow:auto; }
.libraries.collapsed { flex-basis:44px; width:44px; }
.libraries header { display:flex; align-items:center; padding:var(--wao-space-1); min-height:52px; }
.libraries header strong { flex:1; }
.library-add { min-width:44px; min-height:44px; border:0; background:transparent; color:var(--wao-text-3); }
.library-add:hover,.library-add:focus-visible { color:var(--wao-primary-text); background:var(--wao-primary-soft); }
.libraries.collapsed header { padding:0; }
button { color:inherit; font:inherit; cursor:pointer; }
.pane-toggle { border:0; background:transparent; min-width:44px; min-height:44px; }
.library-list { display:grid; gap:var(--wao-space-1); padding:var(--wao-space-1); }
.library-list > button { display:flex; align-items:center; gap:var(--wao-space-1); text-align:left; min-height:44px; padding:var(--wao-space-1); border:0; border-radius:var(--wao-radius-control); background:transparent; }
.library-list > button.active { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.library-list span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.home-directory { position:relative; flex-shrink:0; border-right:1px solid var(--wao-line); overflow:auto; }
.all-tree { padding:var(--wao-space-1); }
.tree-node { display:flex; gap:var(--wao-space-1); align-items:center; width:100%; min-height:36px; border:0; background:transparent; text-align:left; }
.tree-node span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.workspace-content { flex:1; min-width:0; overflow:hidden; }
.workspace-content :deep(.kb-detail) { height:100%; }
.knowledge-home { height:100%; overflow:auto; padding:var(--wao-space-3); }
h1 { font-size:var(--wao-font-title); margin:0; }
.knowledge-home header { max-width:720px; }
.index-library { margin-top:var(--wao-space-3); border-top:1px solid var(--wao-line); padding-top:var(--wao-space-2); }
.library-title, .index-row button { display:flex; gap:var(--wao-space-1); align-items:center; border:0; background:transparent; min-height:44px; text-align:left; overflow-wrap:anywhere; }
.library-title { font-size:var(--wao-font-section); font-weight:600; }
.index-row span { display:flex; gap:var(--wao-space-1); align-items:center; min-height:36px; color:var(--wao-text-3); overflow-wrap:anywhere; }
.empty { color:var(--wao-text-3); }
.create-form { padding:var(--wao-space-3); display:grid; gap:var(--wao-space-2); }
.mobile-toolbar { display:flex; align-items:center; gap:var(--wao-space-1); flex:0 0 52px; min-width:0; padding-inline:var(--wao-space-1); border-bottom:1px solid var(--wao-line); box-sizing:border-box; }
.mobile-toolbar button { display:flex; align-items:center; justify-content:center; gap:var(--wao-space-1); min-height:44px; min-width:44px; padding:var(--wao-space-1); border:0; background:transparent; white-space:nowrap; }
.mobile-toolbar .mobile-add { margin-left:auto; }
.navigation-mask { position:absolute; inset:52px 0 0; z-index:5; background:var(--wao-mask-bg); }
button:focus-visible { outline:2px solid var(--wao-primary); outline-offset:-2px; }
@media(max-width:900px) {
  .knowledge-workspace { flex-direction:column; }
  .libraries,.home-directory { position:absolute; inset:52px auto 0 0; width:min(320px, calc(100% - 32px)) !important; overflow:auto; z-index:6; background:var(--wao-bg-card); box-shadow:var(--wao-shadow-pop); }
  .workspace-content { width:100%; min-height:0; }
  .tree-node { min-height:44px; }
  .knowledge-home { padding:var(--wao-space-2); }
}
</style>

