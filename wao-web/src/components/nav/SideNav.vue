<script setup lang="ts">
import WaoLogo from '@/components/WaoLogo.vue'
import { useTopShortcutsStore, favoriteShortcut } from '@/stores/topShortcuts'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from '@/composables/toast'
import { useIsMobile } from '@/composables/viewport'
import { useMenuDrag } from '@/composables/menuDrag'
import { useUiStore } from '@/stores/ui'
import { useNavStore } from '@/stores/nav'
import { useSessionStore } from '@/stores/session'
import { useFavoritesStore } from '@/stores/favorites'
import type { Favorite } from '@/api/favorites'
import type { ModuleId } from '@/types'
import { externalAppItemId, type NavItemId } from '@/config/nav'
import { hasDesktopToolbar } from '@/utils/desktopToolbar'
const desktopToolbar = hasDesktopToolbar()
import { useTabsStore } from '@/stores/tabs'
import { useDataStore } from '@/stores/data'
import { useKnowledgeFavoritesStore } from '@/stores/knowledgeFavorites'
const data = useDataStore()
const tabs = useTabsStore()
const knowledgeFavorites = useKnowledgeFavoritesStore()
const favoriteKbs = computed(() => data.kbs.filter((kb) => knowledgeFavorites.ids.includes(kb.id)))
import QuickLinks from '@/components/nav/QuickLinks.vue'

const emit = defineEmits<{ (e: 'open-search'): void }>()

const ui = useUiStore()
const nav = useNavStore()
const session = useSessionStore()
const favorites = useFavoritesStore()
const route = useRoute()
const shortcuts = useTopShortcutsStore()
const router = useRouter()
const isMobile = useIsMobile()
const menuRef = ref<HTMLElement | null>(null)

const activeItem = computed<NavItemId | undefined>(() =>
  route.meta.tabKind === 'external'
    ? externalAppItemId(String(route.params.appId))
    : (route.meta.module as ModuleId | undefined),
)

const groups = computed(() => nav.groups)
const groupFingerprint = computed(() => groups.value.flatMap((group) => group.items).join('|'))

/**
 * 手机左栏与正文分别滚动；从地址、收藏或页面内跳转时，把当前入口带回菜单可见区域。
 */
function revealActiveMobileItem() {
  if (!isMobile.value) return
  void nextTick(() => {
    menuRef.value?.querySelector<HTMLElement>('.item.active')?.scrollIntoView({
      block: 'nearest',
      inline: 'nearest',
    })
  })
}

watch([isMobile, activeItem, groupFingerprint], revealActiveMobileItem, { immediate: true })

onMounted(() => {
  nav.loadPersonalOrder()
  nav.loadExternalApps()
  nav.loadBuiltinMenus()
  favorites.load()
  void knowledgeFavorites.load()
})

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

// ---- 桌面统一菜单拖拽与右键改名 ----
const { dragId, dragOverId, onDragStart, onDragOver, onDrop, onDropDock, onDragEnd } = useMenuDrag()
const renameDraft = ref<{
  id: NavItemId
  original: string
  value: string
  x: number
  y: number
  saving: boolean
  error: string
} | null>(null)
const contextMenu = ref<{
  id: NavItemId
  x: number
  y: number
  busy: boolean
  error: string
} | null>(null)

function openRename(event: MouseEvent, id: NavItemId) {
  if (isMobile.value) return
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
    <div class="side-heading">
      <WaoLogo v-if="!desktopToolbar && !nav.collapsed" wordmark />
      <button
        class="side-collapse"
        :aria-label="nav.collapsed ? '展开侧栏' : '折叠侧栏'"
        :title="nav.collapsed ? '展开侧栏' : '折叠侧栏'"
        :aria-expanded="!nav.collapsed"
        @click="nav.toggleCollapse()"
      >
        <i :class="nav.collapsed ? 'ri-side-bar-line' : 'ri-sidebar-fold-line'"></i
        ><span v-if="!nav.collapsed && desktopToolbar">折叠侧栏</span>
      </button>
    </div>

    <div v-if="!nav.collapsed" class="search-row">
      <button class="search" aria-label="搜索功能与内容" aria-keyshortcuts="Meta+K Control+K" @click="emit('open-search')">
        <i class="ri-search-line"></i>
        <span class="search-text">搜索功能与内容</span>
        <span class="kbd">⌘K</span>
      </button>
    </div>

    <button
      v-if="!nav.menuConfig('navigation')?.visible"
      class="all-functions"
      :title="nav.collapsed ? '全部功能' : undefined"
      @click="router.push('/navigation')"
    >
      <i class="ri-apps-2-line"></i><span v-if="!nav.collapsed">全部功能</span>
    </button>
    <div v-if="nav.menuMutationError && !nav.collapsed" class="nav-save-error" role="alert">
      {{ nav.menuMutationError
      }}<button @click="router.push('/settings?tab=navigation')">处理未保存的布局</button>
    </div>
    <nav ref="menuRef" class="menu" aria-label="模块导航">
      <div v-if="desktopToolbar" class="items">
        <button
          class="item"
          :class="{ active: ui.chatOpen, center: nav.collapsed }"
          title="聊天"
          data-chat-trigger
          @click="ui.toggleChat()"
        >
          <i class="ri-chat-3-line"></i><span v-if="!nav.collapsed" class="item-label">聊天</span
          ><span v-if="ui.chatUnreadCount" class="side-chat-unread">{{
            ui.chatUnreadCount > 99 ? '99+' : ui.chatUnreadCount
          }}</span>
        </button>
      </div>
      <template v-for="g in groups" :key="g.id">
        <button
          v-if="g.label && !nav.collapsed"
          class="group-label"
          :title="isMobile ? g.label : '折叠 / 展开分组'"
          @dragover.prevent="onDragOver(g.items[0] ?? 'dock')"
          @drop.prevent="onDrop('MENU', g.id)"
          @click="!isMobile && nav.toggleGroup(g.id)"
        >
          {{ desktopToolbar && g.id === 'PUBLIC' && g.label === '公共' ? '协作' : g.label }}
          <i :class="nav.isFolded(g.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>
        </button>
        <div v-else-if="g.label && nav.collapsed" class="group-gap"></div>

        <!-- 手机没有分组展开按钮，必须始终展示全部可见模块，不能复用桌面的折叠状态。 -->
        <div
          v-if="isMobile || !nav.isFolded(g.id) || nav.collapsed"
          class="items"
          @dragover.prevent
          @drop.prevent="onDrop('MENU', g.id)"
        >
          <template v-for="id in g.items" :key="id">
          <button
            class="item"
            :class="{
              active: activeItem === id && !(id === 'home' && route.query.console),
              center: nav.collapsed,
              dragging: dragId === id,
              'drag-over': !!dragId && dragOverId === id,
            }"
            :title="nav.itemMeta(id)?.label"
            :aria-label="nav.itemMeta(id)?.label"
            :draggable="!isMobile && !nav.collapsed && nav.canMoveMenus"
            @click="go(id)"
            @contextmenu.prevent="openContextMenu($event, id)"
            @dragstart="onDragStart(id, $event)"
            @dragover.stop.prevent="onDragOver(id)"
            @drop.stop.prevent="onDrop('MENU', g.id, id)"
            @dragend="onDragEnd"
          >
            <i :class="nav.itemMeta(id)?.icon"></i>
            <span class="item-label" :class="{ 'compact-label': nav.collapsed }">{{ nav.itemMeta(id)?.label }}</span>
          </button>
          <div v-if="id === 'knowledge' && favoriteKbs.length" class="knowledge-children" :class="{ compact: nav.collapsed }">
            <button v-for="kb in favoriteKbs" :key="kb.id" class="item" :class="{ center: nav.collapsed, active: route.params.id === kb.id && route.name === 'kb' }" :title="kb.name" @click="router.push(tabs.tabs.find((tab) => tab.key === `kb:${kb.id}`)?.path || `/kb/${kb.id}`)">
              <span class="knowledge-child-dot" aria-hidden="true">·</span><span class="item-label" :class="{ 'compact-label': nav.collapsed }">{{ kb.name }}</span>
            </button>
          </div>
          </template>
        </div>

        <!-- 收藏分组紧跟首页之后；手机与桌面复用同一份真实收藏。 -->
        <template v-if="!g.label && favorites.items.length && !nav.collapsed">
          <button
            class="group-label fav-group"
            :title="isMobile ? '收藏' : '折叠 / 展开分组'"
            @click="!isMobile && nav.toggleGroup('收藏')"
          >
            收藏
            <i :class="nav.isFolded('收藏') ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>
          </button>
          <div v-if="isMobile || !nav.isFolded('收藏')" class="items">
            <button
              v-for="f in favorites.items"
              :key="f.type + f.targetId"
              class="item fav-item"
              :title="f.label"
              :draggable="!isMobile && !!favoriteShortcut(f)"
              @dragstart="shortcuts.start(favoriteShortcut(f), $event)"
              @dragend="shortcuts.end"
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
      :class="{ 'drag-over': !!dragId && dragOverId === 'dock' }"
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
          'drag-over': !!dragId && dragOverId === id,
        }"
        :title="nav.itemMeta(id)?.label"
            :aria-label="nav.itemMeta(id)?.label"
        :draggable="!isMobile && nav.canMoveMenus"
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
      <div
        v-if="contextMenu"
        class="menu-action-mask"
        @mousedown.self="contextMenu = null"
        @contextmenu.prevent
      >
        <div
          class="menu-actions"
          :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
        >
          <button type="button" :disabled="contextMenu.busy" @click="renameFromContext">
            <i class="ri-edit-line"></i>重命名
          </button>
          <button type="button" :disabled="contextMenu.busy" @click="mutateContext('pin')">
            <i class="ri-pushpin-line"></i>置顶
          </button>
          <button
            type="button"
            class="danger"
            :disabled="contextMenu.busy"
            @click="mutateContext('hide')"
          >
            <i class="ri-eye-off-line"></i>隐藏
          </button>
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
.knowledge-children { margin-left: 22px; border-left: 1px solid var(--wao-line); padding-left: 4px; }
.knowledge-children.compact { margin-left: 0; padding-left: 0; border-left: 0; }
.knowledge-child-dot { color: var(--wao-text-weak); }
.side.collapsed .item.center { height: auto; min-height: 54px; flex-direction: column; gap: 3px; padding: 7px 2px; }
.side.collapsed .item .compact-label { display: block; width: 100%; text-align: center; font-size: 10px; line-height: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.side-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 10px;
  min-height: 36px;
  flex-shrink: 0;
}
.collapsed .side-heading {
  justify-content: center;
  padding: 4px;
}
.side-collapse {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex-shrink: 0;
  height: 30px;
  margin: 0;
  padding: 0 8px;
  border: 1px solid var(--wao-line);
  border-radius: 5px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
  font-size: 12px;
}
.side-collapse:hover {
  background: var(--wao-bg-hover);
  color: var(--wao-text);
}

.side-chat-unread {
  margin-left: auto;
  border-radius: 9px;
  background: var(--wao-danger);
  color: #fff;
  padding: 1px 5px;
  font-size: 10px;
}
.all-functions {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 4px 12px 8px;
  padding: 9px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  cursor: pointer;
  font-size: 12px;
}
.nav-save-error {
  margin: 0 12px 8px;
  padding: 8px;
  border-radius: 6px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: 11px;
}
.nav-save-error button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}

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
.menu-actions {
  position: fixed;
  width: 170px;
  box-sizing: border-box;
  padding: 6px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-pop-2);
  display: grid;
  gap: 2px;
}
.menu-actions button {
  min-height: 34px;
  padding: 0 9px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.menu-actions button:hover:not(:disabled) {
  background: var(--wao-bg-hover-2);
}
.menu-actions button.danger {
  color: var(--wao-danger-strong);
}
.menu-actions button:disabled {
  opacity: 0.55;
  cursor: default;
}
.menu-actions p {
  margin: 5px;
  color: var(--wao-danger-strong);
  font-size: 10px;
  line-height: 1.4;
}
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
  .side-heading :deep(.wao-logo-name) { display: none; }
  .side-heading :deep(.wao-logo-symbol) { width: 30px; height: 22px; }
  .side,
  .side.collapsed {
    position: relative;
    z-index: 70;
    width: clamp(88px, 24vw, 104px);
    height: 100dvh;
    padding-bottom: env(safe-area-inset-bottom);
    border-top: 0;
    border-right: 1px solid var(--wao-line);
    background: var(--wao-bg-side);
    box-shadow: 4px 0 14px rgba(16, 18, 28, 0.05);
    overflow: hidden;
  }
  .side.collapsed {
    width: 48px;
  }
  .brand-row {
    gap: 5px;
    padding: 8px 6px 5px;
  }
  .brand-mark {
    width: 22px;
    height: 22px;
    border-radius: 6px;
    font-size: 8px;
  }
  .org {
    gap: 1px;
    padding: 1px;
  }
  .org-name {
    overflow: hidden;
    font-size: 9.5px;
    line-height: 1.2;
    text-overflow: ellipsis;
  }
  .org i {
    display: none;
  }
  .search-row {
    padding: 1px 5px 4px;
  }
  .search {
    height: 27px;
    gap: 4px;
    padding: 0 6px;
    border-radius: 6px;
    font-size: 9px;
  }
  .search-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .kbd,
  .quick-row {
    display: none;
  }
  .menu {
    display: block;
    overflow-x: hidden;
    overflow-y: auto;
    padding: 1px 0 4px;
    overscroll-behavior: contain;
  }
  .group-label {
    gap: 2px;
    padding: 7px 7px 2px;
    font-size: 8px;
    letter-spacing: 0.08em;
  }
  .group-label i {
    display: none;
  }
  .group-gap {
    height: 7px;
    margin: 0 6px 5px;
  }
  .items {
    gap: 0;
    padding: 0 4px;
  }
  .item,
  .item.center {
    width: 100%;
    min-width: 0;
    max-width: none;
    height: 27px;
    padding: 0 5px;
    flex-direction: row;
    justify-content: flex-start;
    gap: 4px;
    border-radius: 6px;
    font-size: 10px;
  }
  .item-label {
    display: block;
    min-width: 0;
    max-width: none;
    flex: 1;
    text-align: left;
    font-size: 10px;
    line-height: 1.2;
  }
  .item i {
    font-size: 12px;
  }
  .side.collapsed .item.center {
    justify-content: center;
    padding: 0;
  }
  .fav-remove {
    display: none;
  }
  .user-row {
    padding: 4px;
  }
  .user {
    min-height: 28px;
    gap: 4px;
    padding: 3px;
  }
  .avatar {
    width: 21px;
    height: 21px;
    font-size: 9px;
  }
  .user-name {
    overflow: hidden;
    font-size: 9px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .user-role,
  .user-row .user > i {
    display: none;
  }
}
</style>
