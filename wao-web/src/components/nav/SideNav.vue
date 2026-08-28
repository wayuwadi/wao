<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from '@/composables/toast'
import { useIsMobile } from '@/composables/viewport'
import { useNavStore } from '@/stores/nav'
import { useSessionStore } from '@/stores/session'
import { useFavoritesStore } from '@/stores/favorites'
import type { Favorite } from '@/api/favorites'
import type { ModuleId } from '@/types'
import {
  externalAppItemId,
  type BuiltinMenuGroupCode,
  type MobileNavItemId,
  type NavItemId,
} from '@/config/nav'
import QuickLinks from '@/components/nav/QuickLinks.vue'

const emit = defineEmits<{ (e: 'open-search'): void; (e: 'new-note'): void }>()

const nav = useNavStore()
const session = useSessionStore()
const favorites = useFavoritesStore()
const route = useRoute()
const router = useRouter()
const isMobile = useIsMobile()
const menuRef = ref<HTMLElement | null>(null)
const mobileDragId = ref<MobileNavItemId | null>(null)
let mobilePressTimer: number | null = null
let mobilePressStart: { x: number; y: number } | null = null
let mobileOrderBeforeDrag: MobileNavItemId[] | null = null
let suppressMobileClick = false

const activeItem = computed<NavItemId | undefined>(() =>
  route.meta.tabKind === 'external'
    ? externalAppItemId(String(route.params.appId))
    : (route.meta.module as ModuleId | undefined),
)

const groups = computed(() => nav.groups)
const groupFingerprint = computed(() => groups.value.flatMap((group) => group.items).join('|'))
const mobileItems = computed(() => {
  const valid = nav.mobileItems.filter((id) => id === 'action:note' || nav.itemMeta(id))
  return valid.length ? valid : (['projects', 'action:note'] as MobileNavItemId[])
})

/**
 * 底部导航可能比屏幕宽；从地址栏、收藏或页面内跳转到后半段模块时，把当前入口带回视口。
 */
function revealActiveMobileItem() {
  if (!isMobile.value) return
  void nextTick(() => {
    menuRef.value?.querySelector<HTMLElement>('.item.active')?.scrollIntoView({
      block: 'nearest',
      inline: 'center',
    })
  })
}

watch([isMobile, activeItem, groupFingerprint], revealActiveMobileItem, { immediate: true })

onMounted(() => {
  nav.loadPersonalOrder()
  nav.loadMobileItems()
  nav.loadExternalApps()
  nav.loadBuiltinMenus()
  favorites.load()
})
onBeforeUnmount(cancelMobilePress)

function go(id: NavItemId) {
  const meta = nav.itemMeta(id)
  if (!meta) return
  if (meta.openMode === 'NEW_PAGE') {
    const target = meta.newPagePath ?? meta.path
    const href = /^https:\/\//i.test(target) ? target : router.resolve(target).href
    window.open(href, '_blank', 'noopener,noreferrer')
    return
  }
  router.push(meta.path)
}
function goSettings() {
  router.push('/settings')
}

function mobileItemMeta(id: MobileNavItemId) {
  if (id === 'action:note') {
    return { label: '小记', icon: 'ri-sticky-note-line' }
  }
  return nav.itemMeta(id)
}

function goMobile(id: MobileNavItemId) {
  if (suppressMobileClick) {
    suppressMobileClick = false
    return
  }
  if (id === 'action:note') emit('new-note')
  else go(id)
}

function cancelMobilePress() {
  if (mobilePressTimer !== null) window.clearTimeout(mobilePressTimer)
  mobilePressTimer = null
  mobilePressStart = null
}

function startMobilePress(id: MobileNavItemId, event: PointerEvent) {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  cancelMobilePress()
  mobilePressStart = { x: event.clientX, y: event.clientY }
  mobilePressTimer = window.setTimeout(() => {
    mobilePressTimer = null
    mobileDragId.value = id
    mobileOrderBeforeDrag = [...mobileItems.value]
    suppressMobileClick = true
    navigator.vibrate?.(18)
  }, 420)
}

function moveMobilePress(event: PointerEvent) {
  if (!mobileDragId.value) {
    const start = mobilePressStart
    if (start && Math.hypot(event.clientX - start.x, event.clientY - start.y) > 8)
      cancelMobilePress()
    return
  }
  event.preventDefault()
  const target = document
    .elementFromPoint(event.clientX, event.clientY)
    ?.closest<HTMLElement>('[data-mobile-nav-id]')?.dataset.mobileNavId as
    MobileNavItemId | undefined
  const from = mobileItems.value.indexOf(mobileDragId.value)
  const to = target ? mobileItems.value.indexOf(target) : -1
  if (from < 0 || to < 0 || from === to) return
  const reordered = [...mobileItems.value]
  const [item] = reordered.splice(from, 1)
  if (item) reordered.splice(to, 0, item)
  nav.mobileItems = reordered
}

function endMobilePress() {
  cancelMobilePress()
  const wasDragging = mobileDragId.value
  mobileDragId.value = null
  if (!wasDragging) return
  const changed = mobileOrderBeforeDrag?.join('|') !== nav.mobileItems.join('|')
  mobileOrderBeforeDrag = null
  if (changed) {
    void nav.saveMobileItems(nav.mobileItems).catch(() => toast('底栏顺序保存失败，请到工作台重试'))
  }
}
function orgClick() {
  toast('组织切换将在设置 · 组织管理中提供')
}

// ---- 桌面统一菜单拖拽与右键改名 ----
const dragId = ref<NavItemId | null>(null)
const dragOverId = ref<NavItemId | 'dock' | null>(null)
const renameDraft = ref<{
  id: NavItemId
  original: string
  value: string
  x: number
  y: number
  saving: boolean
  error: string
} | null>(null)
const contextMenu = ref<{ id: NavItemId; x: number; y: number; busy: boolean; error: string } | null>(null)

function groupCode(label: string): BuiltinMenuGroupCode {
  return (
    ({ '': 'ROOT', 公共: 'PUBLIC', 个人: 'PERSONAL', 工具: 'TOOLS' } as const)[label] ?? 'PERSONAL'
  )
}

function onDragStart(id: NavItemId, ev: DragEvent) {
  dragId.value = id
  ev.dataTransfer?.setData('text/plain', id)
  if (ev.dataTransfer) ev.dataTransfer.effectAllowed = 'move'
}
function onDragOver(id: NavItemId | 'dock') {
  if (dragId.value && dragId.value !== id) dragOverId.value = id
}

async function onDrop(
  placement: 'MENU' | 'DOCK',
  group: BuiltinMenuGroupCode,
  targetId?: NavItemId,
) {
  const from = dragId.value
  dragId.value = null
  dragOverId.value = null
  if (!from || from === targetId) return
  try {
    await nav.moveMenu(from, placement, group, targetId)
    toast(placement === 'DOCK' ? '菜单已移到底部图标区' : '菜单位置已保存', 'ri-check-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '菜单保存失败，请重试', 'ri-error-warning-line')
  }
}

function onDropDock(targetId?: NavItemId) {
  const from = dragId.value
  const configured = from ? nav.menuConfig(from) : undefined
  void onDrop('DOCK', (configured?.group ?? 'PERSONAL') as BuiltinMenuGroupCode, targetId)
}

function onDragEnd() {
  dragId.value = null
  dragOverId.value = null
}

function openRename(event: MouseEvent, id: NavItemId) {
  const label = nav.itemMeta(id)?.label
  if (!label) return
  renameDraft.value = {
    id,
    original: label,
    value: label,
    x: Math.min(event.clientX, window.innerWidth - 286),
    y: Math.min(event.clientY, window.innerHeight - 180),
    saving: false,
    error: '',
  }
}

function openContextMenu(event: MouseEvent, id: NavItemId) {
  if (nav.collapsed || isMobile.value) return
  contextMenu.value = {
    id,
    x: Math.min(event.clientX, window.innerWidth - 190),
    y: Math.min(event.clientY, window.innerHeight - 190),
    busy: false,
    error: '',
  }
}

function renameFromContext() {
  const menu = contextMenu.value
  if (!menu) return
  const event = { clientX: menu.x, clientY: menu.y } as MouseEvent
  contextMenu.value = null
  openRename(event, menu.id)
}

async function mutateContext(action: 'hide' | 'pin') {
  const menu = contextMenu.value
  if (!menu || menu.busy) return
  menu.busy = true
  menu.error = ''
  try {
    if (action === 'hide') await nav.setMenuVisibility(menu.id, false)
    else await nav.pinMenu(menu.id)
    toast(action === 'hide' ? '菜单已隐藏，可在设置中恢复' : '菜单已置顶', 'ri-check-line')
    contextMenu.value = null
  } catch (error) {
    menu.busy = false
    menu.error = error instanceof Error ? error.message : '菜单操作失败'
  }
}

function closeRename(force = false) {
  const draft = renameDraft.value
  if (!draft) return
  if (
    !force &&
    draft.value.trim() !== draft.original &&
    !window.confirm('菜单名称尚未保存，确认放弃吗？')
  )
    return
  renameDraft.value = null
}

async function saveRename() {
  const draft = renameDraft.value
  if (!draft || draft.saving) return
  draft.saving = true
  draft.error = ''
  try {
    await nav.renameMenu(draft.id, draft.value)
    toast('菜单名称已保存', 'ri-check-line')
    renameDraft.value = null
  } catch (error) {
    draft.error = error instanceof Error ? error.message : '菜单名称保存失败'
  } finally {
    if (renameDraft.value) renameDraft.value.saving = false
  }
}

// ---- 收藏分组 ----
const FAV_ICONS: Record<Favorite['type'], string> = {
  module: 'ri-dashboard-line',
  project: 'ri-stack-line',
  task: 'ri-checkbox-circle-line',
  doc: 'ri-file-text-line',
}
function openFavorite(f: Favorite) {
  if (f.type === 'module') router.push(`/${f.targetId}`)
  else if (f.type === 'project') router.push(`/projects/${f.targetId}`)
  else if (f.type === 'task') router.push(`/projects/${f.parentId ?? ''}?task=${f.targetId}`)
  else router.push(`/kb/${f.parentId ?? ''}?doc=${f.targetId}`)
}
function removeFavorite(f: Favorite, event: MouseEvent) {
  event.stopPropagation()
  favorites.toggle(f.type, f.targetId, f.label, f.parentId)
}
</script>

<template>
  <aside class="side" :class="{ collapsed: nav.collapsed }">
    <div class="brand-row">
      <div class="brand-mark">WAO</div>
      <button v-if="!nav.collapsed" class="org" @click="orgClick">
        <span class="org-name">{{ session.org.name }}</span>
        <i class="ri-arrow-down-s-line"></i>
      </button>
    </div>

    <div v-if="!nav.collapsed" class="search-row">
      <button class="search" @click="emit('open-search')">
        <i class="ri-search-line"></i>
        <span class="search-text">搜索或提问 AI</span>
        <span class="kbd">⌘K</span>
      </button>
    </div>

    <nav ref="menuRef" class="menu" aria-label="模块导航">
      <template v-if="isMobile">
        <button
          v-for="id in mobileItems"
          :key="id"
          class="item mobile-nav-item"
          :class="{ active: activeItem === id, dragging: mobileDragId === id }"
          :title="mobileItemMeta(id)?.label"
          :data-mobile-nav-id="id"
          @click="goMobile(id)"
          @pointerdown="startMobilePress(id, $event)"
          @pointermove="moveMobilePress"
          @pointerup="endMobilePress"
          @pointercancel="endMobilePress"
        >
          <i :class="mobileItemMeta(id)?.icon"></i>
          <span class="item-label">{{ mobileItemMeta(id)?.label }}</span>
        </button>
        <button
          class="item mobile-more-item"
          :class="{ active: activeItem === 'navigation' }"
          title="更多"
          @click="go('navigation')"
        >
          <i class="ri-apps-2-line"></i>
          <span class="item-label">更多</span>
        </button>
      </template>

      <template v-for="g in groups" v-else :key="g.label || 'root'">
        <button
          v-if="g.label && !nav.collapsed"
          class="group-label"
          title="折叠 / 展开分组"
          @dragover.prevent="onDragOver(g.items[0] ?? 'dock')"
          @drop.prevent="onDrop('MENU', groupCode(g.label))"
          @click="nav.toggleGroup(g.label)"
        >
          {{ g.label }}
          <i :class="nav.isFolded(g.label) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>
        </button>
        <div v-else-if="g.label && nav.collapsed" class="group-gap"></div>

        <!-- 手机没有分组展开按钮，必须始终展示全部可见模块，不能复用桌面的折叠状态。 -->
        <div
          v-if="isMobile || !nav.isFolded(g.label) || nav.collapsed"
          class="items"
          @dragover.prevent
          @drop.prevent="onDrop('MENU', groupCode(g.label))"
        >
          <button
            v-for="id in g.items"
            :key="id"
            class="item"
            :class="{
              active: activeItem === id && !(id === 'home' && route.query.console),
              center: nav.collapsed,
              dragging: dragId === id,
              'drag-over': dragOverId === id,
            }"
            :title="nav.itemMeta(id)?.label"
            :draggable="!nav.collapsed"
            @click="go(id)"
            @contextmenu.prevent="openContextMenu($event, id)"
            @dragstart="onDragStart(id, $event)"
            @dragover.stop.prevent="onDragOver(id)"
            @drop.stop.prevent="onDrop('MENU', groupCode(g.label), id)"
            @dragend="onDragEnd"
          >
            <i :class="nav.itemMeta(id)?.icon"></i>
            <span v-if="!nav.collapsed" class="item-label">{{ nav.itemMeta(id)?.label }}</span>
          </button>
        </div>

        <!-- 收藏分组紧跟首页之后：有收藏才出现，手机底栏不展示 -->
        <template v-if="!g.label && favorites.items.length && !nav.collapsed">
          <button
            class="group-label fav-group"
            title="折叠 / 展开分组"
            @click="nav.toggleGroup('收藏')"
          >
            收藏
            <i :class="nav.isFolded('收藏') ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>
          </button>
          <div v-if="!nav.isFolded('收藏')" class="items">
            <button
              v-for="f in favorites.items"
              :key="f.type + f.targetId"
              class="item fav-item"
              :title="f.label"
              @click="openFavorite(f)"
            >
              <i :class="FAV_ICONS[f.type]"></i>
              <span class="item-label">{{ f.label }}</span>
              <span class="fav-remove" title="取消收藏" @click="removeFavorite(f, $event)"
                ><i class="ri-star-fill"></i
              ></span>
            </button>
          </div>
        </template>
      </template>
    </nav>

    <!-- 停靠菜单仍由菜单注册表驱动；快捷链接继续使用独立的成员级真实配置。 -->
    <div
      v-if="!nav.collapsed"
      class="quick-row"
      :class="{ 'drag-over': dragOverId === 'dock' }"
      aria-label="底部图标区"
      @dragover.prevent="onDragOver('dock')"
      @dragleave="dragOverId === 'dock' && (dragOverId = null)"
      @drop.prevent="onDropDock()"
    >
      <QuickLinks />
      <button
        v-for="id in nav.dockItems"
        :key="id"
        type="button"
        class="dock-item"
        :class="{
          active: activeItem === id,
          dragging: dragId === id,
          'drag-over': dragOverId === id,
        }"
        :title="nav.itemMeta(id)?.label"
        draggable="true"
        @click="go(id)"
        @contextmenu.prevent="openContextMenu($event, id)"
        @dragstart="onDragStart(id, $event)"
        @dragover.stop.prevent="onDragOver(id)"
        @drop.stop.prevent="onDropDock(id)"
        @dragend="onDragEnd"
      >
        <i :class="nav.itemMeta(id)?.icon"></i>
      </button>
      <span v-if="dragId && !nav.dockItems.length" class="dock-hint">拖到这里变为图标</span>
      <i
        v-if="nav.menuMutationState === 'saving'"
        class="ri-loader-4-line dock-saving"
        title="正在保存菜单"
      ></i>
    </div>

    <div class="user-row">
      <button
        class="user"
        :class="{ center: nav.collapsed }"
        title="成员、权限与系统设置"
        @click="goSettings"
      >
        <span class="avatar" :style="{ background: session.currentUser.color }">{{
          session.currentUser.initial
        }}</span>
        <template v-if="!nav.collapsed">
          <span class="user-meta">
            <span class="user-name">{{ session.currentUser.name }}</span>
            <span class="user-role">{{ session.currentUser.role }}</span>
          </span>
          <i class="ri-settings-3-line"></i>
        </template>
      </button>
    </div>

    <Teleport to="body">
      <div v-if="contextMenu" class="menu-action-mask" @mousedown.self="contextMenu = null" @contextmenu.prevent>
        <div class="menu-actions" :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }">
          <button type="button" :disabled="contextMenu.busy" @click="renameFromContext"><i class="ri-edit-line"></i>重命名</button>
          <button type="button" :disabled="contextMenu.busy" @click="mutateContext('pin')"><i class="ri-pushpin-line"></i>置顶</button>
          <button type="button" class="danger" :disabled="contextMenu.busy" @click="mutateContext('hide')"><i class="ri-eye-off-line"></i>隐藏</button>
          <p v-if="contextMenu.error" role="alert">{{ contextMenu.error }}，可重试。</p>
        </div>
      </div>
      <div
        v-if="renameDraft"
        class="rename-mask"
        @contextmenu.prevent
        @mousedown.self="closeRename()"
      >
        <form
          class="rename-menu"
          :style="{ left: `${renameDraft.x}px`, top: `${renameDraft.y}px` }"
          @submit.prevent="saveRename"
        >
          <label for="side-menu-rename">重命名菜单</label>
          <input id="side-menu-rename" v-model="renameDraft.value" maxlength="24" autofocus />
          <p v-if="renameDraft.error" role="alert">{{ renameDraft.error }}，可修改后重试。</p>
          <div>
            <button type="button" :disabled="renameDraft.saving" @click="closeRename()">
              取消
            </button>
            <button
              type="submit"
              class="primary"
              :disabled="renameDraft.saving || !renameDraft.value.trim()"
            >
              {{ renameDraft.saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </form>
      </div>
    </Teleport>
  </aside>
</template>

<style scoped>
.side {
  width: var(--wao-side-w);
  transition: width 0.18s ease;
  background: var(--wao-bg-side);
  border-right: 1px solid var(--wao-line);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  overflow: hidden;
}
.side.collapsed {
  width: var(--wao-side-w-collapsed);
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 14px 14px 10px;
  min-width: 0;
}
.brand-mark {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--wao-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 10px;
  flex-shrink: 0;
}
.org {
  display: flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 6px;
  min-width: 0;
}
.org:hover {
  background: var(--wao-bg-hover-2);
}
.org-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
}
.org i {
  color: var(--wao-text-weak);
  font-size: 14px;
}

.search-row {
  padding: 2px 10px 6px;
}
.search {
  width: 100%;
  height: 32px;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 8px;
  padding: 0 10px;
  cursor: pointer;
  color: var(--wao-text-weak);
  font-size: 12.5px;
}
.search:hover {
  border-color: var(--wao-line-strong);
}
.search-text {
  flex: 1;
  text-align: left;
}
.kbd {
  font-size: 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: 5px;
  padding: 0 5px;
  background: var(--wao-bg-page);
}

.menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 4px 0 8px;
}
.group-label {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: none;
  padding: 14px 18px 5px;
  font-size: 11px;
  color: var(--wao-text-weak);
  letter-spacing: 1.5px;
  cursor: pointer;
  text-align: left;
}
.group-label:hover {
  color: var(--wao-text-3);
}
.group-label i {
  font-size: 13px;
}
.group-gap {
  height: 12px;
  border-bottom: 1px solid var(--wao-line);
  margin: 0 12px 12px;
}
.items {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 0 8px;
}
.item {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 32px;
  padding: 0 9px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-2);
  font-size: 13px;
  cursor: pointer;
  justify-content: flex-start;
}
.item.center {
  justify-content: center;
}
.item i {
  font-size: 16px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.item-label {
  flex: 1;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fav-remove {
  display: inline-flex;
  padding: 4px;
  color: var(--wao-warning-strong);
  border-radius: 5px;
}
.fav-remove:hover {
  background: var(--wao-warning-bg);
}
.fav-remove i {
  color: inherit;
  font-size: 14px;
}
.item:hover {
  background: var(--wao-bg-hover-2);
}
.item.active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.item.active:hover {
  background: var(--wao-primary-soft);
}
.item.active i {
  color: var(--wao-primary);
}
.item.active .item-label {
  font-weight: 600;
}
.item.dragging {
  opacity: 0.45;
}
.item.drag-over {
  outline: 2px dashed var(--wao-primary);
  outline-offset: -2px;
}
.item[draggable='true'] {
  cursor: grab;
}

.quick-row {
  padding: 4px 10px;
  border-top: 1px solid var(--wao-line);
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 40px;
  transition:
    background 0.15s ease,
    box-shadow 0.15s ease;
}
.quick-row.drag-over {
  background: var(--wao-primary-soft);
  box-shadow: inset 0 0 0 2px var(--wao-primary);
}
.dock-item {
  width: 32px;
  height: 32px;
  display: grid;
  flex: none;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: grab;
}
.dock-item:hover {
  background: var(--wao-bg-hover-2);
}
.dock-item.active {
  color: var(--wao-primary);
  background: var(--wao-primary-soft);
}
.dock-item.dragging {
  opacity: 0.42;
}
.dock-item.drag-over {
  outline: 2px dashed var(--wao-primary);
  outline-offset: -2px;
}
.dock-item i {
  font-size: 16px;
}
.dock-hint {
  color: var(--wao-text-faint);
  font-size: 11px;
}
.dock-saving {
  margin-left: auto;
  color: var(--wao-primary);
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.menu-action-mask,
.rename-mask {
  position: fixed;
  z-index: 1300;
  inset: 0;
}
.menu-actions { position: fixed; width: 170px; box-sizing: border-box; padding: 6px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop-2); display: grid; gap: 2px; }
.menu-actions button { min-height: 34px; padding: 0 9px; border: 0; border-radius: 7px; background: transparent; color: var(--wao-text); cursor: pointer; display: flex; align-items: center; gap: 8px; font-size: 12px; }
.menu-actions button:hover:not(:disabled) { background: var(--wao-bg-hover-2); }.menu-actions button.danger { color: var(--wao-danger-strong); }.menu-actions button:disabled { opacity: .55; cursor: default; }
.menu-actions p { margin: 5px; color: var(--wao-danger-strong); font-size: 10px; line-height: 1.4; }
.rename-menu {
  position: fixed;
  width: 260px;
  box-sizing: border-box;
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  box-shadow: 0 12px 36px rgba(16, 18, 28, 0.2);
}
.rename-menu label {
  display: block;
  margin-bottom: 8px;
  color: var(--wao-text-2);
  font-size: 12px;
  font-weight: 600;
}
.rename-menu input {
  width: 100%;
  height: 34px;
  box-sizing: border-box;
  padding: 0 9px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.rename-menu input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 2px var(--wao-primary-soft);
}
.rename-menu p {
  margin: 8px 0 0;
  color: var(--wao-danger-strong);
  font-size: 11px;
}
.rename-menu > div {
  display: flex;
  justify-content: flex-end;
  gap: 7px;
  margin-top: 10px;
}
.rename-menu button {
  min-height: 30px;
  padding: 0 11px;
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  cursor: pointer;
}
.rename-menu button.primary {
  border-color: var(--wao-primary);
  background: var(--wao-primary);
  color: #fff;
}
.rename-menu button:disabled {
  opacity: 0.55;
  cursor: default;
}
.user-row {
  border-top: 1px solid var(--wao-line);
  padding: 10px;
}
.user {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  border: none;
  background: none;
  border-radius: 8px;
  padding: 6px;
  cursor: pointer;
  justify-content: flex-start;
}
.user.center {
  justify-content: center;
}
.user:hover {
  background: var(--wao-bg-hover-2);
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.user-meta {
  flex: 1;
  min-width: 0;
  text-align: left;
  display: flex;
  flex-direction: column;
}
.user-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--wao-text);
}
.user-role {
  font-size: 11px;
  color: var(--wao-text-weak);
}
.user-row i {
  color: var(--wao-text-weak);
  font-size: 15px;
}

@media (max-width: 760px) {
  .side,
  .side.collapsed {
    position: fixed;
    z-index: 70;
    bottom: 0;
    left: 0;
    width: 100%;
    height: calc(64px + env(safe-area-inset-bottom));
    padding-bottom: env(safe-area-inset-bottom);
    border-top: 1px solid var(--wao-line);
    border-right: 0;
    background: color-mix(in srgb, var(--wao-bg-card) 97%, transparent);
    box-shadow: 0 -4px 18px rgba(16, 18, 28, 0.08);
    overflow: visible;
  }
  .brand-row,
  .search-row,
  .group-label,
  .group-gap,
  .fav-item,
  .user-row,
  .quick-row {
    display: none;
  }
  .menu {
    display: flex;
    align-items: stretch;
    justify-content: space-around;
    gap: 2px;
    overflow-x: hidden;
    overflow-y: hidden;
    padding: 4px 6px;
    scrollbar-width: none;
  }
  .menu::-webkit-scrollbar {
    display: none;
  }
  .item,
  .item.center {
    width: auto;
    min-width: 58px;
    max-width: 88px;
    flex: 1 1 0;
    height: 54px;
    padding: 4px 2px;
    flex-direction: column;
    justify-content: center;
    gap: 2px;
    border-radius: 9px;
  }
  .item-label {
    display: block;
    max-width: 54px;
    flex: initial;
    text-align: center;
    font-size: 10px;
    line-height: 14px;
  }
  .item i {
    font-size: 18px;
  }
  .mobile-nav-item {
    touch-action: pan-y;
    user-select: none;
    -webkit-user-select: none;
  }
  .mobile-nav-item.dragging {
    opacity: 0.82;
    background: var(--wao-primary-soft);
    transform: scale(1.06);
    box-shadow: 0 4px 14px color-mix(in srgb, var(--wao-primary) 22%, transparent);
  }
}
</style>
