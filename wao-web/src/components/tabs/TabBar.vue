<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useTabsStore } from '@/stores/tabs'
import type { Tab } from '@/types'

const tabs = useTabsStore()
const router = useRouter()

const menu = reactive({ open: false, x: 0, y: 0, key: '' })

const menuTargetClosable = computed(() => {
  const t = tabs.tabs.find((t) => t.key === menu.key)
  return !!t?.closable
})

function selectTab(tab: Tab) {
  router.push(tab.path)
}

function closeTab(tab: Tab) {
  const next = tabs.close(tab.key)
  if (next) router.push(next.path)
}

function openMenu(e: MouseEvent, tab: Tab) {
  menu.open = true
  menu.x = e.clientX
  menu.y = e.clientY
  menu.key = tab.key
}
function closeMenu() {
  menu.open = false
}
function navigateAfter(next: Tab | null) {
  if (next) router.push(next.path)
  closeMenu()
}
function mCloseCurrent() {
  navigateAfter(tabs.close(menu.key))
}
function mCloseLeft() {
  navigateAfter(tabs.closeLeft(menu.key))
}
function mCloseRight() {
  navigateAfter(tabs.closeRight(menu.key))
}
function mCloseAll() {
  navigateAfter(tabs.closeAll())
}
</script>

<template>
  <div class="tabbar">
    <div
      v-for="tab in tabs.tabs"
      :key="tab.key"
      class="tab"
      :class="{ active: tab.key === tabs.activeKey }"
      title="右键：关闭当前 / 左边 / 右边 / 全部"
      @click="selectTab(tab)"
      @contextmenu.prevent="openMenu($event, tab)"
    >
      <i :class="tab.icon"></i>
      <span class="tab-label">{{ tab.label }}</span>
      <button
        v-if="tab.closable"
        class="close"
        title="关闭"
        @click.stop="closeTab(tab)"
      >
        <i class="ri-close-line"></i>
      </button>
    </div>
  </div>

  <template v-if="menu.open">
    <div class="menu-mask" @click="closeMenu" @contextmenu.prevent="closeMenu"></div>
    <div class="menu" :style="{ left: menu.x + 'px', top: menu.y + 'px' }">
      <button class="menu-item" :disabled="!menuTargetClosable" @click="mCloseCurrent">
        <i class="ri-close-line"></i>关闭当前
      </button>
      <button class="menu-item" @click="mCloseLeft"><i class="ri-arrow-left-line"></i>关闭左边</button>
      <button class="menu-item" @click="mCloseRight">
        <i class="ri-arrow-right-line"></i>关闭右边
      </button>
      <div class="menu-sep"></div>
      <button class="menu-item danger" @click="mCloseAll">
        <i class="ri-close-circle-line"></i>关闭全部
      </button>
    </div>
  </template>
</template>

<style scoped>
.tabbar {
  height: var(--wao-tabbar-h);
  background: var(--wao-bg-tab);
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  align-items: stretch;
  padding: 0 10px;
  gap: 2px;
  flex-shrink: 0;
  overflow-x: auto;
}
.tabbar::-webkit-scrollbar {
  height: 0;
}
.tab {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 9px 0 13px;
  margin: 5px 0 0;
  cursor: pointer;
  border-radius: 9px 9px 0 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--wao-text-3);
  font-size: 12.5px;
  font-weight: 400;
  white-space: nowrap;
  user-select: none;
}
.tab:hover {
  background: #e6e7ec;
}
.tab i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.tab.active {
  background: #fff;
  color: var(--wao-text);
  font-weight: 500;
  border-bottom-color: var(--wao-primary);
}
.tab.active i {
  color: var(--wao-primary);
}
.tab-label {
  white-space: nowrap;
}
.close {
  width: 18px;
  height: 18px;
  border: none;
  background: none;
  border-radius: 5px;
  cursor: pointer;
  color: var(--wao-text-faint);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}
.close:hover {
  background: #e0e2e9;
  color: var(--wao-text-2);
}
.close i {
  font-size: 13px;
  color: inherit;
}

.menu-mask {
  position: fixed;
  inset: 0;
  z-index: 130;
}
.menu {
  position: fixed;
  z-index: 131;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop-2);
  padding: 5px;
  width: 150px;
  animation: waoPop 0.14s ease;
}
.menu-item {
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
.menu-item i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.menu-item:hover {
  background: var(--wao-bg-hover-2);
}
.menu-item:disabled {
  cursor: not-allowed;
}
.menu-item.danger {
  color: var(--wao-danger-strong);
}
.menu-item.danger i {
  color: var(--wao-danger-strong);
}
.menu-item.danger:hover {
  background: var(--wao-danger-bg);
}
.menu-sep {
  height: 1px;
  background: #f0f0f3;
  margin: 4px 6px;
}
</style>
