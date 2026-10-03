<script setup lang="ts">
import { useIsMobile } from '@/composables/viewport'
const isMobile = useIsMobile()
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TabWorkspace from '@/components/tabs/TabWorkspace.vue'
import NavigationContext from '@/components/nav/NavigationContext.vue'
import TopMenuBar from '@/components/nav/TopMenuBar.vue'
import SideNav from '@/components/nav/SideNav.vue'
import MobileShellMenu from '@/components/nav/MobileShellMenu.vue'
import TabBar from '@/components/tabs/TabBar.vue'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
import NewProjectModal from '@/components/overlays/NewProjectModal.vue'
import GlobalSearch from '@/components/overlays/GlobalSearch.vue'
import Celebration from '@/components/overlays/CelebrationOverlay.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import ShortcutsHelp from '@/components/overlays/ShortcutsHelp.vue'
import AiAssistantPanel from '@/components/assistant/AiAssistantPanel.vue'
import NotificationCenter from '@/components/notifications/NotificationCenter.vue'
import FloatingChat from '@/components/chat/FloatingChat.vue'
import PageSectionNav from '@/components/navigation/PageSectionNav.vue'
import ReleaseHighlightsHost from '@/components/release/ReleaseHighlightsHost.vue'
import VersionUpdateNotice from '@/components/ui/VersionUpdateNotice.vue'
import { externalAppItemId, RETIRED_MODULE_IDS, tabFromRoute } from '@/config/nav'
import { useNavStore } from '@/stores/nav'
import { useTabsStore } from '@/stores/tabs'
import { useFloatingTabsStore } from '@/stores/floatingTabs'
import { useUiStore } from '@/stores/ui'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { useTheme } from '@/composables/theme'
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
const router = useRouter()
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
const theme = useTheme()
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

const loadingDomainCount = computed(
  () => Object.values(data.loadStates).filter((state) => state === 'loading').length,
)
const loadErrorMessages = computed(() => [
  ...new Set(Object.values(data.loadErrors).filter(Boolean)),
])

// 登录成员切换时先清掉上一名成员的内存偏好，再按当前会话重新读取。
preferences.prepareForMember(session.currentUser.id)

useShortcuts()

// 兼容旧动作调用，所有小记入口都回到同一个记录空间。
watch(
  () => ui.modal,
  (modal) => {
    if (modal === 'note') {
      ui.closeModal()
      newNote()
    }
  },
)

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

async function reloadAll() {
  await data.hydrate()
  void import('@/utils/autoNoteEnrichment').then(({ resumeAutoNoteEnrichment }) =>
    resumeAutoNoteEnrichment(session.currentUser.id),
  )
  ensureVisibleHeavyDomains()
}

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
/** 小记：不用先选知识库，打开就是一个空白输入框，存进小记知识库。 */
function newNote() {
  if (ui.openTaskId) void data.flushTask(ui.openTaskId)
  ui.closeTask()
  void router.push('/notes')
}
const themeIcon = computed(() => {
  return theme.effective.value === 'dark' ? 'ri-moon-line' : 'ri-sun-line'
})
const themeTitle = computed(() => {
  const labels = {
    light: '当前：明亮，点击切换深色',
    dark: '当前：深色，点击切换跟随系统',
    system: '当前：跟随系统，点击切换明亮',
  }
  return labels[theme.mode.value]
})
function toggleTheme() {
  theme.toggleTheme()
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
  } else if (e.key === 'Escape') {
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
    :class="{ 'desktop-shell': desktopToolbar, 'legacy-desktop-shell': legacyDesktopToolbar }"
    :data-control-style="preferences.effectiveControlStyle"
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
        <TopMenuBar />
        <RouterLink class="rest-link" to="/breathe" aria-label="休息一下，打开一呼一吸">休息一下</RouterLink>
        <TabBar />

        <!-- 「问问 AI」入口按用户要求从顶栏移除；Ctrl/⌘ + J 快捷键仍可打开助手 -->
        <div class="quick-actions" :class="{ relocated: desktopToolbar }">
          <!-- 两个最高频的新建动作直接摆在这里，点一次就到位；新建项目留在项目页和 Shift+N。 -->
          <button class="plus" data-desktop-action="new-task" aria-label="新建任务" title="新建任务" @click="ui.openNewTask()">
            <i class="ri-checkbox-circle-line" aria-hidden="true"></i><span class="action-label">新建任务</span>
          </button>
          <button class="plus ghost" data-desktop-action="new-note" aria-label="新建小记" title="新建小记" @click="newNote">
            <i class="ri-sticky-note-line" aria-hidden="true"></i><span class="action-label">新建小记</span>
          </button>

          <button
            class="bell"
            data-desktop-action="theme"
            :data-theme="theme.effective.value"
            :title="themeTitle"
            :aria-label="themeTitle"
            @click="toggleTheme"
          >
            <i :class="themeIcon" aria-hidden="true"></i>
          </button>
          <button
            class="bell"
            data-desktop-action="shortcuts"
            title="查看快捷键"
            aria-label="查看快捷键"
            :aria-expanded="ui.helpOpen"
            @click="ui.toggleHelp()"
          >
            <i class="ri-keyboard-line" aria-hidden="true"></i>
          </button>
          <FloatingChat :show-trigger="false" />
          <NotificationCenter />
          <VersionUpdateNotice />
        </div>
      </header>
      </Teleport>

      <div ref="contentRef" class="content">
        <div v-if="loadErrorMessages.length" class="data-status error" role="alert">
          <i class="ri-error-warning-line"></i>
          <span>
            部分数据加载失败：{{ loadErrorMessages.join('；') }}
            <template v-if="loadingDomainCount">（其余数据仍在加载）</template>
          </span>
          <button v-if="!loadingDomainCount" type="button" @click="reloadAll">
            <i class="ri-refresh-line"></i>重新加载
          </button>
        </div>
        <div v-else-if="loadingDomainCount" class="data-status" role="status" aria-live="polite">
          <i class="ri-loader-4-line spin"></i>
          正在加载业务数据…
        </div>
        <!-- 页面主体撑满剩余高度；复杂页面可用本页锚点，首页留给主要内容。 -->
        <div class="page-stage">
          <PageSectionNav v-if="route.path !== '/'" :root="pageBodyRef" :scroll-container="contentRef" />
          <div ref="pageBodyRef" class="page-body">
            <TabWorkspace />
            <AiAssistantPanel v-model="ui.assistantOpen" />
          </div>
        </div>
        <SiteFooter reserve-mobile-actions />
      </div>
    </main>

    <!-- 窄屏正文占满宽度，导航与全局工具按需打开。 -->
    <button :inert="isMobile && mobileMenuOpen" class="fab" aria-label="新建任务" title="新建任务" @click="newTask">
      <i class="ri-add-line"></i>
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
    <NewProjectModal v-if="ui.modal === 'project'" />
    <GlobalSearch v-if="ui.searchOpen" />
    <ShortcutsHelp v-if="ui.helpOpen" />
    <Celebration v-if="ui.celebrating" />
    <ReleaseHighlightsHost />
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
  .mobile-main-nav > * {
    position: relative;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 3px;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    border: 0;
    text-decoration: none;
    color: var(--wao-text-weak);
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
    font-size: 9px;
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
  font-size: 13px;
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
  font-size: 13px;
  color: var(--wao-text-weak);
  white-space: nowrap;
}
.crumb-sep {
  color: var(--wao-text-faint-2);
  font-size: 14px;
}
.crumb-b {
  font-size: 13px;
  color: var(--wao-text);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.header > :not(.top-menu-bar):not(.tabbar-shell) {
  flex-shrink: 0;
}
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
  font-size: 13px;
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
@media (min-width: 821px) {
  .content:has(.notes-page) {
    overflow: hidden;
  }
  .page-stage:has(.notes-page) {
    flex: 1 1 0;
    min-height: 0;
    align-items: stretch;
    overflow: hidden;
  }
  .page-body:has(.notes-page) {
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
    font-size: 10px;
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
    font-size: 11px;
    overscroll-behavior: contain;
  }
  .data-status {
    min-height: 32px;
    margin: 5px 7px 0;
    padding: 6px 7px;
    border-radius: 7px;
    font-size: 10px;
  }
}
</style>

<style scoped>
.rest-link { flex:none; padding:8px; color:var(--wao-text-weak); font-size:12px; white-space:nowrap; }

/* 侧栏也占据空间：依据正文区宽度换行，而非仅看浏览器视口。 */
@container app-main (max-width: 900px) {
  .header { height: auto; min-height: var(--wao-header-h); flex-wrap: wrap; gap: 6px; padding: 6px 12px 0; }
  .header :deep(.tabbar-shell) { order: 10; flex: 0 0 100%; width: 100%; min-width: 0; height: 38px; margin: 0; border-top: 1px solid var(--wao-line); border-radius: 0; }
  .header :deep(.top-menu-bar.has-items) { flex: 1 1 120px; min-width: 0; }
}
@container app-main (max-width: 560px) {
  .header { gap: 4px; padding: 4px 6px 0; }
  .quick-actions { gap: 4px; }
  .plus, .plus.ghost, .fold, .bell { width: 32px; min-width: 32px; height: 32px; padding: 0; flex: 0 0 32px; justify-content: center; }
  .plus .action-label { display: none; }
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
  .header .rest-link { padding: 8px 0; }
  .mobile-main-nav > button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -3px; }
}
</style>
