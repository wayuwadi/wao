<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useNavStore } from '@/stores/nav'
import { matchesFunction } from '@/config/navigationCatalog'
import type { NavigationLayoutItem } from '@/config/navigationLayout'
import type { NavItemId } from '@/config/nav'
import { toast } from '@/composables/toast'

const props = defineProps<{ groupId: string }>()
const emit = defineEmits<{ close: [] }>()
const nav = useNavStore()
const dialog = ref<HTMLElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const query = ref('')
const filter = ref<'all' | 'hidden' | 'added'>('all')
const selected = ref<NavItemId[]>([])
const saving = ref(false)
const refreshing = ref(false)
const error = ref('')
let disposed = false
let previousOverflow = ''

type MenuOption = NavigationLayoutItem & { added: boolean; location: string }
// Store 保存时会先乐观更新布局；冻结本轮列表，避免选项在请求完成前跳动或消失。
const submittingMenus = ref<MenuOption[] | null>(null)
const groupLabel = computed(() => nav.layoutGroups.find((group) => group.id === props.groupId)?.label ?? '此分组')
const menus = computed<MenuOption[]>(() => submittingMenus.value ?? nav.currentLayout().items
  .filter((item) => item.id !== 'chat')
  .map((item) => {
    const added = item.visible && item.placement === 'MENU' && item.group === props.groupId
    const source = nav.layoutGroups.find((group) => group.id === item.group)?.label
    return {
      ...item,
      label: nav.itemMeta(item.id)?.label ?? item.label,
      added,
      location: !item.visible ? '未显示在导航中'
        : item.placement === 'TOP' ? '顶部菜单'
          : item.placement === 'DOCK' ? '底部图标区'
            : source ? `左侧菜单 · ${source}` : '左侧菜单',
    }
  })
  .sort((a, b) => Number(a.added) - Number(b.added) || a.label.localeCompare(b.label, 'zh-CN')))
const filters = computed(() => [
  { id: 'all' as const, label: '全部菜单', count: menus.value.length },
  { id: 'hidden' as const, label: '未显示', count: menus.value.filter((item) => !item.visible).length },
  { id: 'added' as const, label: '已在本组', count: menus.value.filter((item) => item.added).length },
])
const visibleMenus = computed(() => menus.value.filter((item) =>
  matchesFunction(item.id, item.label, query.value)
  && (filter.value === 'all' || (filter.value === 'hidden' ? !item.visible : item.added))))
const selectableMenus = computed(() => visibleMenus.value.filter((item) => !item.added))
const selectedIds = computed(() => selected.value.filter((id) => menus.value.some((item) => item.id === id && !item.added)))
const allVisibleSelected = computed(() => selectableMenus.value.length > 0
  && selectableMenus.value.every((item) => selected.value.includes(item.id)))
const busy = computed(() => saving.value || refreshing.value)

function toggle(id: NavItemId) {
  if (busy.value || menus.value.find((item) => item.id === id)?.added) return
  selected.value = selected.value.includes(id) ? selected.value.filter((item) => item !== id) : [...selected.value, id]
}
function toggleVisible() {
  if (busy.value) return
  const ids = selectableMenus.value.map((item) => item.id)
  selected.value = allVisibleSelected.value
    ? selected.value.filter((id) => !ids.includes(id))
    : [...new Set([...selected.value, ...ids])]
}
function close() {
  if (!busy.value) emit('close')
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close()
  } else if (event.key === 'Tab') {
    const controls = [...(dialog.value?.querySelectorAll<HTMLElement>('button, input, a[href]') ?? [])]
      .filter((element) => !element.hasAttribute('disabled'))
    const first = controls[0], last = controls[controls.length - 1]
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) {
      event.preventDefault()
      last?.focus()
    } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.value)) {
      event.preventDefault()
      first?.focus()
    }
  }
}

async function addSelected() {
  if (busy.value || !selectedIds.value.length || !nav.canMoveMenus) return
  const ids = [...selectedIds.value]
  const next = nav.currentLayout()
  if (!next.groups.some((group) => group.id === props.groupId)) {
    error.value = '这个分组已不存在，请关闭后重新选择分组。'
    return
  }
  const peers = next.items.filter((item) => !ids.includes(item.id) && item.visible
    && item.placement === 'MENU' && item.group === props.groupId).sort((a, b) => a.order - b.order)
  const additions = ids.map((id) => next.items.find((item) => item.id === id)!)
  ;[...peers, ...additions].forEach((item, index) => {
    item.group = props.groupId
    item.placement = 'MENU'
    item.visible = true
    item.order = (index + 1) * 10
  })
  submittingMenus.value = menus.value.map((item) => ({ ...item }))
  saving.value = true
  error.value = ''
  await nextTick(() => dialog.value?.focus())
  // 所有选择沿用现有布局接口一次保存，失败时整批回滚，不产生部分成功。
  try {
    await nav.saveLayout(next)
    if (disposed) return
    toast(`已将 ${ids.length} 个菜单添加到「${groupLabel.value}」`, 'ri-check-line')
    emit('close')
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '菜单添加失败'
  } finally {
    saving.value = false
    submittingMenus.value = null
  }
}

async function refreshLayout() {
  if (busy.value) return
  refreshing.value = true
  await nextTick(() => dialog.value?.focus())
  try {
    await nav.discardLayoutDraft()
    if (nav.layoutError) throw new Error(nav.layoutError)
    error.value = ''
    selected.value = [...selectedIds.value]
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '导航读取失败，请重试'
  } finally {
    refreshing.value = false
  }
}

onMounted(() => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  void nextTick(() => searchInput.value?.focus())
})
onBeforeUnmount(() => {
  disposed = true
  document.body.style.overflow = previousOverflow
})
</script>

<template>
  <Teleport to="body">
    <div class="group-picker-mask" @mousedown.self="close" @contextmenu.prevent @keydown="onKeydown">
      <section ref="dialog" class="group-picker" role="dialog" aria-modal="true" aria-labelledby="group-picker-title" aria-describedby="group-picker-description" :aria-busy="busy" tabindex="-1">
        <header class="picker-heading">
          <span class="picker-heading-icon"><i class="ri-menu-add-line" aria-hidden="true"></i></span>
          <div class="picker-heading-copy">
            <h2 id="group-picker-title">添加菜单</h2>
            <p id="group-picker-description">添加到 <strong>「{{ groupLabel }}」</strong>，让常用功能更容易找到。</p>
          </div>
          <button class="picker-icon-button" type="button" title="关闭" aria-label="关闭添加菜单" :disabled="busy" @click="close"><i class="ri-close-line" aria-hidden="true"></i></button>
        </header>

        <div class="picker-tools">
          <div class="picker-search">
            <i class="ri-search-line" aria-hidden="true"></i>
            <input ref="searchInput" v-model="query" type="search" aria-label="搜索菜单" placeholder="搜索菜单名称、用途或关键词" :disabled="busy" />
            <button v-if="query" class="picker-icon-button" type="button" aria-label="清除搜索" title="清除搜索" :disabled="busy" @click="query = ''; searchInput?.focus()"><i class="ri-close-circle-fill" aria-hidden="true"></i></button>
          </div>
          <div class="picker-filters" role="group" aria-label="筛选菜单">
            <button v-for="item in filters" :key="item.id" type="button" :aria-pressed="filter === item.id" :disabled="busy" @click="filter = item.id">{{ item.label }}<span>{{ item.count }}</span></button>
          </div>
        </div>

        <div class="picker-list-heading">
          <span>{{ query.trim() ? `找到 ${visibleMenus.length} 个菜单` : '选择要添加的菜单' }}</span>
          <button v-if="selectableMenus.length" type="button" :disabled="busy" @click="toggleVisible">{{ allVisibleSelected ? '取消当前选择' : '选择当前结果' }}</button>
        </div>
        <div class="group-picker-list">
          <label v-for="item in visibleMenus" :key="item.id" class="picker-option" :class="{ selected: selected.includes(item.id) && !item.added, added: item.added, busy }">
            <input type="checkbox" :checked="selected.includes(item.id) && !item.added" :disabled="busy || item.added" :aria-label="item.label" @change="toggle(item.id)" />
            <span class="picker-option-icon"><i :class="item.icon" aria-hidden="true"></i></span>
            <span class="picker-option-copy"><span class="picker-option-title">{{ item.label }}</span><small>{{ item.location }}</small></span>
            <span v-if="item.added" class="picker-added"><i class="ri-check-line" aria-hidden="true"></i>已添加</span>
            <span v-else-if="item.id.startsWith('external:')" class="picker-external">外部应用</span>
          </label>
          <div v-if="!visibleMenus.length" class="picker-empty" role="status">
            <i :class="query.trim() ? 'ri-search-line' : 'ri-menu-line'" aria-hidden="true"></i>
            <strong>{{ query.trim() ? '没有找到匹配的菜单' : filter === 'added' ? '这个分组还没有菜单' : filter === 'hidden' ? '没有未显示的菜单' : '暂无可用菜单' }}</strong>
            <p>{{ query.trim() ? '换个关键词，或清除搜索查看全部菜单。' : '切换到全部菜单，选择要加入这个分组的功能。' }}</p>
            <button type="button" @click="query = ''; filter = 'all'; searchInput?.focus()">{{ query.trim() ? '清除搜索' : '查看全部菜单' }}</button>
          </div>
        </div>

        <div v-if="error" class="picker-error" role="alert"><i class="ri-error-warning-line" aria-hidden="true"></i><div><strong>添加失败</strong><p>{{ error }}</p><button v-if="nav.layoutConflict || nav.layoutError" type="button" :disabled="busy" @click="refreshLayout">{{ refreshing ? '正在读取…' : '读取最新布局，保留选择' }}</button><small v-else>选择已保留，可以重试添加。</small></div></div>
        <footer class="picker-footer">
          <div class="picker-selection" role="status"><strong>已选 <b>{{ selectedIds.length }}</b> 项</strong><span>添加后，菜单将从原位置移到本组。</span></div>
          <div class="picker-actions">
            <button type="button" class="picker-cancel" :disabled="busy" @click="close">取消</button>
            <button type="button" class="picker-submit" :disabled="busy || !selectedIds.length || !nav.canMoveMenus" @click="addSelected"><i v-if="saving" class="ri-loader-4-line" aria-hidden="true"></i>{{ saving ? '添加中…' : `${error ? '重试添加' : '添加'}${selectedIds.length ? ` ${selectedIds.length} 项` : '菜单'}` }}</button>
          </div>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.group-picker-mask { position: fixed; inset: 0; z-index: 1310; display: grid; place-items: center; padding: 24px; background: rgba(14, 18, 32, .38); }
.group-picker { display: flex; flex-direction: column; width: min(600px, 100%); height: min(680px, calc(100dvh - 48px)); max-height: calc(100dvh - 48px); overflow: hidden; box-sizing: border-box; border: 1px solid var(--wao-line); border-radius: var(--wao-radius-modal); background: var(--wao-bg-card); color: var(--wao-text); box-shadow: var(--wao-shadow-modal); outline: none; }
.picker-heading, .picker-tools, .picker-list-heading { flex: none; }
:global(:root[data-theme='dark']) .group-picker { color-scheme: dark; }
.picker-heading { display: flex; align-items: flex-start; gap: 12px; padding: 24px 24px 20px; }
.picker-heading-icon { display: grid; place-items: center; width: 42px; height: 42px; flex: none; border-radius: 11px; background: var(--wao-primary-soft); color: var(--wao-primary-text); font-size: 22px; }
.picker-heading-copy { flex: 1; min-width: 0; }
.picker-heading h2 { margin: 0; font-size: 20px; line-height: 1.4; font-weight: 650; }
.picker-heading p { margin: 6px 0 0; color: var(--wao-text-3); font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.picker-heading p strong { color: var(--wao-text-2); font-weight: 600; }
.picker-icon-button { display: grid; place-items: center; width: 32px; height: 32px; flex: none; padding: 0; border: 0; border-radius: 8px; background: transparent; color: var(--wao-text-3); font-size: 19px; cursor: pointer; }
.picker-icon-button:hover:not(:disabled) { background: var(--wao-bg-hover); color: var(--wao-text); }
.picker-tools { padding: 0 24px 16px; border-bottom: 1px solid var(--wao-divider); }
.picker-search { display: flex; align-items: center; gap: 9px; height: 42px; padding: 0 10px 0 13px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-weak); }
.picker-search:focus-within { border-color: var(--wao-primary); box-shadow: 0 0 0 3px var(--wao-primary-soft); }
.picker-search > i { font-size: 18px; }
.picker-search input { width: 100%; min-width: 0; flex: 1; height: 100%; padding: 0; border: 0; outline: none; background: transparent; color: var(--wao-text); font: inherit; font-size: 13px; }
.picker-search input::-webkit-search-cancel-button { display: none; }
.picker-filters { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
.picker-filters button { display: inline-flex; align-items: center; gap: 7px; min-height: 32px; padding: 5px 10px; border: 1px solid transparent; border-radius: 7px; background: transparent; color: var(--wao-text-3); font: inherit; font-size: 12px; cursor: pointer; }
.picker-filters button:hover:not(:disabled) { background: var(--wao-bg-hover); }
.picker-filters button[aria-pressed='true'] { background: var(--wao-primary-soft); color: var(--wao-primary-text); font-weight: 600; }
.picker-filters span { font-size: 11px; font-variant-numeric: tabular-nums; }
.picker-list-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 15px 24px 8px; color: var(--wao-text-3); font-size: 12px; }
.picker-list-heading button, .picker-empty button, .picker-error button { padding: 3px 0; border: 0; background: transparent; color: var(--wao-primary-text); font: inherit; font-size: 12px; cursor: pointer; }
.group-picker-list { display: flex; flex: 1; flex-direction: column; gap: 6px; min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; padding: 4px 16px 16px 24px; }
.picker-option { display: grid; grid-template-columns: 18px 38px minmax(0, 1fr) auto; align-items: center; gap: 12px; flex: none; min-height: 68px; padding: 11px 12px; box-sizing: border-box; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); cursor: pointer; transition: background .12s, border-color .12s; }
.picker-option:hover:not(.added):not(.busy) { border-color: var(--wao-line-control); background: var(--wao-bg-hover); }
.picker-option.selected { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.picker-option:focus-within { outline: 2px solid var(--wao-primary); outline-offset: 1px; }
.picker-option > input { width: 17px; height: 17px; margin: 0; accent-color: var(--wao-primary); cursor: inherit; }
.picker-option-icon { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-text-2); font-size: 19px; }
.selected .picker-option-icon { background: var(--wao-bg-card); color: var(--wao-primary-text); }
.picker-option-copy { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.picker-option-title { font-size: 14px; font-weight: 550; line-height: 1.4; overflow-wrap: anywhere; }
.picker-option-copy small { color: var(--wao-text-3); font-size: 12px; line-height: 1.4; overflow-wrap: anywhere; }
.picker-external, .picker-added { display: inline-flex; align-items: center; gap: 4px; color: var(--wao-text-3); font-size: 11px; white-space: nowrap; }
.picker-external { padding: 3px 6px; border: 1px solid var(--wao-line); border-radius: 4px; }
.picker-option.added { border-color: transparent; background: var(--wao-bg-sub); cursor: default; }
.picker-option.added .picker-option-icon { background: var(--wao-bg-card); }
.picker-option.busy { cursor: wait; }
.picker-empty { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; gap: 8px; min-height: 180px; padding: 20px; text-align: center; }
.picker-empty > i { margin-bottom: 4px; color: var(--wao-text-weak); font-size: 30px; }
.picker-empty strong { font-size: 14px; font-weight: 600; }
.picker-empty p { margin: 0; color: var(--wao-text-3); font-size: 12px; line-height: 1.6; }
.picker-error { display: flex; gap: 9px; flex: none; max-height: 140px; overflow-y: auto; margin: 0 24px 16px; padding: 10px 12px; border-radius: 8px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); font-size: 12px; line-height: 1.5; }
.picker-error > i { font-size: 17px; }
.picker-error p { margin: 3px 0; overflow-wrap: anywhere; }
.picker-error small { font-size: 12px; }
.picker-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex: none; padding: 18px 24px; border-top: 1px solid var(--wao-divider); background: var(--wao-bg-sub); }
.picker-selection { display: flex; flex-direction: column; gap: 5px; }
.picker-selection strong { font-size: 13px; font-weight: 500; }
.picker-selection b { color: var(--wao-primary-text); font-weight: 650; }
.picker-selection > span { color: var(--wao-text-3); font-size: 11.5px; line-height: 1.5; }
.picker-actions { display: flex; gap: 8px; flex: none; }
.picker-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 38px; padding: 0 14px; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-2); font: inherit; font-size: 13px; cursor: pointer; }
.picker-actions .picker-submit { min-width: 108px; border-color: var(--wao-primary); background: var(--wao-primary); color: #fff; }
.picker-submit:hover:not(:disabled) { border-color: var(--wao-primary-hover); background: var(--wao-primary-hover); }
.picker-cancel:hover:not(:disabled) { background: var(--wao-bg-hover); }
button:disabled { opacity: .55; cursor: default; }
.picker-submit .ri-loader-4-line { animation: picker-spin 1s linear infinite; }
@keyframes picker-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .picker-option { transition: none; } .picker-submit .ri-loader-4-line { animation: none; } }
@media (max-width: 600px) {
  .group-picker-mask { padding: 12px; }
  .group-picker { height: min(680px, calc(100dvh - 24px)); max-height: calc(100dvh - 24px); }
  .picker-heading { gap: 10px; padding: 18px 16px 16px; }
  .picker-heading-icon { width: 36px; height: 36px; font-size: 20px; }
  .picker-heading h2 { font-size: 18px; }
  .picker-tools { padding: 0 16px 12px; }
  .picker-search input { font-size: 16px; }
  .picker-list-heading { padding: 12px 16px 8px; }
  .group-picker-list { padding: 4px 12px 12px 16px; }
  .picker-option { grid-template-columns: 18px 32px minmax(0, 1fr); gap: 9px; padding: 10px; }
  .picker-option-icon { width: 32px; height: 32px; font-size: 18px; }
  .picker-added, .picker-external { grid-column: 3; justify-self: start; }
  .picker-error { margin: 0 16px 12px; }
  .picker-footer { flex-wrap: wrap; gap: 12px; padding: 14px 16px; }
  .picker-actions { width: 100%; }
  .picker-actions button { min-height: 42px; }
  .picker-actions .picker-submit { flex: 1; }
}
@media (max-height: 540px) { .picker-heading { padding-top: 14px; padding-bottom: 12px; } .picker-tools { padding-bottom: 10px; } .picker-filters { margin-top: 8px; } .picker-footer { padding-top: 12px; padding-bottom: 12px; } }
</style>
