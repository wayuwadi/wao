<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTabsStore } from '@/stores/tabs'
import { useFloatingTabsStore } from '@/stores/floatingTabs'
import type { Tab } from '@/types'

const tabs = useTabsStore()
const windows = useFloatingTabsStore()
const router = useRouter()

const menu = reactive({ open: false, x: 0, y: 0, key: '' })
const renameDraft = ref('')
const renaming = ref(false)
const scroller = ref<HTMLElement | null>(null)
const shell = ref<HTMLElement | null>(null)
const canLeft = ref(false)
const canRight = ref(false)
const homeTab = computed(() => tabs.tabs.find((tab) => tab.key === 'home'))
const scrollableTabs = computed(() => tabs.tabs.filter((tab) => tab.key !== 'home'))

function measureScroll() {
  const el = scroller.value
  if (!el) return
  canLeft.value = el.scrollLeft > 2
  canRight.value = el.scrollLeft + el.clientWidth < el.scrollWidth - 2
}
function scrollByPage(direction: -1 | 1) {
  scroller.value?.scrollBy({
    left: direction * Math.max(180, (scroller.value?.clientWidth ?? 400) * 0.7),
    behavior: 'smooth',
  })
}
function scrollWithWheel(event: WheelEvent) {
  const el = scroller.value
  if (!el || el.scrollWidth <= el.clientWidth) return
  const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY
  if (!delta) return
  event.preventDefault()
  el.scrollLeft += delta
  measureScroll()
}
function revealActive() {
  void nextTick(() => {
    const active = scroller.value?.querySelector<HTMLElement>('.tab.active')
    active?.scrollIntoView?.({ block: 'nearest', inline: 'nearest', behavior: 'smooth' })
    measureScroll()
  })
}
watch(() => [tabs.activeKey, tabs.tabs.map((tab) => tab.key).join('|')], revealActive)
onMounted(revealActive)

const menuTargetClosable = computed(() => {
  const t = tabs.tabs.find((t) => t.key === menu.key)
  return !!t?.closable
})
const menuTargetPinned = computed(
  () => tabs.tabs.find((tab) => tab.key === menu.key)?.pinned === true,
)

function selectTab(tab: Tab) {
  if (windows.keys.includes(tab.key)) {
    windows.front = tab.key
    return
  }
  router.push(tab.path)
}

function floatMenuTab() {
  windows.float(menu.key)
  closeMenu()
}

function onWorkspaceDragOver(event: DragEvent) {
  if (dragKey.value && event.target instanceof Element && !event.target.closest('.tabbar-shell')) event.preventDefault()
}
function onWorkspaceDrop(event: DragEvent) {
  if (!dragKey.value || !(event.target instanceof Element) || event.target.closest('.tabbar-shell')) return
  event.preventDefault()
  windows.float(dragKey.value)
  onDragEnd()
}
onMounted(() => {
  document.addEventListener('dragover', onWorkspaceDragOver)
  document.addEventListener('drop', onWorkspaceDrop)
})
onBeforeUnmount(() => {
  document.removeEventListener('dragover', onWorkspaceDragOver)
  document.removeEventListener('drop', onWorkspaceDrop)
})

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

async function closeTab(tab: Tab, event: MouseEvent) {
  const keyboard = event.detail === 0
  const next = tabs.close(tab.key)
  if (next) await router.push(next.path)
  if (keyboard) {
    await nextTick()
    shell.value?.querySelector<HTMLButtonElement>('button[aria-current="page"]')?.focus()
  }
}

function openMenu(e: MouseEvent, tab: Tab) {
  menu.open = true
  menu.x = e.clientX
  menu.y = e.clientY
  menu.key = tab.key
  renameDraft.value = tab.label
  renaming.value = false
}
function closeMenu() {
  menu.open = false
  renaming.value = false
}
function navigateAfter(next: Tab | null) {
  if (next) router.push(next.path)
  closeMenu()
}
function mTogglePin() {
  tabs.togglePin(menu.key)
  closeMenu()
}
function mRename() {
  if (menuTargetClosable.value) renaming.value = true
}
function mSaveRename() {
  if (tabs.rename(menu.key, renameDraft.value)) closeMenu()
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
function mCloseUnpinned() {
  navigateAfter(tabs.closeUnpinned())
}
</script>

<template>
  <div ref="shell" class="tabbar-shell">
    <button
      v-if="homeTab"
      type="button"
      :aria-label="'切换到' + homeTab.label"
      :aria-current="homeTab.key === tabs.activeKey ? 'page' : undefined"
      class="tab home-tab"
      :class="{ active: homeTab.key === tabs.activeKey, over: overKey === homeTab.key }"
      title="首页固定在页签栏左侧"
      @click="selectTab(homeTab)"
      @contextmenu.prevent="openMenu($event, homeTab)"
      @dragover.prevent="onDragOver(homeTab)"
      @drop.prevent="onDrop(homeTab)"
    >
      <i :class="homeTab.icon" aria-hidden="true"></i>
      <span class="tab-label">{{ homeTab.label }}</span>
    </button>
    <button class="scroll-btn" :disabled="!canLeft" title="查看左侧页签" aria-label="查看左侧页签" @click="scrollByPage(-1)">
      <i class="ri-arrow-left-s-line"></i>
    </button>
    <div ref="scroller" class="tabbar" @scroll.passive="measureScroll" @wheel="scrollWithWheel">
      <div
        v-for="tab in scrollableTabs"
        :key="tab.key"
        class="tab"
        :class="{
          active: tab.key === tabs.activeKey,
          pinned: tab.pinned,
          dragging: dragKey === tab.key,
          over: overKey === tab.key,
        }"
        :draggable="tab.key !== 'home'"
        title="栏内拖动排序，拖出栏外悬浮；右键设置悬浮、置顶或关闭"
        @click="selectTab(tab)"
        @contextmenu.prevent="openMenu($event, tab)"
        @dragstart="onDragStart(tab, $event)"
        @dragover.prevent="onDragOver(tab)"
        @drop.prevent="onDrop(tab)"
        @dragend="onDragEnd"
      >
        <button type="button" class="tab-open" :aria-label="'切换到' + tab.label" :aria-current="tab.key === tabs.activeKey ? 'page' : undefined" @click.stop="selectTab(tab)">
          <i :class="tab.icon" aria-hidden="true"></i>
          <span class="tab-label">{{ tab.label }}</span>
          <i v-if="windows.keys.includes(tab.key)" class="ri-picture-in-picture-line" title="已悬浮，点击置前" aria-hidden="true"></i>
          <i v-if="tab.pinned" class="tab-pin ri-pushpin-fill" title="已置顶，批量关闭时保留" aria-hidden="true"></i>
        </button>
        <button v-if="tab.closable" type="button" class="close" :title="'关闭页签：' + tab.label" :aria-label="'关闭页签：' + tab.label" @click.stop="closeTab(tab, $event)">
          <i class="ri-close-line"></i>
        </button>
      </div>
    </div>
    <button class="scroll-btn" :disabled="!canRight" title="查看右侧页签" aria-label="查看右侧页签" @click="scrollByPage(1)">
      <i class="ri-arrow-right-s-line"></i>
    </button>
  </div>

  <template v-if="menu.open">
    <div class="menu-mask" @click="closeMenu" @contextmenu.prevent="closeMenu"></div>
    <div class="menu" :style="{ left: menu.x + 'px', top: menu.y + 'px' }">
      <form v-if="renaming" class="rename-form" @submit.prevent="mSaveRename">
        <label for="tab-rename-input">重命名页签</label>
        <input id="tab-rename-input" v-model="renameDraft" maxlength="24" autofocus />
        <div>
          <button type="button" @click="renaming = false">返回</button
          ><button class="primary" :disabled="!renameDraft.trim()">保存</button>
        </div>
      </form>
      <template v-else>
        <button class="menu-item" @click="floatMenuTab">
          <i class="ri-picture-in-picture-line"></i>{{ windows.keys.includes(menu.key) ? '显示悬浮窗口' : '设为悬浮' }}
        </button>
        <button class="menu-item" :disabled="!menuTargetClosable" @click="mRename">
          <i class="ri-edit-line"></i>重命名页签
        </button>
        <button class="menu-item" :disabled="!menuTargetClosable" @click="mTogglePin">
          <i :class="menuTargetPinned ? 'ri-pushpin-2-line' : 'ri-pushpin-line'"></i>
          {{ menuTargetPinned ? '取消置顶' : '置顶页签' }}
        </button>
        <div class="menu-sep"></div>
        <button class="menu-item" :disabled="!menuTargetClosable" @click="mCloseCurrent">
          <i class="ri-close-line"></i>关闭当前
        </button>
        <button class="menu-item" @click="mCloseLeft">
          <i class="ri-arrow-left-line"></i>关闭左边
        </button>
        <button class="menu-item" @click="mCloseRight">
          <i class="ri-arrow-right-line"></i>关闭右边
        </button>
        <div class="menu-sep"></div>
        <button class="menu-item" @click="mCloseUnpinned">
          <i class="ri-filter-off-line"></i>关闭非置顶
        </button>
        <button class="menu-item danger" @click="mCloseAll">
          <i class="ri-close-circle-line"></i>关闭全部
        </button>
      </template>
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
.tab-open { display: flex; align-items: center; gap: 7px; min-width: 0; height: 100%; border: 0; padding: 0; background: transparent; color: inherit; font: inherit; cursor: pointer; }
.home-tab { border: 0; border-bottom: 2px solid transparent; }
.tab-open:focus-visible, .home-tab:focus-visible, .close:focus-visible, .scroll-btn:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
.home-tab {
  flex: 0 0 auto;
  margin-left: 10px;
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
.scroll-btn:hover:not(:disabled) {
  background: var(--wao-bg-hover-2);
}
.scroll-btn:disabled {
  opacity: 0.22;
  cursor: default;
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
  background: var(--wao-bg-hover);
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
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-weight: 500;
  border-bottom-color: var(--wao-primary);
}
.tab.active i {
  color: var(--wao-primary-text);
}
.tab .tab-pin {
  font-size: 12px;
  color: var(--wao-primary-text);
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
  background: var(--wao-bg-hover-2);
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
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop-2);
  padding: 5px;
  width: 166px;
  animation: waoPop 0.14s ease;
}
.rename-form {
  display: grid;
  gap: 7px;
  padding: 4px;
}
.rename-form label {
  color: var(--wao-text-weak);
  font-size: 10px;
}
.rename-form input {
  width: 100%;
  height: 32px;
  box-sizing: border-box;
  padding: 0 8px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.rename-form div {
  display: flex;
  justify-content: flex-end;
  gap: 5px;
}
.rename-form button {
  min-height: 28px;
  padding: 0 9px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
}
.rename-form button.primary {
  border-color: var(--wao-primary);
  background: var(--wao-primary);
  color: #fff;
}
.rename-form button:disabled {
  opacity: 0.5;
  cursor: default;
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
  background: var(--wao-bg-tab);
  margin: 4px 6px;
}
@media (max-width: 760px) {
  .tabbar-shell {
    height: var(--wao-tabbar-h);
  }
  .tabbar {
    padding: 0 2px;
    gap: 1px;
  }
  .home-tab {
    margin-left: 2px;
  }
  .scroll-btn {
    width: 20px;
    flex-basis: 20px;
    padding: 0;
  }
  .tab {
    gap: 3px;
    margin-top: 3px;
    padding: 0 5px 0 6px;
    border-radius: 6px 6px 0 0;
    font-size: 9px;
  }
  .tab i {
    font-size: 11px;
  }
  .close {
    width: 14px;
    height: 14px;
  }
  .close i {
    font-size: 10px;
  }
}
</style>
