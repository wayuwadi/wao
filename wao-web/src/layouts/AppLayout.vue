<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { toast } from '@/composables/toast'
import SideNav from '@/components/nav/SideNav.vue'
import TabBar from '@/components/tabs/TabBar.vue'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
import NewProjectModal from '@/components/overlays/NewProjectModal.vue'
import QuickNoteModal from '@/components/overlays/QuickNoteModal.vue'
import GlobalSearch from '@/components/overlays/GlobalSearch.vue'
import Celebration from '@/components/overlays/CelebrationOverlay.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import ShortcutsHelp from '@/components/overlays/ShortcutsHelp.vue'
import { MODULES, tabFromRoute } from '@/config/nav'
import { useNavStore } from '@/stores/nav'
import { useTabsStore } from '@/stores/tabs'
import { useUiStore } from '@/stores/ui'
import { useDataStore } from '@/stores/data'
import { useShortcuts } from '@/composables/shortcuts'
import type { ModuleId } from '@/types'

const route = useRoute()
const nav = useNavStore()
const tabs = useTabsStore()
const ui = useUiStore()
const data = useDataStore()

useShortcuts()

const crumbA = computed(() => {
  const mod = MODULES[route.meta.module as ModuleId]
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
    if (tab) tabs.open(tab)
  },
  { immediate: true },
)

function newTask() {
  ui.openModal('task')
}
/** 小记：不用先选知识库，打开就是一个空白输入框，存进小记知识库。 */
function newNote() {
  ui.openModal('note')
}
function bell() {
  toast('通知中心建设中')
}
function openSearch() {
  ui.openSearch()
}

function onKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    ui.openSearch()
  } else if (e.key === 'Escape') {
    // 逐层关闭：庆祝 → 帮助 → 搜索 → 弹窗
    if (ui.celebrating) ui.stopCelebrate()
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
  // 接入真实 /api（后端不可用时静默保留 fixtures）
  await data.hydrate()
  openLocatedTask()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="shell">
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

        <!-- 两个最高频的新建动作直接摆在这里，点一次就到位；新建项目留在项目页和 Shift+N。 -->
        <button class="plus" @click="newTask">
          <i class="ri-checkbox-circle-line"></i>新建任务
        </button>
        <button class="plus ghost" @click="newNote">
          <i class="ri-sticky-note-line"></i>新建小记
        </button>

        <button class="bell" title="快捷键（?）" @click="ui.toggleHelp()">
          <i class="ri-keyboard-line"></i>
        </button>
        <button class="bell" title="通知" @click="bell">
          <i class="ri-notification-3-line"></i>
          <span class="bell-dot"></span>
        </button>
      </header>

      <TabBar />

      <div class="content">
        <RouterView />
        <SiteFooter />
      </div>
    </main>

    <!--
      手机上的常驻新建入口：顶栏那个图标在屏幕最上沿，单手够不着；
      录任务是移动端最高频的动作，按钮就该落在拇指自然停的位置。
    -->
    <button class="fab" aria-label="新建任务" title="新建任务" @click="newTask">
      <i class="ri-add-line"></i>
    </button>

    <TaskDrawer />
    <NewTaskModal v-if="ui.modal === 'task'" />
    <NewProjectModal v-if="ui.modal === 'project'" />
    <QuickNoteModal v-if="ui.modal === 'note'" />
    <GlobalSearch v-if="ui.searchOpen" />
    <ShortcutsHelp v-if="ui.helpOpen" />
    <Celebration v-if="ui.celebrating" />
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
.header {
  height: var(--wao-header-h);
  background: #fff;
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
.plus:hover {
  background: var(--wao-primary-hover);
}
.plus i {
  font-size: 15px;
}
.plus.ghost {
  background: #fff;
  border: 1px solid var(--wao-line-strong);
  color: var(--wao-text-2);
}
.plus.ghost:hover {
  background: var(--wao-bg-hover-2);
}
.plus.ghost i {
  color: #7a5cc6;
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
.bell-dot {
  position: absolute;
  top: 7px;
  right: 8px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--wao-danger);
}

.content {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

/* 悬浮新建按钮只在手机上出现；桌面顶栏已经有「新建任务」了 */
.fab {
  display: none;
}

@media (max-width: 760px) {
  .fab {
    position: fixed;
    right: 16px;
    bottom: calc(78px + env(safe-area-inset-bottom));
    z-index: 71;
    width: 52px;
    height: 52px;
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
    font-size: 26px;
  }
  .fab:active {
    transform: scale(0.94);
  }
  .shell {
    display: block;
    height: 100dvh;
    overflow: hidden;
  }
  .main {
    height: 100%;
    padding-bottom: 64px;
  }
  .header {
    height: 48px;
    gap: 4px;
    padding: 0 8px;
  }
  .fold,
  .crumb-sep,
  .crumb-b,
  .plus.ghost,
  .bell-dot {
    display: none;
  }
  .crumb-a {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    font-weight: 600;
    color: var(--wao-text);
  }
  .plus {
    width: 34px;
    padding: 0;
    justify-content: center;
    border-radius: 9px;
    font-size: 0;
  }
  .plus i { font-size: 18px; }
  .bell { width: 36px; height: 36px; }
  .content { overscroll-behavior: contain; }
}
</style>
