<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { toast } from '@/composables/toast'
import SideNav from '@/components/nav/SideNav.vue'
import TabBar from '@/components/tabs/TabBar.vue'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
import NewProjectModal from '@/components/overlays/NewProjectModal.vue'
import GlobalSearch from '@/components/overlays/GlobalSearch.vue'
import Celebration from '@/components/overlays/CelebrationOverlay.vue'
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

const plusOpen = ref(false)

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

function togglePlus() {
  plusOpen.value = !plusOpen.value
}
function newTask() {
  plusOpen.value = false
  ui.openModal('task')
}
function newProject() {
  plusOpen.value = false
  ui.openModal('project')
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
    // 逐层关闭：庆祝 → 帮助 → 搜索 → 弹窗 → 新建下拉
    if (ui.celebrating) ui.stopCelebrate()
    else if (ui.helpOpen) ui.closeHelp()
    else if (ui.searchOpen) ui.closeSearch()
    else if (ui.modal) ui.closeModal()
    else plusOpen.value = false
  }
}
onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  // 接入真实 /api（后端不可用时静默保留 fixtures）
  data.hydrate()
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

        <div class="plus-wrap">
          <button class="plus" @click="togglePlus">
            <i class="ri-add-line"></i>新建<i class="ri-arrow-down-s-line chev"></i>
          </button>
          <template v-if="plusOpen">
            <div class="plus-mask" @click="togglePlus"></div>
            <div class="plus-menu">
              <button class="plus-item" @click="newTask">
                <i class="ri-checkbox-circle-line"></i>新建任务
              </button>
              <button class="plus-item" @click="newProject">
                <i class="ri-stack-line"></i>新建项目
              </button>
            </div>
          </template>
        </div>

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
      </div>
    </main>

    <TaskDrawer />
    <NewTaskModal v-if="ui.modal === 'task'" />
    <NewProjectModal v-if="ui.modal === 'project'" />
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

.plus-wrap {
  position: relative;
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
.plus .chev {
  font-size: 13px;
  opacity: 0.8;
}
.plus-mask {
  position: fixed;
  inset: 0;
  z-index: 69;
}
.plus-menu {
  position: absolute;
  right: 0;
  top: 38px;
  z-index: 70;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop);
  padding: 5px;
  width: 168px;
  animation: waoPop 0.14s ease;
}
.plus-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 8px 9px;
  font-size: 13px;
  color: var(--wao-text);
  cursor: pointer;
}
.plus-item i {
  font-size: 15px;
  color: var(--wao-text-3);
}
.plus-item:hover {
  background: var(--wao-bg-hover-2);
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
</style>
