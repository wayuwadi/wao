<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'
import WaoLogo from '@/components/WaoLogo.vue'
import { useIsMobile } from '@/composables/viewport'
import { useNavStore } from '@/stores/nav'
import { useSessionStore } from '@/stores/session'
import { hasDesktopToolbar } from '@/utils/desktopToolbar'
import { FIXED_NAV_IDS, type NavItemId } from '@/config/nav'
const emit = defineEmits<{ (e: 'open-search'): void; (e: 'navigate'): void }>()
const nav = useNavStore()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const isMobile = useIsMobile()
const desktopToolbar = hasDesktopToolbar()
const isCollapsed = computed(() => !isMobile.value && nav.collapsed)
const navigationWidth = ref(236)
const menuRef = ref<HTMLElement | null>(null)
const activeItem = computed(() => route.meta.module)
watch([isMobile, activeItem], () => {
  if (isMobile.value) void nextTick(() => menuRef.value?.querySelector<HTMLElement>('.item.active')
    ?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' }))
}, { immediate: true })
function go(id: NavItemId) {
  const meta = nav.itemMeta(id)
  if (!meta?.path) return
  emit('navigate')
  void router.push(meta.path)
}
function goSettings() { emit('navigate'); void router.push('/settings') }
</script>

<template>
  <aside class="side" :class="{ collapsed: isCollapsed }" :style="{ '--navigation-width': `${navigationWidth}px` }">
    <PaneResizeHandle v-if="!isCollapsed && !isMobile" :storage-key="`wao:navigation-width:${session.currentUser.id}`" label="全局导航宽度" :default-width="236" :max="420" @resize="navigationWidth = $event" />
    <div class="side-heading">
      <RouterLink v-if="!desktopToolbar && !isCollapsed" class="side-brand" to="/" aria-label="WAO 首页" @click="emit('navigate')">
        <WaoLogo wordmark />
      </RouterLink>
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

    <nav ref="menuRef" class="menu" aria-label="主菜单">
      <div class="items">
        <button v-for="id in FIXED_NAV_IDS" :key="id" class="item"
          :class="{ active: activeItem === id, center: isCollapsed }"
          :aria-current="activeItem === id ? 'page' : undefined"
          :title="nav.itemMeta(id)?.label" @click="go(id)">
          <i :class="nav.itemMeta(id)?.icon" aria-hidden="true"></i>
          <span :class="isCollapsed ? 'compact-label' : 'item-label'">{{ nav.itemMeta(id)?.label }}</span>
        </button>
      </div>
    </nav>
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

  </aside>
</template>

<style scoped>
.favorite-drop-target { outline: 1px solid var(--wao-primary); background: var(--wao-primary-soft); }
.favorite-order-status { color: var(--wao-text-3); font-size: var(--wao-font-caption); }
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
.side-brand { display: inline-flex; color: var(--wao-primary-text); text-decoration: none; border-radius: 6px; }
.side-brand :deep(.wao-logo-name) { font-weight: 600; }
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
  position: relative;
  width: min(var(--navigation-width, var(--wao-side-w)), 30vw);
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
  .side-heading { display: flex; padding: 8px 12px 0; }
  .side-collapse { display: none; }
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
