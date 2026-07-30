<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toast } from '@/composables/toast'
import { useNavStore } from '@/stores/nav'
import { useSessionStore } from '@/stores/session'
import { useFavoritesStore } from '@/stores/favorites'
import type { Favorite } from '@/api/favorites'
import type { ModuleId } from '@/types'
import { PERSONAL_ITEMS, externalAppItemId, type NavItemId } from '@/config/nav'

const emit = defineEmits<{ (e: 'open-search'): void }>()

const nav = useNavStore()
const session = useSessionStore()
const favorites = useFavoritesStore()
const route = useRoute()
const router = useRouter()

const activeItem = computed<NavItemId | undefined>(() =>
  route.meta.tabKind === 'external'
    ? externalAppItemId(String(route.params.appId))
    : route.meta.module as ModuleId | undefined,
)

const groups = computed(() => nav.groups)

onMounted(() => {
  nav.loadPersonalOrder()
  nav.loadExternalApps()
  nav.loadBuiltinMenus()
  favorites.load()
})

function go(id: NavItemId) {
  const meta = nav.itemMeta(id)
  if (meta) router.push(meta.path)
}
function goSettings() {
  router.push('/settings')
}
function orgClick() {
  toast('组织切换将在设置 · 组织管理中提供')
}

// ---- 个人分组拖拽排序 ----
const dragId = ref<ModuleId | null>(null)
const dragOverId = ref<ModuleId | null>(null)

function onDragStart(id: ModuleId, ev: DragEvent) {
  dragId.value = id
  ev.dataTransfer?.setData('text/plain', id)
  if (ev.dataTransfer) ev.dataTransfer.effectAllowed = 'move'
}
function onDragOver(id: ModuleId) {
  if (dragId.value && dragId.value !== id) dragOverId.value = id
}
function onDrop(targetId: ModuleId) {
  const from = dragId.value
  dragId.value = null
  dragOverId.value = null
  if (!from || from === targetId) return
  const order = [...nav.personalOrder]
  const fi = order.indexOf(from)
  const ti = order.indexOf(targetId)
  if (fi < 0 || ti < 0) return
  order.splice(fi, 1)
  order.splice(ti, 0, from)
  nav.setPersonalOrder(order)
}
function onDragEnd() {
  dragId.value = null
  dragOverId.value = null
}

function isPersonalModule(id: NavItemId): id is ModuleId {
  return !id.startsWith('external:') && PERSONAL_ITEMS.includes(id as ModuleId)
}

// ---- 收藏分组 ----
const FAV_ICONS: Record<Favorite['type'], string> = {
  project: 'ri-stack-line',
  task: 'ri-checkbox-circle-line',
  doc: 'ri-file-text-line',
}
function openFavorite(f: Favorite) {
  if (f.type === 'project') router.push(`/projects/${f.targetId}`)
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

    <nav class="menu">
      <template v-for="g in groups" :key="g.label || 'root'">
        <button
          v-if="g.label && !nav.collapsed"
          class="group-label"
          title="折叠 / 展开分组"
          @click="nav.toggleGroup(g.label)"
        >
          {{ g.label }}
          <i :class="nav.isFolded(g.label) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i>
        </button>
        <div v-else-if="g.label && nav.collapsed" class="group-gap"></div>

        <div v-if="!nav.isFolded(g.label) || nav.collapsed" class="items">
          <button
            v-for="id in g.items"
            :key="id"
            class="item"
            :class="{
              active: activeItem === id,
              center: nav.collapsed,
              dragging: dragId === id,
              'drag-over': dragOverId === id,
            }"
            :title="nav.itemMeta(id)?.label"
            :draggable="g.label === '个人' && isPersonalModule(id) && !nav.collapsed"
            @click="go(id)"
            @dragstart="g.label === '个人' && isPersonalModule(id) && onDragStart(id, $event)"
            @dragover.prevent="g.label === '个人' && isPersonalModule(id) && onDragOver(id)"
            @drop.prevent="g.label === '个人' && isPersonalModule(id) && onDrop(id)"
            @dragend="onDragEnd"
          >
            <i :class="nav.itemMeta(id)?.icon"></i>
            <span v-if="!nav.collapsed" class="item-label">{{ nav.itemMeta(id)?.label }}</span>
          </button>
        </div>

        <!-- 收藏分组紧跟首页之后：有收藏才出现，手机底栏不展示 -->
        <template v-if="!g.label && favorites.items.length && !nav.collapsed">
          <button class="group-label fav-group" title="折叠 / 展开分组" @click="nav.toggleGroup('收藏')">
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
              <span class="fav-remove" title="取消收藏" @click="removeFavorite(f, $event)"><i class="ri-star-fill"></i></span>
            </button>
          </div>
        </template>
      </template>
    </nav>

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
  background: #e9eaef;
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
  background: #fff;
  border-radius: 8px;
  padding: 0 10px;
  cursor: pointer;
  color: var(--wao-text-weak);
  font-size: 12.5px;
}
.search:hover {
  border-color: #c9cdda;
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
  color: #8a8d99;
  flex-shrink: 0;
}
.item-label {
  flex: 1;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fav-remove { display: inline-flex; padding: 4px; color: #d6a11d; border-radius: 5px; }
.fav-remove:hover { background: #fff2cc; }
.fav-remove i { color: inherit; font-size: 14px; }
.item:hover {
  background: #e9eaef;
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
  background: #e9eaef;
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
    background: rgba(255, 255, 255, 0.97);
    box-shadow: 0 -4px 18px rgba(16, 18, 28, 0.08);
    overflow: visible;
  }
  .brand-row,
  .search-row,
  .group-label,
  .group-gap,
  .fav-item,
  .user-row { display: none; }
  .menu {
    display: flex;
    align-items: stretch;
    gap: 1px;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 4px 6px;
    scrollbar-width: none;
  }
  .menu::-webkit-scrollbar { display: none; }
  .items { display: flex; flex-direction: row; padding: 0; gap: 1px; }
  .item,
  .item.center {
    width: 58px;
    min-width: 58px;
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
  .item i { font-size: 18px; }
}
</style>
