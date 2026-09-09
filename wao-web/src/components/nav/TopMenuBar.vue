<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNavStore } from '@/stores/nav'
import { useMenuDrag } from '@/composables/menuDrag'
import { useIsMobile } from '@/composables/viewport'
import { externalAppItemId, type NavItemId } from '@/config/nav'
import type { ModuleId } from '@/types'

const nav = useNavStore()
const router = useRouter()
const route = useRoute()
const isMobile = useIsMobile()
const list = ref<HTMLElement | null>(null)
const { dragId, dragOverId, onDragStart, onDragOver, onDragEnd, onDropTop } = useMenuDrag()
const activeItem = computed(() => route.meta.tabKind === 'external'
  ? externalAppItemId(String(route.params.appId)) : route.meta.module as ModuleId | undefined)
const loadError = computed(() => nav.builtinMenusError || nav.externalAppsError)
const loading = computed(() => nav.builtinMenusState === 'loading' || nav.externalAppsState === 'loading')

function go(id: NavItemId) {
  const meta = nav.itemMeta(id)
  if (!meta) return
  if (meta.openMode === 'NEW_PAGE') {
    const target = meta.newPagePath ?? meta.path
    window.open(/^https:\/\//i.test(target) ? target : router.resolve(target).href, '_blank', 'noopener,noreferrer')
  } else {
    void router.push(meta.path)
  }
}

function scroll(direction: number) {
  list.value?.scrollBy({ left: direction * 160, behavior: 'smooth' })
}

function revealDragEdge(event: DragEvent) {
  if (!dragId.value || !list.value) return
  const bounds = list.value.getBoundingClientRect()
  if (event.clientX < bounds.left + 32) list.value.scrollLeft -= 16
  else if (event.clientX > bounds.right - 32) list.value.scrollLeft += 16
}
</script>

<template>
  <nav
    class="top-menu-bar"
    :class="{ 'has-items': nav.topItems.length, dragging: !!dragId, 'drag-over': !!dragId && dragOverId === 'top' }"
    aria-label="顶部按钮区"
    :aria-busy="loading || nav.menuMutationState === 'saving'"
    @dragover.prevent="onDragOver('top')"
    @dragleave.self="dragOverId = null"
    @drop.prevent="onDropTop()"
  >
    <button v-if="nav.topItems.length" class="scroll-button" aria-label="向左浏览顶部菜单" @click="scroll(-1)"><i class="ri-arrow-left-s-line"></i></button>
    <div ref="list" class="top-menu-list" @dragover.capture="revealDragEdge">
      <button
        v-for="id in nav.topItems" :key="id" type="button" class="top-menu-item"
        :class="{ active: activeItem === id, dragging: dragId === id, 'drag-over': !!dragId && dragOverId === id }"
        :title="nav.itemMeta(id)?.label" :aria-current="activeItem === id ? 'page' : undefined"
        :draggable="!isMobile && nav.canMoveMenus"
        @click="go(id)" @dragstart="onDragStart(id, $event)" @dragend="onDragEnd"
        @dragover.stop.prevent="onDragOver(id)" @drop.stop.prevent="onDropTop(id)"
      >
        <i :class="nav.itemMeta(id)?.icon"></i><span>{{ nav.itemMeta(id)?.label }}</span>
      </button>
      <span v-if="!nav.topItems.length && !loading && !loadError" class="empty-hint">{{ dragId ? '松开后显示为按钮' : '拖动菜单到这里，显示为按钮' }}</span>
    </div>
    <button v-if="nav.topItems.length" class="scroll-button" aria-label="向右浏览顶部菜单" @click="scroll(1)"><i class="ri-arrow-right-s-line"></i></button>
    <span v-if="loading || nav.menuMutationState === 'saving'" class="save-state" role="status">{{ loading ? '加载中…' : '保存中…' }}</span>
    <button v-else-if="loadError" class="retry" :title="loadError" @click="nav.loadBuiltinMenus(true); nav.loadExternalApps(true)">加载失败，重试</button>
    <span v-else-if="nav.menuMutationState === 'error'" class="save-state error" role="alert" :title="nav.menuMutationError">保存失败，请重新拖拽</span>
    <span v-else-if="nav.menuMutationState === 'saved'" class="sr-only" role="status">菜单位置已保存</span>
  </nav>
</template>

<style scoped>
.top-menu-bar { flex: 1 1 0; min-width: 100px; height: 38px; display: flex; align-items: center; gap: 3px; border: 1px solid transparent; border-radius: 8px; overflow: hidden; }
.top-menu-bar.dragging { border: 1px dashed var(--wao-primary); background: var(--wao-primary-soft); }
.top-menu-bar.drag-over { box-shadow: inset 0 0 0 1px var(--wao-primary); }
.top-menu-list { flex: 1; min-width: 0; display: flex; align-items: center; gap: 6px; overflow-x: auto; scrollbar-width: thin; padding: 2px; }
.top-menu-item { display: flex; flex: none; align-items: center; gap: 5px; max-width: 180px; height: 30px; padding: 0 10px; border: 1px solid var(--wao-line-control); border-radius: 7px; color: var(--wao-text-2); background: var(--wao-bg-card); cursor: pointer; font-size: 12px; }
.top-menu-item span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.top-menu-item i { flex: none; font-size: 14px; }
.top-menu-item:hover, .top-menu-item.active { color: var(--wao-primary); background: var(--wao-primary-soft); border-color: var(--wao-primary); }
.top-menu-item.dragging { opacity: .45; }
.top-menu-item.drag-over { outline: 2px dashed var(--wao-primary); outline-offset: -2px; }
.top-menu-item[draggable='true'] { cursor: grab; }
.scroll-button { flex: none; width: 18px; height: 28px; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--wao-text-weak); cursor: pointer; }
.scroll-button:hover { background: var(--wao-bg-hover-2); }
.empty-hint { font-size: 11px; color: var(--wao-text-faint); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.save-state, .retry { flex: none; font-size: 11px; color: var(--wao-text-weak); }
.error, .retry { color: var(--wao-danger-strong); }
.retry { cursor: pointer; background: none; border: 0; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
@media (max-width: 760px) {
  .top-menu-bar { min-width: 1px; height: 30px; }
  .top-menu-bar.has-items { flex: 0 0 140px; }
  .empty-hint { display: none; }
  .top-menu-item { height: 26px; max-width: 104px; padding: 0 5px; font-size: 10px; }
}
</style>
