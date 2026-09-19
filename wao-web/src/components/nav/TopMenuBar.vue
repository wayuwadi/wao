<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNavStore } from '@/stores/nav'
import { useMenuDrag } from '@/composables/menuDrag'
import { useIsMobile } from '@/composables/viewport'
import { externalAppItemId, type NavItemId } from '@/config/nav'
import { useTopShortcutsStore, type TopShortcut } from '@/stores/topShortcuts'
import { useQuickLinks } from '@/composables/quickLinks'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import type { ModuleId } from '@/types'

const shortcuts = useTopShortcutsStore()
const quickLinks = useQuickLinks()
const session = useSessionStore()
const data = useDataStore()
const shortcutOver = ref<string | null>(null)
watch(
  () => session.userId,
  () => {
    void shortcuts.load()
    void quickLinks.load()
  },
  { immediate: true },
)
const nav = useNavStore()
const router = useRouter()
const route = useRoute()
const isMobile = useIsMobile()
const list = ref<HTMLElement | null>(null)
const { dragId, dragOverId, onDragStart, onDragOver, onDragEnd, onDropTop } = useMenuDrag()
const activeItem = computed(() =>
  route.meta.tabKind === 'external'
    ? externalAppItemId(String(route.params.appId))
    : (route.meta.module as ModuleId | undefined),
)
const loadError = computed(() => nav.builtinMenusError || nav.externalAppsError)
const loading = computed(
  () => nav.builtinMenusState === 'loading' || nav.externalAppsState === 'loading',
)

const hasItems = computed(() => nav.topItems.length + shortcuts.items.length)
const dragging = computed(() => !!dragId.value || !!shortcuts.dragged)
watch(dragId, (id) => {
  if (id) shortcuts.end()
})
function shortcutLabel(item: TopShortcut) {
  if (item.quickLinkId)
    return quickLinks.links.value.find((link) => link.id === item.quickLinkId)?.label ?? item.label
  if (item.key.startsWith('project:'))
    return data.projectById(item.key.slice(8))?.name ?? item.label
  if (item.key.startsWith('kb:'))
    return data.kbs.find((kb) => kb.id === item.key.slice(3))?.name ?? item.label
  return item.label
}
function visitShortcut(item: TopShortcut) {
  if (!item.quickLinkId) {
    void router.push(item.path)
    return
  }
  const link = quickLinks.links.value.find((link) => link.id === item.quickLinkId)
  if (!link || quickLinks.loadError.value) {
    toast('快捷链接已删除或尚未加载，请在快捷链接中检查', 'ri-error-warning-line')
    return
  }
  if (link.openMode === 'NEW_PAGE') window.open(link.url, '_blank', 'noopener,noreferrer')
  else void router.push(item.path)
}
async function dropShortcut(beforeKey?: string) {
  const item = shortcuts.dragged
  shortcuts.end()
  shortcutOver.value = null
  if (!item) return
  try {
    await shortcuts.pin(item, beforeKey)
    toast('顶部快捷入口已保存', 'ri-check-line')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '保存失败，请重试', 'ri-error-warning-line')
  }
}
function dragOverTop() {
  if (shortcuts.dragged) shortcutOver.value = 'top'
  else onDragOver('top')
}
function clearTopDragOver() {
  dragOverId.value = null
  shortcutOver.value = null
}
function finishShortcutDrag() {
  shortcuts.end()
  shortcutOver.value = null
}
function reloadTopMenus() {
  void nav.loadBuiltinMenus(true)
  void nav.loadExternalApps(true)
}
function dropTop(targetId?: NavItemId) {
  if (shortcuts.dragged) return dropShortcut()
  return onDropTop(targetId)
}
async function removeShortcut(key: string) {
  try {
    await shortcuts.remove(key)
  } catch {
    /* 顶栏保留失败原因和重试入口。 */
  }
}
async function retryShortcuts() {
  try {
    await shortcuts.retry()
  } catch {
    /* 保留草稿继续重试。 */
  }
}

function go(id: NavItemId) {
  const meta = nav.itemMeta(id)
  if (!meta) return
  if (meta.openMode === 'NEW_PAGE') {
    const target = meta.newPagePath ?? meta.path
    window.open(
      /^https:\/\//i.test(target) ? target : router.resolve(target).href,
      '_blank',
      'noopener,noreferrer',
    )
  } else {
    void router.push(meta.path)
  }
}

function scroll(direction: number) {
  list.value?.scrollBy({ left: direction * 160, behavior: 'smooth' })
}

function revealDragEdge(event: DragEvent) {
  if (!dragging.value || !list.value) return
  const bounds = list.value.getBoundingClientRect()
  if (event.clientX < bounds.left + 32) list.value.scrollLeft -= 16
  else if (event.clientX > bounds.right - 32) list.value.scrollLeft += 16
}
</script>

<template>
  <nav
    class="top-menu-bar"
    :class="{
      'has-items': hasItems,
      dragging,
      'drag-over': (!!dragId && dragOverId === 'top') || shortcutOver === 'top',
    }"
    aria-label="顶部按钮区"
    :aria-busy="
      loading ||
      nav.menuMutationState === 'saving' ||
      shortcuts.state === 'saving' ||
      shortcuts.state === 'loading'
    "
    @dragover.prevent="dragOverTop"
    @dragleave.self="clearTopDragOver"
    @drop.prevent="dropTop()"
  >
    <button v-if="hasItems" class="scroll-button" aria-label="向左浏览顶部菜单" @click="scroll(-1)">
      <i class="ri-arrow-left-s-line"></i>
    </button>
    <div ref="list" class="top-menu-list" @dragover.capture="revealDragEdge">
      <button
        v-for="(id, index) in nav.topItems"
        :key="id"
        type="button"
        class="top-menu-item"
        :data-desktop-menu-index="index"
        :class="{
          active: activeItem === id,
          dragging: dragId === id,
          'drag-over': !!dragId && dragOverId === id,
        }"
        :title="nav.itemMeta(id)?.label"
        :aria-label="nav.itemMeta(id)?.label"
        :aria-current="activeItem === id ? 'page' : undefined"
        :draggable="!isMobile && nav.canMoveMenus"
        @click="go(id)"
        @dragstart="onDragStart(id, $event)"
        @dragend="onDragEnd"
        @dragover.stop.prevent="onDragOver(id)"
        @drop.stop.prevent="dropTop(id)"
      >
        <i :class="nav.itemMeta(id)?.icon"></i><span>{{ nav.itemMeta(id)?.label }}</span>
      </button>
      <span
        v-for="(item, index) in shortcuts.items"
        :key="item.key"
        class="shortcut-wrap"
        :class="{ 'drag-over': shortcutOver === item.key }"
        @dragover.stop.prevent="shortcuts.dragged ? (shortcutOver = item.key) : onDragOver('top')"
        @drop.stop.prevent="shortcuts.dragged ? dropShortcut(item.key) : onDropTop()"
      >
        <button
          type="button"
          class="top-menu-item personal-shortcut"
          :title="shortcutLabel(item)"
          :aria-label="shortcutLabel(item)"
          :data-desktop-menu-index="nav.topItems.length + index"
          :class="{ active: route.fullPath === item.path }"
          :aria-current="route.fullPath === item.path ? 'page' : undefined"
          :draggable="!isMobile && shortcuts.canEdit"
          @dragstart="shortcuts.start(item, $event)"
          @dragend="finishShortcutDrag"
          @click="visitShortcut(item)"
        >
          <i :class="item.icon"></i><span>{{ shortcutLabel(item) }}</span>
        </button>
        <button
          type="button"
          class="remove-shortcut"
          :aria-label="`移除顶部快捷入口：${shortcutLabel(item)}`"
          title="从顶部移除"
          :disabled="!shortcuts.canEdit"
          @click="removeShortcut(item.key)"
        >
          <i class="ri-close-line"></i>
        </button>
      </span>
      <span v-if="!hasItems && !loading && !loadError" class="empty-hint">{{
        dragging ? '松开后显示为按钮' : '拖动菜单、项目、知识库或快捷链接到这里'
      }}</span>
    </div>
    <button v-if="hasItems" class="scroll-button" aria-label="向右浏览顶部菜单" @click="scroll(1)">
      <i class="ri-arrow-right-s-line"></i>
    </button>
    <span v-if="loading || nav.menuMutationState === 'saving'" class="save-state" role="status">{{
      loading ? '加载中…' : '保存中…'
    }}</span>
    <button
      v-else-if="loadError"
      class="retry"
      :title="loadError"
      @click="reloadTopMenus"
    >
      加载失败，重试
    </button>
    <span
      v-else-if="nav.menuMutationState === 'error'"
      class="save-state error"
      role="alert"
      :title="nav.menuMutationError"
      >保存失败，请重新拖拽</span
    >
    <span v-else-if="nav.menuMutationState === 'saved'" class="sr-only" role="status"
      >菜单位置已保存</span
    >
    <span
      v-if="shortcuts.state === 'loading' || shortcuts.state === 'saving'"
      class="save-state"
      role="status"
      >{{ shortcuts.state === 'saving' ? '快捷入口保存中…' : '快捷入口加载中…' }}</span
    >
    <button
      v-else-if="shortcuts.state === 'error'"
      class="retry"
      :title="shortcuts.error"
      @click="retryShortcuts"
    >
      快捷入口失败，重试
    </button>
    <span v-else-if="shortcuts.state === 'saved'" class="sr-only" role="status"
      >顶部快捷入口已保存</span
    >
  </nav>
</template>

<style scoped>
.top-menu-bar {
  flex: 1 1 0;
  min-width: 100px;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 3px;
  border: 1px solid transparent;
  border-radius: 8px;
  overflow: hidden;
}
.top-menu-bar.dragging {
  border: 1px dashed var(--wao-primary);
  background: var(--wao-primary-soft);
}
.top-menu-bar.drag-over {
  box-shadow: inset 0 0 0 1px var(--wao-primary);
}
.top-menu-list {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  scrollbar-width: thin;
  padding: 2px;
}
.top-menu-item {
  display: flex;
  flex: none;
  align-items: center;
  gap: 5px;
  max-width: 180px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  color: var(--wao-text-2);
  background: var(--wao-bg-card);
  cursor: pointer;
  font-size: 12px;
}
.shortcut-wrap {
  display: flex;
  align-items: center;
  flex: none;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
}
.shortcut-wrap .top-menu-item {
  border: 0;
}
.shortcut-wrap.drag-over {
  outline: 2px dashed var(--wao-primary);
}
.remove-shortcut {
  border: 0;
  background: transparent;
  color: var(--wao-text-faint);
  cursor: pointer;
  padding: 4px;
}
.remove-shortcut:hover {
  color: var(--wao-danger-strong);
}
.top-menu-item span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.top-menu-item i {
  flex: none;
  font-size: 14px;
}
.top-menu-item:hover,
.top-menu-item.active {
  color: var(--wao-primary);
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary);
}
.top-menu-item.dragging {
  opacity: 0.45;
}
.top-menu-item.drag-over {
  outline: 2px dashed var(--wao-primary);
  outline-offset: -2px;
}
.top-menu-item[draggable='true'] {
  cursor: grab;
}
.scroll-button {
  flex: none;
  width: 18px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.scroll-button:hover {
  background: var(--wao-bg-hover-2);
}
.empty-hint {
  font-size: 11px;
  color: var(--wao-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.save-state,
.retry {
  flex: none;
  font-size: 11px;
  color: var(--wao-text-weak);
}
.error,
.retry {
  color: var(--wao-danger-strong);
}
.retry {
  cursor: pointer;
  background: none;
  border: 0;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
@media (max-width: 760px) {
  .top-menu-bar {
    min-width: 1px;
    height: 30px;
  }
  .top-menu-bar.has-items {
    flex: 0 0 140px;
  }
  .empty-hint {
    display: none;
  }
  .top-menu-item {
    height: 26px;
    max-width: 104px;
    padding: 0 5px;
    font-size: 10px;
  }
}
</style>
