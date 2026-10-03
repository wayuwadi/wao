<script setup lang="ts">
import WaoLogo from '@/components/WaoLogo.vue'
import { useTopShortcutsStore, favoriteShortcut } from '@/stores/topShortcuts'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from '@/composables/toast'
import { useIsMobile } from '@/composables/viewport'
import { useMenuDrag } from '@/composables/menuDrag'
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
import MenuGroupPicker from '@/components/nav/MenuGroupPicker.vue'

const emit = defineEmits<{ (e: 'open-search'): void; (e: 'navigate'): void }>()

const nav = useNavStore()
// 手机完整菜单不继承桌面侧栏的窄条偏好，也不改写该偏好。
const isCollapsed = computed(() => !isMobile.value && nav.collapsed)
const session = useSessionStore()
const favorites = useFavoritesStore()
const favoriteProjects = computed(() => favorites.items.filter((item) => item.type === 'project'))
const otherFavorites = computed(() => favorites.items.filter((item) => item.type === 'module'))
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
  emit('navigate')
  if (meta.openMode === 'NEW_PAGE') {
    const target = meta.newPagePath ?? meta.path
    const href = /^https:\/\//i.test(target) ? target : router.resolve(target).href
    window.open(href, '_blank', 'noopener,noreferrer')
    return
  }
  router.push(meta.path)
}
function goSettings() {
  emit('navigate')
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
  id: NavItemId | null
  groupId: string | null
  x: number
  y: number
  busy: boolean
  error: string
} | null>(null)
const groupCreateDraft = ref<{ value: string; error: string; saving: boolean } | null>(null)
const groupPicker = ref<string | null>(null)
watch(() => session.userId, () => { groupPicker.value = null })

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
  if (isCollapsed.value || isMobile.value) return
  contextMenu.value = {
    id,
    groupId: null,
    x: Math.min(event.clientX, window.innerWidth - 190),
    y: Math.min(event.clientY, window.innerHeight - 190),
    busy: false,
    error: '',
  }
}

function openGroupContextMenu(event: MouseEvent, groupId: string) {
  if (isCollapsed.value || isMobile.value || groupId === 'ROOT') return
  contextMenu.value = {
    id: null,
    groupId,
    x: Math.min(event.clientX, window.innerWidth - 220),
    y: Math.min(event.clientY, window.innerHeight - 150),
    busy: false,
    error: '',
  }
}

function renameFromContext() {
  const menu = contextMenu.value
  if (!menu?.id) return
  const event = { clientX: menu.x, clientY: menu.y } as MouseEvent
  contextMenu.value = null
  openRename(event, menu.id)
}

async function mutateContext(action: 'hide' | 'pin') {
  const menu = contextMenu.value
  if (!menu?.id || menu.busy) return
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

function createGroupFromContext() {
  contextMenu.value = null
  groupCreateDraft.value = { value: '', error: '', saving: false }
}

async function saveNewGroup() {
  const draft = groupCreateDraft.value
  if (!draft || draft.saving) return
  draft.saving = true
  draft.error = ''
  try {
    const groupId = await nav.createMenuGroup(draft.value)
    groupCreateDraft.value = null
    openGroupPicker(groupId)
    toast('分组已创建，选择要加入的已有菜单', 'ri-check-line')
  } catch (error) {
    draft.error = error instanceof Error ? error.message : '分组创建失败'
    draft.saving = false
  }
}

function openGroupPicker(groupId: string) {
  contextMenu.value = null
  groupPicker.value = groupId
}

function closeGroupPicker() {
  const groupId = groupPicker.value
  groupPicker.value = null
  void nextTick(() => {
    const trigger = [...(menuRef.value?.querySelectorAll<HTMLButtonElement>('.group-label') ?? [])]
      .find((button) => button.dataset.groupId === groupId)
    trigger?.focus()
  })
}

async function hideGroupFromContext() {
  const menu = contextMenu.value
  if (!menu?.groupId || menu.busy) return
  menu.busy = true
  menu.error = ''
  try {
    const result = await nav.hideMenuGroup(menu.groupId)
    toast(
      result === 'removed-empty-group'
        ? '空分组已移除'
        : '分组已隐藏，可在“全部功能”→“自定义导航”中重新固定菜单恢复',
      'ri-check-line',
    )
    contextMenu.value = null
  } catch (error) {
    menu.busy = false
    menu.error = error instanceof Error ? error.message : '分组隐藏失败'
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
  <aside class="side" :class="{ collapsed: isCollapsed }">
    <div class="side-heading">
      <WaoLogo v-if="!desktopToolbar && !isCollapsed" wordmark />
      <button
        class="side-collapse"
        :aria-label="isCollapsed ? '展开侧栏' : '折叠侧栏'"
        :title="isCollapsed ? '展开侧栏' : '折叠侧栏'"
        :aria-expanded="!isCollapsed"
        @click="nav.toggleCollapse()"
      >
        <i :class="isCollapsed ? 'ri-side-bar-line' : 'ri-sidebar-fold-line'"></i
        ><span v-if="!isCollapsed && desktopToolbar">折叠侧栏</span>
      </button>
    </div>

    <div v-if="!isCollapsed" class="search-row">
      <button class="search" aria-label="搜索功能与内容" aria-keyshortcuts="Meta+K Control+K" @click="emit('open-search')">
        <i class="ri-search-line"></i>
        <span class="search-text">搜索功能与内容</span>
        <span class="kbd">⌘K</span>
      </button>
    </div>

    <button
      v-if="!nav.menuConfig('navigation')?.visible"
      class="all-functions"
      :title="isCollapsed ? '全部功能' : undefined"
      @click="router.push('/navigation')"
    >
      <i class="ri-apps-2-line"></i><span v-if="!isCollapsed">全部功能</span>
    </button>
    <div v-if="nav.menuMutationError && !isCollapsed" class="nav-save-error" role="alert">
      {{ nav.menuMutationError
      }}<button @click="router.push('/settings?tab=navigation')">处理未保存的布局</button>
    </div>
    <nav ref="menuRef" class="menu" aria-label="模块导航">
      <template v-for="g in groups" :key="g.id">
        <button
          v-if="g.label && !isCollapsed && (g.items.length || !g.configuredMenuCount)"
          class="group-label"
          :data-group-id="g.id"
          :title="isMobile ? g.label : '点击折叠 / 展开，右键管理分组'"
          @dragover.prevent="onDragOver(g.items[0] ?? 'dock')"
          @drop.prevent="onDrop('MENU', g.id)"
          @contextmenu.prevent.stop="openGroupContextMenu($event, g.id)"
          @click="!isMobile && nav.toggleGroup(g.id)"
        >
          {{ desktopToolbar && g.id === 'PUBLIC' && g.label === '公共' ? '协作' : g.label }}
          <i :class="nav.isFolded(g.id) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>
        </button>
        <div v-else-if="g.label && isCollapsed && (g.items.length || !g.configuredMenuCount)" class="group-gap"></div>

        <!-- 手机没有分组展开按钮，必须始终展示全部可见模块，不能复用桌面的折叠状态。 -->
        <div
          v-if="(g.items.length || !g.configuredMenuCount) && (isMobile || !nav.isFolded(g.id) || isCollapsed)"
          class="items"
          @dragover.prevent
          @drop.prevent="onDrop('MENU', g.id)"
        >
          <template v-for="id in g.items" :key="id">
          <button
            class="item"
            :class="{
              active: activeItem === id && !(id === 'knowledge' && !isCollapsed && route.name === 'kb' && favoriteKbs.some((kb) => kb.id === route.params.id)) && !(id === 'home' && route.query.console) && !(id === 'projects' && !isCollapsed && favoriteProjects.some((project) => route.path === `/projects/${project.targetId}`)),
              center: isCollapsed,
              dragging: dragId === id,
              'drag-over': !!dragId && dragOverId === id,
            }"
            :title="nav.itemMeta(id)?.label"
            :aria-label="nav.itemMeta(id)?.label"
            :draggable="!isMobile && !isCollapsed && nav.canMoveMenus"
            @click="go(id)"
            @contextmenu.prevent="openContextMenu($event, id)"
            @dragstart="onDragStart(id, $event)"
            @dragover.stop.prevent="onDragOver(id)"
            @drop.stop.prevent="onDrop('MENU', g.id, id)"
            @dragend="onDragEnd"
          >
            <i :class="nav.itemMeta(id)?.icon"></i>
            <span class="item-label" :class="{ 'compact-label': isCollapsed }">{{ nav.itemMeta(id)?.label }}</span>
          </button>
          <div v-if="id === 'knowledge' && favoriteKbs.length && !isCollapsed" class="favorite-children knowledge-children">
            <div v-for="kb in favoriteKbs" :key="kb.id" class="favorite-child knowledge-child">
            <button class="item" :class="{ active: route.params.id === kb.id && route.name === 'kb' }" :title="kb.name" @click="router.push(tabs.tabs.find((tab) => tab.key === `kb:${kb.id}`)?.path || `/kb/${kb.id}`)">
              <span class="item-label">{{ kb.name }}</span>
            </button>
            <button class="favorite-unfavorite knowledge-unfavorite" title="取消收藏" :aria-label="`取消收藏：${kb.name}`" :disabled="knowledgeFavorites.saving" @click="knowledgeFavorites.toggle(kb.id)"><i class="ri-star-fill" aria-hidden="true"></i></button>
            </div>
          </div>
          <div v-if="id === 'projects' && favoriteProjects.length && !isCollapsed" class="favorite-children project-children">
            <div v-for="project in favoriteProjects" :key="project.targetId" class="favorite-child">
              <button class="item" :class="{ active: route.path === `/projects/${project.targetId}` }" :title="project.label"
                :draggable="!isMobile && !!favoriteShortcut(project)"
                @dragstart="shortcuts.start(favoriteShortcut(project), $event)" @dragend="shortcuts.end"
                @click="openFavorite(project)">
                <span class="item-label">{{ project.label }}</span>
              </button>
              <button class="favorite-unfavorite" title="取消收藏" :aria-label="`取消收藏：${project.label}`" @click="removeFavorite(project, $event)"><i class="ri-star-fill" aria-hidden="true"></i></button>
            </div>
          </div>
          </template>
        </div>

        <!-- 收藏分组紧跟首页之后；手机与桌面复用同一份真实收藏。 -->
        <template v-if="!g.label && otherFavorites.length && !isCollapsed">
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
              v-for="f in otherFavorites"
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
      v-if="!isCollapsed"
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
        :class="{ center: isCollapsed }"
        title="成员、权限与系统设置"
        @click="goSettings"
      >
        <span class="avatar" :style="{ background: session.currentUser.color }">{{
          session.currentUser.initial
        }}</span>
        <template v-if="!isCollapsed">
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
          <template v-if="contextMenu.id">
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
          </template>
          <template v-else-if="contextMenu.groupId">
            <button type="button" :disabled="contextMenu.busy || !nav.canMoveMenus" @click="createGroupFromContext">
              <i class="ri-folder-add-line"></i>新建分组
            </button>
            <button type="button" :disabled="contextMenu.busy || !nav.canMoveMenus" @click="openGroupPicker(contextMenu.groupId!)">
              <i class="ri-add-line"></i>添加已有菜单
            </button>
            <button type="button" class="danger" :disabled="contextMenu.busy || !nav.canMoveMenus || !nav.canHideMenuGroup(contextMenu.groupId!)" @click="hideGroupFromContext">
              <i class="ri-eye-off-line"></i>隐藏分组
            </button>
          </template>
          <p v-if="contextMenu.error" role="alert">{{ contextMenu.error }}，可重试。</p>
        </div>
      </div>
      <div v-if="groupCreateDraft" class="group-dialog-mask" @mousedown.self="groupCreateDraft = null" @contextmenu.prevent>
        <form class="group-dialog" role="dialog" aria-modal="true" aria-labelledby="new-nav-group-title" @submit.prevent="saveNewGroup">
          <h2 id="new-nav-group-title">新建菜单分组</h2>
          <label for="new-nav-group-name">分组名称</label>
          <input id="new-nav-group-name" v-model="groupCreateDraft.value" maxlength="24" autofocus :disabled="groupCreateDraft.saving" placeholder="例如：创作" />
          <p v-if="groupCreateDraft.error" role="alert">{{ groupCreateDraft.error }}</p>
          <div><button type="button" :disabled="groupCreateDraft.saving" @click="groupCreateDraft = null">取消</button><button type="submit" class="primary" :disabled="groupCreateDraft.saving || !groupCreateDraft.value.trim()">{{ groupCreateDraft.saving ? '正在保存…' : '创建分组' }}</button></div>
        </form>
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
    <MenuGroupPicker v-if="groupPicker" :group-id="groupPicker" @close="closeGroupPicker" />
  </aside>
</template>

<style scoped>
.favorite-children { margin-left: 22px; border-left: 1px solid var(--wao-line); padding-left: 4px; }
.favorite-child { display: flex; align-items: center; gap: 2px; }
.favorite-child > .item { flex: 1; min-width: 0; width: auto; }
.favorite-unfavorite { flex: 0 0 26px; width: 26px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--wao-warning-strong); cursor: pointer; }
.favorite-unfavorite:hover { background: var(--wao-warning-bg); }
.favorite-unfavorite:disabled { opacity: .5; cursor: wait; }
@media (hover: hover) and (pointer: fine) {
  .favorite-unfavorite { opacity: 0; pointer-events: none; }
  .favorite-child:hover .favorite-unfavorite, .favorite-child:focus-within .favorite-unfavorite { opacity: 1; pointer-events: auto; }
}
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
  color: var(--wao-primary-text);
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
  color: var(--wao-primary-text);
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
  color: var(--wao-primary-text);
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.menu-action-mask,
.rename-mask,
.group-dialog-mask {
  position: fixed;
  z-index: 1300;
  inset: 0;
}
.group-dialog-mask { z-index: 1310; display: grid; place-items: center; padding: 18px; background: rgba(14, 18, 32, .38); }
.group-dialog { width: min(420px, 100%); max-height: min(78dvh, 680px); box-sizing: border-box; padding: 18px; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 14px; background: var(--wao-bg-card); color: var(--wao-text); box-shadow: var(--wao-shadow-pop-2); }
.group-dialog h2 { margin: 0 0 14px; color: var(--wao-text); font-size: 16px; }
.group-dialog > label { display: block; margin-bottom: 7px; color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.group-dialog > input { width: 100%; height: 38px; box-sizing: border-box; padding: 0 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-page); color: var(--wao-text); outline: none; }
.group-dialog > input:focus { border-color: var(--wao-primary); box-shadow: 0 0 0 2px var(--wao-primary-soft); }
.group-dialog > p { margin: 9px 0 0; color: var(--wao-text-weak); font-size: 12px; line-height: 1.5; }
.group-dialog > p[role='alert'] { color: var(--wao-danger-strong); }
.group-dialog > div { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.group-dialog > div > button { min-height: 34px; padding: 0 12px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.group-dialog > div > button.primary { border-color: var(--wao-primary); background: var(--wao-primary); color: #fff; }
.group-dialog button:disabled { opacity: .58; cursor: wait; }
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
  .side { width: 100%; height: 100%; min-height: 0; border: 0; background: var(--wao-bg-card); box-shadow: none; }
  .side-heading { display: none; }
  .search { height: 40px; font-size: 13px; }
  .search-row { padding: 8px 12px; }
  .kbd { display: none; }
  .menu { min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
  .item { min-height: 40px; }
  .item-label { font-size: 13px; }
  .group-label { font-size: 12px; }
  .user { min-height: 44px; }
}
</style>
