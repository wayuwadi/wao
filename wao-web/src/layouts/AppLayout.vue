<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import SideNav from '@/components/nav/SideNav.vue'
import TabBar from '@/components/tabs/TabBar.vue'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
import NewProjectModal from '@/components/overlays/NewProjectModal.vue'
import GlobalSearch from '@/components/overlays/GlobalSearch.vue'
import Celebration from '@/components/overlays/CelebrationOverlay.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import ShortcutsHelp from '@/components/overlays/ShortcutsHelp.vue'
import AiAssistantPanel from '@/components/assistant/AiAssistantPanel.vue'
import MusicPlayer from '@/components/music/MusicPlayer.vue'
import NotificationCenter from '@/components/notifications/NotificationCenter.vue'
import FloatingChat from '@/components/chat/FloatingChat.vue'
import PageSectionNav from '@/components/navigation/PageSectionNav.vue'
import FunctionScoreControl from '@/components/navigation/FunctionScoreControl.vue'
import { externalAppItemId, tabFromRoute } from '@/config/nav'
import { useNavStore } from '@/stores/nav'
import { useTabsStore } from '@/stores/tabs'
import { useUiStore } from '@/stores/ui'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { useTheme } from '@/composables/theme'
import { useQuickLinks } from '@/composables/quickLinks'
import { useShortcuts } from '@/composables/shortcuts'
import { useFunctionHealth } from '@/composables/functionHealth'
import type { ModuleId } from '@/types'

const route = useRoute()
const router = useRouter()
const nav = useNavStore()
const tabs = useTabsStore()
const ui = useUiStore()
const data = useDataStore()
const preferences = usePreferenceStore()
const session = useSessionStore()
const theme = useTheme()
const quickLinks = useQuickLinks()
const functionHealth = useFunctionHealth()
const TRACKED_FEATURES = new Set([
  'home',
  'ai-sessions',
  'navigation',
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
])
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
watch(() => ui.modal, (modal) => {
  if (modal === 'note') {
    ui.closeModal()
    void router.push('/notes')
  }
})

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
const scoredFeatureId = computed(() => {
  const featureId = String(route.meta.module || '')
  return functionHealth.byId.value[featureId] ? featureId : ''
})

/** 路由变化即同步页签（覆盖点击导航 / 直接 URL / 前进后退）。 */
watch(
  () => route.fullPath,
  () => {
    const tab = tabFromRoute(route)
    if (tab) {
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
    !!ui.openTaskId ||
    ui.searchOpen
  )
}
function ensureVisibleHeavyDomains() {
  if (needsDocs()) void data.ensureDocsLoaded()
}
watch(() => [route.name, route.meta.module], ensureVisibleHeavyDomains, { immediate: true })

async function reloadAll() {
  await data.hydrate()
  ensureVisibleHeavyDomains()
}

watch(
  () => nav.builtinMenus.map((menu) => `${menu.id}|${menu.label}|${menu.icon}`).join('\n'),
  () => {
    for (const menu of nav.builtinMenus) {
      tabs.setMeta(menu.id, { label: menu.label, icon: menu.icon })
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
  void router.push('/notes')
}
const themeIcon = computed(() => {
  if (theme.mode.value === 'system') return 'ri-contrast-line'
  return theme.effective.value === 'dark' ? 'ri-sun-line' : 'ri-moon-line'
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
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    ui.openSearch()
  } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'j') {
    e.preventDefault()
    ui.toggleAssistant()
  } else if (e.key === 'Escape') {
    // 逐层关闭：聊天 → 助手 → 庆祝 → 帮助 → 搜索 → 弹窗
    if (ui.chatOpen) ui.closeChat()
    else if (ui.assistantOpen) ui.closeAssistant()
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
  void preferences.load()
  // 接入真实 /api；任务域会独立暴露加载 / 失败状态，不用 fixtures 冒充用户数据。
  await data.hydrate()
  openLocatedTask()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="shell" :data-control-style="preferences.effectiveControlStyle">
    <SideNav @open-search="openSearch" />

    <main class="main">
      <header class="header">
        <button class="fold" title="折叠 / 展开导航" @click="nav.toggleCollapse">
          <i :class="nav.collapsed ? 'ri-menu-unfold-line' : 'ri-menu-fold-line'"></i>
        </button>
        <div class="crumb-a">{{ crumbA }}</div>
        <template v-if="crumbB">
          <i class="ri-arrow-right-s-line crumb-sep"></i>
          <div class="crumb-b">{{ crumbB }}</div>
        </template>
        <div class="spacer"></div>

        <FunctionScoreControl
          v-if="scoredFeatureId"
          :feature-id="scoredFeatureId"
          :label="crumbA"
        />

        <button class="assistant-entry" title="问问 AI（Ctrl/⌘ + J）" @click="ui.openAssistant()">
          <i class="ri-robot-2-line"></i>问问 AI
        </button>
        <!-- 两个最高频的新建动作直接摆在这里，点一次就到位；新建项目留在项目页和 Shift+N。 -->
        <button class="plus" @click="newTask">
          <i class="ri-checkbox-circle-line"></i>新建任务
        </button>
        <button class="plus ghost" @click="newNote">
          <i class="ri-sticky-note-line"></i>新建小记
        </button>

        <button class="bell" :title="themeTitle" @click="toggleTheme">
          <i :class="themeIcon"></i>
        </button>
        <button class="bell" title="快捷键（?）" @click="ui.toggleHelp()">
          <i class="ri-keyboard-line"></i>
        </button>
        <FloatingChat />
        <NotificationCenter />
      </header>

      <TabBar />

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
        <!-- 页面主体撑满剩余高度；复杂页面声明分区后，桌面左侧自动出现本页锚点。 -->
        <div class="page-stage">
          <PageSectionNav :root="pageBodyRef" :scroll-container="contentRef" />
          <div ref="pageBodyRef" class="page-body"><RouterView /></div>
        </div>
        <SiteFooter reserve-mobile-actions />
      </div>
    </main>

    <!-- 手机保留拇指区新建入口；左栏恢复后不再为底部模块导航让位。 -->
    <button class="fab" aria-label="新建任务" title="新建任务" @click="newTask">
      <i class="ri-add-line"></i>
    </button>

    <TaskDrawer />
    <NewTaskModal v-if="ui.modal === 'task'" />
    <NewProjectModal v-if="ui.modal === 'project'" />
    <GlobalSearch v-if="ui.searchOpen" />
    <ShortcutsHelp v-if="ui.helpOpen" />
    <Celebration v-if="ui.celebrating" />
    <!-- 助手依赖登录态和私有资产，只挂在受保护的应用壳内，避免遮挡登录页与公开分享页。 -->
    <AiAssistantPanel v-model="ui.assistantOpen" />
    <!-- 音乐来自真实文件库，只挂在登录后的应用壳；切换页面时 audio 节点不会被路由销毁。 -->
    <MusicPlayer />
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--wao-bg-page);
}
.main {
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
  color: #4358d6;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.data-status.error {
  border-color: #f0c7c7;
  background: var(--wao-danger-bg);
  color: #a83b3b;
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
.spacer {
  flex: 1;
}

.plus {
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
.assistant-entry {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--wao-primary-line);
  border-radius: 8px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.assistant-entry:hover {
  background: var(--wao-bg-hover-2);
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
/* 四象限占据可用视口，列表分别滚动；不让某一格的长清单撑走其他象限。 */
.page-stage:has(.todo-quadrant) { flex: 1 1 0; min-height: 360px; align-items: stretch; }
.page-body:has(.todo-quadrant) { display: flex; min-height: 0; }

/* 悬浮新建按钮只在手机上出现；桌面顶栏已经有「新建任务」了 */
.fab {
  display: none;
}

@media (max-width: 760px) {
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
  .header {
    height: var(--wao-header-h);
    gap: 2px;
    padding: 0 4px;
    overflow-x: auto;
    overflow-y: hidden;
    scrollbar-width: none;
  }
  .header::-webkit-scrollbar {
    display: none;
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
  .plus.ghost,
  .assistant-entry {
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
  .plus i,
  .assistant-entry i {
    font-size: 14px;
  }
  .bell {
    flex: 0 0 27px;
  }
  .spacer {
    min-width: 1px;
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
