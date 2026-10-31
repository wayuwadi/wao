<script setup lang="ts">
import { useIsMobile } from '@/composables/viewport'
import { useMobileVisualViewport } from '@/composables/mobileVisualViewport'
const isMobile = useIsMobile()
const { keyboardOpen } = useMobileVisualViewport()
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TabWorkspace from '@/components/tabs/TabWorkspace.vue'
import NavigationContext from '@/components/nav/NavigationContext.vue'
import SideNav from '@/components/nav/SideNav.vue'
import WaoLogo from '@/components/WaoLogo.vue'
import DataLoadStatus from '@/components/ui/DataLoadStatus.vue'
import { pageDataDomains } from '@/utils/pageDataDomains'
import MobileShellMenu from '@/components/nav/MobileShellMenu.vue'
import TabBar from '@/components/tabs/TabBar.vue'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
import QuickNotePopover from '@/components/overlays/QuickNotePopover.vue'
import GlobalToolsMenu from '@/components/nav/GlobalToolsMenu.vue'
import QuietCompanionHost from '@/components/companions/QuietCompanionHost.vue'
import NewProjectModal from '@/components/overlays/NewProjectModal.vue'
import GlobalSearch from '@/components/overlays/GlobalSearch.vue'
import Celebration from '@/components/overlays/CelebrationOverlay.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import ShortcutsHelp from '@/components/overlays/ShortcutsHelp.vue'
import AiAssistantPanel from '@/components/assistant/AiAssistantPanel.vue'
import FloatingChat from '@/components/chat/FloatingChat.vue'
import PageSectionNav from '@/components/navigation/PageSectionNav.vue'
import ReleaseHighlightsHost from '@/components/release/ReleaseHighlightsHost.vue'
import { externalAppItemId, RETIRED_MODULE_IDS, tabFromRoute } from '@/config/nav'
import { useNavStore } from '@/stores/nav'
import { useTabsStore } from '@/stores/tabs'
import { useFloatingTabsStore } from '@/stores/floatingTabs'
import { useUiStore } from '@/stores/ui'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { useQuickLinks } from '@/composables/quickLinks'
import { useShortcuts } from '@/composables/shortcuts'
import { useFunctionHealth } from '@/composables/functionHealth'
import {
  hasDesktopToolbar,
  hasLegacyDesktopToolbar,
  mountDesktopToolbar,
} from '@/utils/desktopToolbar'
import type { ModuleId } from '@/types'

const desktopToolbar = hasDesktopToolbar()
const legacyDesktopToolbar = hasLegacyDesktopToolbar()
const toolbarRef = ref<HTMLElement | null>(null)
let stopDesktopToolbar: (() => void) | undefined
onMounted(() => {
  if (toolbarRef.value) stopDesktopToolbar = mountDesktopToolbar(toolbarRef.value)
})
onBeforeUnmount(() => stopDesktopToolbar?.())
const route = useRoute()
const mobileMenuOpen = ref(false)
const mobileMenuButton = ref<HTMLButtonElement>()
async function closeMobileMenu() {
  mobileMenuOpen.value = false
  await nextTick()
  if (isMobile.value) mobileMenuButton.value?.focus()
}
watch(isMobile, () => { mobileMenuOpen.value = false })
watch(() => route.fullPath, () => { mobileMenuOpen.value = false })
const nav = useNavStore()
const tabs = useTabsStore()
const floatingTabs = useFloatingTabsStore()
const ui = useUiStore()
watch(() => [ui.modal, ui.searchOpen, ui.helpOpen, ui.openTaskId, ui.assistantOpen], () => {
  if (ui.modal || ui.searchOpen || ui.helpOpen || ui.openTaskId || ui.assistantOpen) mobileMenuOpen.value = false
})
const data = useDataStore()
const preferences = usePreferenceStore()
const session = useSessionStore()
const quickLinks = useQuickLinks()
const functionHealth = useFunctionHealth()
const TRACKED_FEATURES = new Set([
  'home',
  'ai-sessions',
  'chatgpt',
  'navigation',
  'changelog',
  'updates',
  'projects',
  'ideas',
  'decisions',
  'todo',
  'calendar',
  'workday-rhythm',
  'action-plans',
  'appearance',
  'food',
  'workspaces',
  'weekly-report',
  'life-review',
  'knowledge',
  'memory',
  'english-learning',
  'vocabulary',
  'skills',
  'skill-connections',
  'capabilities',
  'drawings',
  'files',
  'automation',
  'text-polish',
  'data-center',
  'dashboard',
  'system-reports',
  'request-monitoring',
  'devices',
  'vault',
  'games',
  'tianren',
  'settings',
].filter((id) => !RETIRED_MODULE_IDS.has(id as ModuleId)))
const contentRef = ref<HTMLElement | null>(null)
const pageBodyRef = ref<HTMLElement | null>(null)

const visibleDataDomains = computed(() => pageDataDomains(String(route.meta.module || ''), !!ui.openTaskId, ui.searchOpen))

// 登录成员切换时先清掉上一名成员的内存偏好，再按当前会话重新读取。
watch(() => session.currentUser.id, (memberId) => {
  preferences.prepareForMember(memberId)
  if (memberId) void preferences.load()
}, { immediate: true })

useShortcuts()

const crumbA = computed(() => {
  if (route.meta.tabKind === 'external') {
    return nav.externalAppById(String(route.params.appId))?.label ?? '外部应用'
  }
  if (route.meta.tabKind === 'quick-link') {
    return (
      quickLinks.links.value.find((link) => link.id === String(route.params.linkId))?.label ??
      '快捷链接'
    )
  }
  const mod = nav.itemMeta(route.meta.module as ModuleId)
  return mod?.label ?? '首页'
})
const crumbB = computed(() => {
  const kind = route.meta.tabKind as string
  if (kind === 'project' || kind === 'kb') return tabs.activeTab()?.label ?? ''
  return ''
})

/** 路由变化即同步页签（覆盖点击导航 / 直接 URL / 前进后退）。 */
watch(
  () => route.fullPath,
  () => {
    const tab = tabFromRoute(route)
    if (tab) {
      tab.path = route.fullPath
      if (route.meta.tabKind === 'module' && route.meta.module) {
        const meta = nav.itemMeta(route.meta.module as ModuleId)
        if (meta) {
          tab.label = meta.label
          tab.icon = meta.icon
        }
      }
      if (route.meta.tabKind === 'external') {
        const app = nav.externalAppById(String(route.params.appId))
        if (app) {
          tab.label = app.label
          tab.icon = app.icon
          tab.labelPlaceholder = false
        }
      }
      if (route.meta.tabKind === 'quick-link') {
        const link = quickLinks.links.value.find((item) => item.id === String(route.params.linkId))
        if (link) {
          tab.label = link.label
          tab.icon = link.icon
          tab.labelPlaceholder = false
        }
      }
      tabs.open(tab)
    }
  },
  { immediate: true },
)

watch(
  () => String(route.meta.module || ''),
  (featureId, previous) => {
    if (TRACKED_FEATURES.has(featureId) && featureId !== previous)
      void functionHealth.record(featureId, 'PAGE')
  },
  { immediate: true },
)

/** 大体量文档只在会展示文档的路由按需读取；任务抽屉与搜索各自在打开时触发。 */
function needsDocs() {
  return (
    route.name === 'project' ||
    route.name === 'kb' ||
    route.meta.module === 'knowledge' ||
    route.meta.module === 'notes' ||
    floatingTabs.keys.some(
      (key) =>
        key.startsWith('proj:') || key.startsWith('kb:') || key === 'knowledge' || key === 'notes',
    ) ||
    !!ui.openTaskId ||
    ui.searchOpen
  )
}
function ensureVisibleHeavyDomains() {
  if (needsDocs()) void data.ensureDocsLoaded()
}
watch(() => [route.name, route.meta.module, ...floatingTabs.keys], ensureVisibleHeavyDomains, {
  immediate: true,
})

watch(
  () => nav.builtinMenus.map((menu) => `${menu.id}|${menu.label}|${menu.icon}`).join('\n'),
  () => {
    for (const menu of nav.builtinMenus) {
      const meta = nav.itemMeta(menu.id)
      if (meta) tabs.setMeta(menu.id, { label: meta.label, icon: meta.icon })
    }
  },
  { immediate: true },
)

watch(
  () => nav.externalApps.map((app) => `${app.id}|${app.label}|${app.icon}`).join('\n'),
  () => {
    for (const app of nav.externalApps) {
      tabs.setMeta(externalAppItemId(app.id), { label: app.label, icon: app.icon })
    }
  },
  { immediate: true },
)

watch(
  () => quickLinks.links.value.map((link) => `${link.id}|${link.label}|${link.icon}`).join('\n'),
  () => {
    for (const link of quickLinks.links.value) {
      tabs.setMeta(`quick-link:${link.id}`, { label: link.label, icon: link.icon })
    }
  },
  { immediate: true },
)

function newTask() {
  ui.openModal('task')
}
/** 快速小记原地打开，保留当前页签、滚动和任务上下文。 */
function newNote() {
  ui.openModal('note')
}
function openSearch() {
  ui.openSearch()
}

function onKeydown(e: KeyboardEvent) {
  if (e.defaultPrevented) return
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    ui.openSearch()
  } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'j') {
    e.preventDefault()
    ui.toggleAssistant()
  } else if (e.key === 'Escape' && ui.modal !== 'note' && ui.modal !== 'task') {
    // 逐层关闭：聊天 → 助手 → 庆祝 → 帮助 → 搜索 → 弹窗
    if (ui.chatOpen) {
      e.preventDefault()
      ui.closeChat()
    } else if (ui.assistantOpen) ui.closeAssistant()
    else if (ui.celebrating) ui.stopCelebrate()
    else if (ui.helpOpen) ui.closeHelp()
    else if (ui.searchOpen) ui.closeSearch()
    else if (ui.modal) ui.closeModal()
  }
}
function openLocatedTask() {
  const id = typeof route.query.task === 'string' ? route.query.task : ''
  if (id && data.taskById(id)) ui.openTask(id)
}
watch(() => route.query.task, openLocatedTask)

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  void session.refreshDirectory()
  void preferences.load()
  // 接入真实 /api；任务域会独立暴露加载 / 失败状态，不用 fixtures 冒充用户数据。
  await data.hydrate()
  void import('@/utils/autoNoteEnrichment').then(({ resumeAutoNoteEnrichment }) =>
    resumeAutoNoteEnrichment(session.currentUser.id),
  )
  openLocatedTask()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div
    class="shell"
    :class="{ 'desktop-shell': desktopToolbar, 'legacy-desktop-shell': legacyDesktopToolbar, 'mobile-keyboard-open': keyboardOpen }"
    :data-control-style="preferences.effectiveControlStyle"
    :data-page-layout="preferences.pageLayoutStyle"
  >
    <MobileShellMenu :open="isMobile && mobileMenuOpen" @close="closeMobileMenu" />
    <Teleport defer to="#mobile-navigation-slot" :disabled="!isMobile">
      <SideNav @open-search="openSearch" @navigate="mobileMenuOpen = false" />
    </Teleport>

    <main class="main" :inert="isMobile && mobileMenuOpen">
      <Teleport defer to="#mobile-tools-slot" :disabled="!isMobile">
      <header ref="toolbarRef" class="header">
        <button
          v-if="desktopToolbar"
          class="fold"
          data-desktop-action="fold"
          :title="nav.collapsed ? '展开导航' : '折叠导航'"
          :aria-label="nav.collapsed ? '展开导航' : '折叠导航'"
          :aria-expanded="!nav.collapsed"
          @click="nav.toggleCollapse"
        >
          <i
            :class="nav.collapsed ? 'ri-menu-unfold-line' : 'ri-menu-fold-line'"
            aria-hidden="true"
          ></i>
        </button>
        <template v-if="desktopToolbar || legacyDesktopToolbar">
          <NavigationContext class="crumb-a" :label="crumbA" />
          <template v-if="crumbB">
            <i class="ri-arrow-right-s-line crumb-sep"></i>
            <div class="crumb-b">{{ crumbB }}</div>
          </template>
        </template>
        <RouterLink v-if="nav.collapsed && !isMobile && !desktopToolbar" class="header-brand" to="/" aria-label="WAO 首页">
          <WaoLogo wordmark />
        </RouterLink>
        <TabBar />

        <!-- 「问问 AI」入口按用户要求从顶栏移除；Ctrl/⌘ + J 快捷键仍可打开助手 -->
        <div class="quick-actions" :class="{ relocated: desktopToolbar }" role="group" aria-label="全局快捷操作">
          <!-- 两个最高频的新建动作直接摆在这里，点一次就到位；新建项目留在项目页和 Shift+N。 -->
          <button class="plus ghost" data-desktop-action="new-task" :disabled="ui.taskComposerLocked" aria-label="新建任务" title="新建任务" @click="ui.openNewTask()">
            <i class="ri-play-list-add-line" aria-hidden="true"></i><span class="action-label">任务</span>
          </button>
          <button class="plus ghost" data-desktop-action="new-note" :disabled="ui.taskComposerLocked" aria-label="新建小记" title="新建小记" @click="newNote">
            <i class="ri-file-add-line" aria-hidden="true"></i><span class="action-label">小记</span>
          </button>

          <GlobalToolsMenu />
          <FloatingChat :show-trigger="false" />
        </div>
      </header>
      </Teleport>

      <div ref="contentRef" class="content" :class="{ 'focus-content': route.path === '/focus' }">
        <DataLoadStatus :states="data.loadStates" :errors="data.loadErrors" :active-domains="visibleDataDomains" @retry="data.retryDomain" />
        <!-- 页面主体撑满剩余高度；复杂页面可用本页锚点，首页留给主要内容。 -->
        <div class="page-stage">
          <PageSectionNav v-if="route.path !== '/'" :root="pageBodyRef" :scroll-container="contentRef" />
          <div ref="pageBodyRef" class="page-body">
            <TabWorkspace />
            <AiAssistantPanel v-model="ui.assistantOpen" />
          </div>
        </div>
        <SiteFooter reserve-mobile-actions show-brand />
      </div>
    </main>

    <!-- 窄屏正文占满宽度，导航与全局工具按需打开。 -->
    <button :inert="isMobile && mobileMenuOpen" class="fab" :aria-label="route.meta.module === 'notes' ? '新建小记' : '新建任务'" :title="route.meta.module === 'notes' ? '新建小记' : '新建任务'" @click="route.meta.module === 'notes' ? newNote() : newTask()">
      <i :class="route.meta.module === 'notes' ? 'ri-quill-pen-line' : 'ri-add-line'" aria-hidden="true"></i>
    </button>

    <nav class="mobile-main-nav" aria-label="手机主入口" :inert="isMobile && mobileMenuOpen">
      <RouterLink to="/" :class="{ active: route.path === '/' }"
        ><i class="ri-home-4-line"></i>首页</RouterLink
      >
      <RouterLink to="/todo"><i class="ri-checkbox-multiple-line"></i>待办</RouterLink>
      <RouterLink to="/notes"><i class="ri-sticky-note-line"></i>小记</RouterLink>
      <button ref="mobileMenuButton" aria-label="打开完整菜单" aria-haspopup="dialog" :aria-expanded="mobileMenuOpen" @click="mobileMenuOpen = true"><i class="ri-menu-line" aria-hidden="true"></i>更多</button>
    </nav>
    <TaskDrawer />
    <NewTaskModal v-if="ui.modal === 'task'" />
    <QuickNotePopover :key="session.currentUser.id" :open="ui.modal === 'note'" @close="ui.closeModal()" />
    <NewProjectModal v-if="ui.modal === 'project'" />
    <GlobalSearch v-if="ui.searchOpen" />
    <ShortcutsHelp v-if="ui.helpOpen" />
    <Celebration v-if="ui.celebrating" />
    <ReleaseHighlightsHost />
    <QuietCompanionHost />
  </div>
</template>

<style scoped>
.mobile-main-nav {
  display: none;
}
.legacy-desktop-shell .header {
  height: 32px;
  min-height: 32px;
}
.legacy-desktop-shell .quick-actions {
  display: none;
}
.desktop-shell .header {
  background: var(--wao-bg-side);
  border-bottom: 0;
}
.desktop-shell .header {
  display: none;
}
@media (min-width: 761px) {
  .desktop-shell :deep(.side) {
    border-right: 0;
  }
  .desktop-shell :deep(.item) {
    min-height: 32px;
    padding-top: 6px;
    padding-bottom: 6px;
  }
  .desktop-shell :deep(.group-title) {
    margin-top: 10px;
  }
}
@media (max-width: 760px) {
  .mobile-main-nav {
    position: fixed;
    z-index: 72;
    bottom: 0;
    inset-inline: 0;
    display: flex;
    height: calc(54px + env(safe-area-inset-bottom));
    padding-bottom: env(safe-area-inset-bottom);
    box-sizing: border-box;
    background: var(--wao-bg-card);
    border-top: 1px solid var(--wao-line);
  }
  .mobile-keyboard-open .mobile-main-nav, .mobile-keyboard-open .fab { display: none; }
  .shell.mobile-keyboard-open .main { padding-bottom: 0; }
  .mobile-main-nav > * {
    position: relative;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 3px;
    align-items: center;
    justify-content: center;
    font-size: var(--wao-font-body);
    border: 0;
    text-decoration: none;
    color: var(--wao-text-3);
    background: transparent;
  }
  .mobile-main-nav i {
    font-size: 20px;
  }
  .mobile-main-nav .active,
  .mobile-main-nav .router-link-exact-active {
    color: var(--wao-primary-text);
  }
  .mobile-unread {
    position: absolute;
    top: 2px;
    left: 55%;
    border-radius: 10px;
    background: var(--wao-danger);
    color: white;
    padding: 1px 4px;
    font-size: var(--wao-font-caption);
  }
  .shell .main {
    padding-bottom: calc(54px + env(safe-area-inset-bottom));
    box-sizing: border-box;
  }
  .shell .fab {
    bottom: calc(66px + env(safe-area-inset-bottom));
  }
}
.quick-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  margin-left: auto;
}
.quick-actions > * {
  flex-shrink: 0;
}
.quick-actions.relocated {
  display: none;
}
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--wao-bg-page);
}
.main {
  container: app-main / inline-size;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.data-status {
  min-height: 40px;
  margin: 12px 24px 0;
  padding: 9px 12px;
  border: 1px solid #dce2f5;
  border-radius: 9px;
  background: var(--wao-primary-tint);
  color: var(--wao-primary-text);
  font-size: var(--wao-font-help);
  display: flex;
  align-items: center;
  gap: 8px;
}
.data-status.error {
  border-color: var(--wao-danger-line);
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.data-status span {
  flex: 1;
}
.data-status button {
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.spin {
  animation: data-spin 0.9s linear infinite;
}
@keyframes data-spin {
  to {
    transform: rotate(360deg);
  }
}
.header {
  height: var(--wao-header-h);
  background: var(--wao-bg-card);
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  flex-shrink: 0;
}
.header :deep(.tabbar-shell) {
  flex: 1 1 0;
  width: auto;
  min-width: 160px;
  height: 38px;
  background: transparent;
  border: 0;
  border-radius: 8px;
}
.header :deep(.tabbar) {
  padding: 0 4px;
}
.fold {
  width: 30px;
  height: 30px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.fold:hover {
  background: var(--wao-bg-hover-2);
}
.fold i {
  font-size: 17px;
}
.crumb-a {
  font-size: var(--wao-font-body);
  color: var(--wao-text-weak);
  white-space: nowrap;
}
.crumb-sep {
  color: var(--wao-text-faint-2);
  font-size: var(--wao-font-body);
}
.crumb-b {
  font-size: var(--wao-font-body);
  color: var(--wao-text);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.header > :not(.top-menu-bar):not(.tabbar-shell) {
  flex-shrink: 0;
}
.header-brand {
  display: inline-flex;
  align-items: center;
  height: 32px;
  padding: 0 6px;
  color: var(--wao-primary-text);
  text-decoration: none;
  border-radius: 6px;
}
.header-brand:hover { background: var(--wao-primary-soft); }
.header-brand :deep(.wao-logo) { gap: 6px; }
.header-brand :deep(.wao-logo-symbol) { width: 28px; height: 28px; }
.header-brand :deep(.wao-logo-name) { font-size: var(--wao-font-body); font-weight: 600; }
.crumb-b {
  max-width: 180px;
}

.plus {
  white-space: nowrap;
  flex: none;
  height: 32px;
  padding: 0 12px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: var(--wao-font-body);
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.plus:hover {
  background: var(--wao-primary-hover);
}
.plus i {
  font-size: 15px;
}
.plus.ghost {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line-strong);
  color: var(--wao-text-2);
}
.plus.ghost:hover {
  background: var(--wao-bg-hover-2);
}
.plus.ghost i {
  color: var(--wao-area-finance);
}

.bell {
  width: 32px;
  height: 32px;
  border: none;
  background: none;
  border-radius: 8px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}
.bell:hover {
  background: var(--wao-bg-hover-2);
}
.bell i {
  font-size: 16px;
}
.content:has(.cal),
.content:has(.drawings-page),
.content:has(.vault) {
  overflow: hidden;
}
.page-stage:has(.drawings-page),
.page-stage:has(.vault) {
  flex: 1 1 0;
  min-height: 0;
  align-items: stretch;
  overflow: hidden;
}
.page-body:has(.drawings-page),
.page-body:has(.vault) {
  min-height: 0;
  overflow: hidden;
}
.page-stage:has(.cal) {
  flex: 1 1 0;
  min-height: 0;
  align-items: stretch;
  overflow: hidden;
}
.page-body:has(.cal) {
  min-height: 0;
  overflow: hidden;
}
.content {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.page-body {
  flex: 1 0 auto;
  min-width: 0;
}
.page-stage {
  flex: 1 0 auto;
  min-width: 0;
  display: flex;
  align-items: flex-start;
}
.page-stage > .page-body {
  width: auto;
  flex: 1 1 0;
}
.page-stage:has(.home) { align-items: stretch; }
@media (min-width: 821px) {
  .content:has(.notes-page), .content.focus-content {
    overflow: hidden;
  }
  .page-stage:has(.notes-page), .focus-content .page-stage {
    flex: 1 1 0;
    min-height: 0;
    align-items: stretch;
    overflow: hidden;
  }
  .page-body:has(.notes-page), .focus-content .page-body {
    min-height: 0;
    overflow: hidden;
  }
}
/* 四象限占据可用视口，列表分别滚动；不让某一格的长清单撑走其他象限。 */
.page-stage:has(.quadrant-view) {
  flex: 1 1 0;
  min-height: 360px;
  align-items: stretch;
}
.page-body:has(.quadrant-view) {
  display: flex;
  min-height: 0;
}

/* 悬浮新建按钮只在手机上出现；桌面顶栏已经有「新建任务」了 */
.fab {
  display: none;
}

@media (max-width: 760px) {
  .header :deep(.tabbar) {
    padding: 0 2px;
  }
  .fab {
    position: fixed;
    right: 12px;
    bottom: calc(12px + env(safe-area-inset-bottom));
    z-index: 71;
    width: 42px;
    height: 42px;
    border: none;
    border-radius: 50%;
    background: var(--wao-primary);
    color: #fff;
    box-shadow: 0 6px 18px rgba(43, 59, 141, 0.34);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
  }
  .fab i {
    font-size: 21px;
  }
  .fab:active {
    transform: scale(0.94);
  }
  .shell {
    display: flex;
    height: 100dvh;
    overflow: hidden;
  }
  .main {
    height: 100%;
    min-width: 0;
    padding-bottom: 0;
  }
  .crumb-sep,
  .crumb-b {
    display: none;
  }
  .crumb-a {
    min-width: 34px;
    max-width: 52px;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: var(--wao-font-caption);
    font-weight: 600;
    color: var(--wao-text);
  }
  .fold,
  .bell,
  .plus,
  .plus.ghost {
    width: 27px;
    min-width: 27px;
    height: 27px;
    padding: 0;
    border-radius: 6px;
    font-size: 0;
    justify-content: center;
  }
  .fold i,
  .bell i,
  .plus i {
    font-size: 14px;
  }
  .bell {
    flex: 0 0 27px;
  }
  .content {
    font-size: var(--wao-font-caption);
    overscroll-behavior: contain;
  }
  .data-status {
    min-height: 32px;
    margin: 5px 7px 0;
    padding: 6px 7px;
    border-radius: 7px;
    font-size: var(--wao-font-help);
  }
}
</style>

<style scoped>
@container app-main (max-width: 560px) {
  .header { gap: 4px; padding-inline: 8px; }
  .quick-actions { gap: 4px; }
  .plus, .plus.ghost, .fold, .bell { width: 32px; min-width: 32px; height: 32px; padding: 0; flex: 0 0 32px; justify-content: center; }
  .plus .action-label { display: inline; }
  .plus i, .fold i, .bell i { font-size: 16px; }
  .header :deep(.top-menu-bar.empty-target) { flex-basis: 26px; min-width: 26px; }
  .header :deep(.top-menu-bar.has-items) { flex-basis: 100px; }
}

@media (max-width: 760px) {
  .header { flex-wrap: wrap; height: auto; padding: 8px 12px; gap: 8px; background: var(--wao-bg-card); }
  .header :deep(.top-menu-bar.empty-target) { display: none; }
  .header :deep(.tabbar-shell) { order: 10; flex: 0 0 100%; min-width: 0; height: 36px; }
  .header .quick-actions { flex-wrap: wrap; gap: 8px; }
  .header .bell, .header .plus, .header .plus.ghost { width: 36px; min-width: 36px; height: 36px; }
  .mobile-main-nav > button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -3px; }
}
</style>

<style scoped>
.header .quick-actions .plus, .header .quick-actions .plus.ghost { width:auto; min-width:0; flex:none; height:30px; padding:0 8px; gap:5px; border:0; border-radius:7px; background:transparent; color:var(--wao-text-2); font-size:var(--wao-font-body); }
.quick-actions .plus .action-label { display:inline; }
.quick-actions .plus i, .quick-actions .plus.ghost i { font-size:16px; color:var(--wao-primary-text); }
.quick-actions .plus:hover, .quick-actions .plus.ghost:hover { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
@media(pointer:coarse) { .header .quick-actions .plus, .header .quick-actions .plus.ghost { min-height:44px; min-width:44px; } }

/* 页签与操作分区，保持桌面原有的单行高度。 */
@media (min-width: 761px) {
  .header { gap: 12px; padding: 0 16px; }
  .header :deep(.top-menu-bar.has-items) { flex: 0 1 28%; min-width: 100px; max-width: 320px; }
  .header :deep(.tabbar-shell) {
    min-width: 0;
    height: 38px;
    padding: 4px;
    gap: 2px;
    background: var(--wao-bg-tab);
    border-radius: 12px;
  }
  .header :deep(.tabbar) { padding: 0 2px; gap: 5px; }
  .header :deep(.tab) {
    margin: 0;
    padding: 0 8px 0 10px;
    gap: 6px;
    border: 0;
    border-radius: 8px;
  }
  .header :deep(.home-tab) { margin-left: 0; }
  .header :deep(.tab.active) { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
  .header :deep(.scroll-btn) { width: 22px; flex-basis: 22px; border-radius: 7px; padding: 0; }
  .header :deep(.scroll-btn:disabled) { opacity: .28; }
  .header .quick-actions {
    margin-left: 0;
    padding-left: 12px;
    border-left: 1px solid var(--wao-line-control);
    gap: 6px;
  }
  .header .quick-actions .plus { background: var(--wao-primary-soft); color: var(--wao-primary-text); border-radius: 8px; padding: 0 10px; }
  .header .quick-actions .plus.ghost { background: transparent; color: var(--wao-text-2); }
  .header .quick-actions .plus:hover { background: var(--wao-primary-soft-2); }
  .header .quick-actions :deep(.tools-trigger) { border-radius: 8px; }
  .header .quick-actions :deep(.tools-trigger:not(.has-update)) { border-color: transparent; }
}
</style>

<style scoped>
.content:has(.matrix-workspace, .detail-quadrant) { overflow:hidden; }
.page-stage:has(.matrix-workspace, .detail-quadrant) { flex:1 1 0; min-height:0; overflow:hidden; align-items:stretch; }
.page-body:has(.matrix-workspace, .detail-quadrant) { display:flex; min-height:0; overflow:hidden; }
</style>
