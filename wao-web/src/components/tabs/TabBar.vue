<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTabsStore } from '@/stores/tabs'
import type { Tab } from '@/types'

const tabs = useTabsStore()
const router = useRouter()

const menu = reactive({ open: false, x: 0, y: 0, key: '' })
const scroller = ref<HTMLElement | null>(null)
const canLeft = ref(false)
const canRight = ref(false)

function measureScroll() {
  const el = scroller.value
  if (!el) return
  canLeft.value = el.scrollLeft > 2
  canRight.value = el.scrollLeft + el.clientWidth < el.scrollWidth - 2
}
function scrollByPage(direction: -1 | 1) {
  scroller.value?.scrollBy({ left: direction * Math.max(180, (scroller.value?.clientWidth ?? 400) * 0.7), behavior: 'smooth' })
}
function revealActive() {
  void nextTick(() => {
    const active = scroller.value?.querySelector<HTMLElement>('.tab.active')
    active?.scrollIntoView?.({ block: 'nearest', inline: 'nearest', behavior: 'smooth' })
    measureScroll()
  })
}
watch(() => [tabs.activeKey, tabs.tabs.length], revealActive)
onMounted(revealActive)

const menuTargetClosable = computed(() => {
  const t = tabs.tabs.find((t) => t.key === menu.key)
  return !!t?.closable
})

function selectTab(tab: Tab) {
  router.push(tab.path)
}

// ---- 拖拽排序 ----
// 页签顺序是用户自己的动线，堆到第七八个时想把常看的挪到前面，只能靠拖。
// 首页钉在最左，不参与拖动（见 tabs.move）。
const dragKey = ref<string | null>(null)
const overKey = ref<string | null>(null)

function onDragStart(tab: Tab, e: DragEvent) {
  if (tab.key === 'home') {
    e.preventDefault()
    return
  }
  dragKey.value = tab.key
  e.dataTransfer?.setData('text/plain', tab.key)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}
function onDragOver(tab: Tab) {
  if (dragKey.value && tab.key !== dragKey.value) overKey.value = tab.key
}
function onDrop(tab: Tab) {
  if (dragKey.value) tabs.move(dragKey.value, tab.key)
  dragKey.value = null
  overKey.value = null
}
function onDragEnd() {
  dragKey.value = null
  overKey.value = null
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
  <div class="tabbar-shell">
    <button class="scroll-btn" :disabled="!canLeft" title="查看左侧页签" @click="scrollByPage(-1)"><i class="ri-arrow-left-s-line"></i></button>
    <div ref="scroller" class="tabbar" @scroll.passive="measureScroll">
    <div
      v-for="tab in tabs.tabs"
      :key="tab.key"
      class="tab"
      :class="{ active: tab.key === tabs.activeKey, dragging: dragKey === tab.key, over: overKey === tab.key }"
      :draggable="tab.key !== 'home'"
      title="拖动可调整顺序；右键：关闭当前 / 左边 / 右边 / 全部"
      @click="selectTab(tab)"
      @contextmenu.prevent="openMenu($event, tab)"
      @dragstart="onDragStart(tab, $event)"
      @dragover.prevent="onDragOver(tab)"
      @drop.prevent="onDrop(tab)"
      @dragend="onDragEnd"
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
    <button class="scroll-btn" :disabled="!canRight" title="查看右侧页签" @click="scrollByPage(1)"><i class="ri-arrow-right-s-line"></i></button>
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
.tabbar-shell {
  height: var(--wao-tabbar-h);
  background: var(--wao-bg-tab);
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  flex-shrink: 0;
  min-width: 0;
}
.tabbar {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: stretch;
  padding: 0 10px;
  gap: 2px;
  overflow-x: auto;
}
.tabbar::-webkit-scrollbar {
  height: 0;
}
.scroll-btn {
  width: 28px;
  flex: 0 0 28px;
  border: 0;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.scroll-btn:hover:not(:disabled) { background: var(--wao-bg-hover-2); }
.scroll-btn:disabled { opacity: 0.22; cursor: default; }
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
/* 拖拽中的页签变淡，落点页签左侧压一道主色，指明会插到哪儿 */
.tab.dragging {
  opacity: 0.45;
}
.tab.over {
  box-shadow: inset 2px 0 0 var(--wao-primary);
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
@media (max-width: 760px) {
  /* 手机由底部模块导航承担切换；桌面多页签条只会重复信息并挤掉项目正文。 */
  .tabbar-shell { display: none; }
}
</style>
