<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import CellMenu from '@/components/task/CellMenu.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { TODO_GROUPS, TODO_SORTS, TODO_VIEWS, type TodoViewSettings, type TodoDisplaySettings } from '@/utils/todoViewSettings'

const props = defineProps<{ selection: string; view: TodoViewSettings; display: TodoDisplaySettings; syncError: string }>()
const emit = defineEmits<{ view: [Partial<TodoViewSettings>]; display: [Partial<TodoDisplaySettings>]; retry: [] }>()
const more = ref<HTMLElement | null>(null), order = ref<HTMLElement | null>(null)
const menu = ref<'view' | 'order' | null>(null)
const orderPage = ref<'group' | 'sort' | 'direction' | null>(null)
const settingsOpen = ref(false)
const orderPanel = ref<HTMLElement | null>(null)
const layouts = computed(() => TODO_VIEWS.filter(v => props.selection !== 'abandoned' || v.id !== 'quadrant'))
const directions = computed(() => props.view.sort === 'priority'
  ? { asc: '从低到高', desc: '从高到低' }
  : { asc: '升序', desc: '降序' })
const groupLabel = computed(() => props.view.group === 'auto' ? props.view.layout === 'board' ? '状态' : props.selection === 'today' ? '时间' : '默认' : TODO_GROUPS[props.view.group])
const canToggleCompleted = computed(() => !['completed', 'abandoned'].includes(props.selection))
function toggle(kind: 'view' | 'order') {
  menu.value = menu.value === kind ? null : kind
  orderPage.value = null
}
async function closeAndFocus(anchor: HTMLElement | null) {
  menu.value = null
  await nextTick()
  if (anchor?.isConnected) anchor.focus()
  else document.querySelector<HTMLButtonElement>('.view-controls [aria-label="更多视图选项"]')?.focus()
}
async function orderSection(page: typeof orderPage.value, focusLabel?: string) {
  orderPage.value = page
  await nextTick()
  const buttons = Array.from(orderPanel.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
  ;(focusLabel ? buttons.find(button => button.dataset.section === focusLabel) : buttons[0])?.focus()
}
function chooseOrder(value: Partial<TodoViewSettings>) {
  emit('view', value)
  void orderSection(null, orderPage.value ?? undefined)
}
async function openSettings() {
  closeAndFocus(more.value)
  await nextTick()
  settingsOpen.value = true
}
watch(() => props.selection, () => { menu.value = null; settingsOpen.value = false })
</script>

<template>
  <div class="view-controls">
    <button v-if="view.layout!=='quadrant'" ref="order" class="control" type="button" title="分组与排序" aria-label="分组与排序" aria-haspopup="menu" :aria-expanded="menu==='order'" @click="toggle('order')"><i class="ri-sort-desc" aria-hidden="true" /></button>
    <button ref="more" class="control" type="button" title="更多视图选项" aria-label="更多视图选项" aria-haspopup="menu" :aria-expanded="menu==='view'" @click="toggle('view')"><i class="ri-more-line" aria-hidden="true" /></button>
  </div>
  <CellMenu v-if="menu==='view'" :anchor="more" :min-width="224" align-end aria-label="视图选项" @close="menu=null">
    <span class="menu-caption" role="presentation">视图</span>
    <div class="view-grid" role="none">
      <button v-for="item in layouts.filter(item=>item.id!=='quadrant')" :key="item.id" type="button" role="menuitemradio" :aria-label="item.label" :title="item.label" :aria-checked="view.layout===item.id"
        @click="emit('view',{layout:item.id}); closeAndFocus(more)">
        <i :class="item.icon" aria-hidden="true" /><span>{{ item.label }}</span>
      </button>
    </div>
    <button v-if="layouts.some(item=>item.id==='quadrant')" type="button" role="menuitemradio" :aria-checked="view.layout==='quadrant'" @click="emit('view',{layout:'quadrant'}); closeAndFocus(more)">
      <span><i class="ri-layout-grid-line" aria-hidden="true" /> 四象限</span><i v-if="view.layout==='quadrant'" class="ri-check-line" aria-hidden="true" />
    </button>
    <button v-if="canToggleCompleted" class="menu-section" type="button" role="menuitemcheckbox" :aria-checked="view.showDone" @click="emit('view',{showDone:!view.showDone})">
      <span><i class="ri-checkbox-circle-line" aria-hidden="true" /> 显示已完成</span><i v-if="view.showDone" class="ri-check-line" aria-hidden="true" />
    </button>
    <button v-if="view.layout!=='quadrant'" type="button" role="menuitemcheckbox" :aria-checked="view.showDetails" @click="emit('view',{showDetails:!view.showDetails})">
      <span><i class="ri-list-unordered" aria-hidden="true" /> 显示详细</span><i v-if="view.showDetails" class="ri-check-line" aria-hidden="true" />
    </button>
    <button type="button" role="menuitem" @click="openSettings"><span><i class="ri-settings-4-line" aria-hidden="true" /> 显示设置</span></button>
  </CellMenu>
  <CellMenu v-if="menu==='order'" :anchor="order" :min-width="232" align-end aria-label="分组与排序" @close="menu=null">
    <div ref="orderPanel" class="order-panel" role="none">
      <template v-if="!orderPage">
        <button type="button" role="menuitem" data-section="group" @click="orderSection('group')"><span><i class="ri-layout-row-line" aria-hidden="true" /> 分组</span><small>{{ groupLabel }} <i class="ri-arrow-right-s-line" aria-hidden="true" /></small></button>
        <button type="button" role="menuitem" data-section="sort" @click="orderSection('sort')"><span><i class="ri-sort-desc" aria-hidden="true" /> 排序</span><small>{{ TODO_SORTS[view.sort] }} <i class="ri-arrow-right-s-line" aria-hidden="true" /></small></button>
        <button type="button" role="menuitem" data-section="direction" :disabled="view.sort==='manual'" @click="orderSection('direction')"><span><i class="ri-arrow-up-down-line" aria-hidden="true" /> 顺序</span><small>{{ view.sort==='manual' ? '—' : directions[view.direction] }} <i class="ri-arrow-right-s-line" aria-hidden="true" /></small></button>
      </template>
      <template v-else>
        <button type="button" role="menuitem" class="order-back" @click="orderSection(null,orderPage)"><span><i class="ri-arrow-left-s-line" aria-hidden="true" /> {{ {group:'分组',sort:'排序',direction:'顺序'}[orderPage] }}</span></button>
        <template v-if="orderPage==='group'">
          <button v-for="(label,key) in TODO_GROUPS" :key="key" type="button" role="menuitemradio" :aria-checked="view.group===key" @click="chooseOrder({group:key})">{{ label }}<i v-if="view.group===key" class="ri-check-line" aria-hidden="true" /></button>
        </template>
        <template v-else-if="orderPage==='sort'">
          <button v-for="(label,key) in TODO_SORTS" :key="key" type="button" role="menuitemradio" :aria-checked="view.sort===key" @click="chooseOrder({sort:key,direction:key==='priority'?'desc':'asc'})">{{ label }}<i v-if="view.sort===key" class="ri-check-line" aria-hidden="true" /></button>
        </template>
        <template v-else>
          <button v-for="(label,key) in directions" :key="key" type="button" role="menuitemradio" :aria-checked="view.direction===key" @click="chooseOrder({direction:key})">{{ label }}<i v-if="view.direction===key" class="ri-check-line" aria-hidden="true" /></button>
        </template>
      </template>
    </div>
  </CellMenu>

  <SettingsDialogShell v-if="settingsOpen" title="显示设置" icon="ri-settings-4-line" width="520px" height="auto" @close="settingsOpen=false">
    <div class="display-settings">
      <label class="setting-row"><span>日期显示为</span><select aria-label="日期显示为" :value="display.date" @change="emit('display',{date:($event.target as HTMLSelectElement).value as TodoDisplaySettings['date']})"><option value="time">任务时间</option><option value="countdown">倒数日</option></select></label>
      <fieldset class="setting-row detail-settings">
        <legend>任务详情样式</legend>
        <div class="detail-choices">
          <label v-for="(label,key) in {sidebar:'侧边栏',popup:'弹窗'}" :key="key">
            <span class="detail-preview" :class="key" aria-hidden="true"><span></span><span></span></span>
            <span><input type="radio" name="todo-detail-style" :checked="display.detail===key" @change="emit('display',{detail:key})" /> {{ label }}</span>
          </label>
        </div>
      </fieldset>
      <div class="setting-row"><div><span id="todo-view-sync-label">视图跨设备同步</span><p>关闭后，可在不同浏览器和设备上分别使用各自的视图。</p></div>
        <button class="switch" type="button" role="switch" aria-labelledby="todo-view-sync-label" :aria-checked="display.sync" @click="emit('display',{sync:!display.sync})"><span /></button>
      </div>
      <p v-if="syncError" class="sync-error" role="status">{{ syncError }} <button type="button" @click="emit('retry')">重试同步</button></p>
    </div>
    <template #footer><WaoButton variant="primary" @click="settingsOpen=false">完成</WaoButton></template>
  </SettingsDialogShell>
</template>

<style scoped>
.view-controls { display:flex; margin-inline-start:auto; gap:var(--wao-space-1); }
.control { width:36px; min-height:36px; padding:0; border:0; border-radius:var(--wao-radius-control); background:transparent; color:var(--wao-text-2); font:inherit; cursor:pointer; }
.control:hover,.control[aria-expanded=true] { background:var(--wao-bg-hover); color:var(--wao-primary-text); }
.control i { font-size:var(--wao-font-card-title); }
.menu-caption { display:block; padding:4px 8px; color:var(--wao-text-3); font-size:var(--wao-font-help); }
.view-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:4px; padding:4px; }
.view-grid button { display:flex; flex-direction:column; align-items:center; gap:4px; min-height:56px; padding:6px 2px; border:0; border-radius:var(--wao-radius-control); background:transparent; color:var(--wao-text-2); font:inherit; font-size:var(--wao-font-caption); cursor:pointer; }
.view-grid i { font-size:var(--wao-font-title); }
.view-grid button:hover { background:var(--wao-bg-hover); }
.view-grid button[aria-checked=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.menu-section { border-top:1px solid var(--wao-line) !important; margin-top:4px; }
.order-panel button { display:flex; width:100%; justify-content:space-between; align-items:center; min-height:34px; padding:6px 8px; border:0; background:transparent; border-radius:var(--wao-radius-control); color:var(--wao-text); font:inherit; text-align:left; cursor:pointer; }
.order-panel button:hover,.order-panel button:focus-visible { background:var(--wao-bg-hover); }
.order-panel button[aria-checked=true] { color:var(--wao-primary-text); }
.order-panel button:disabled { color:var(--wao-text-weak); cursor:default; }
.order-panel small,.order-back { color:var(--wao-text-3); font-size:var(--wao-font-help); }
.display-settings { display:grid; gap:var(--wao-space-2); padding:var(--wao-space-2) var(--wao-space-3); }
.setting-row { display:flex; align-items:center; justify-content:space-between; gap:var(--wao-space-2); border:0; margin:0; padding:var(--wao-space-2); border-radius:var(--wao-radius-card); background:var(--wao-bg-sub); color:var(--wao-text); font-size:var(--wao-font-body); }
.setting-row p { margin:4px 0 0; color:var(--wao-text-3); font-size:var(--wao-font-help); }
.setting-row select { min-height:36px; max-width:100%; padding:0 var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; }
.detail-settings { display:block; min-inline-size:0; }
.detail-settings legend { float:left; width:100%; padding:0 0 var(--wao-space-2); }
.detail-choices { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:var(--wao-space-2); clear:both; }
.detail-choices label { display:grid; gap:var(--wao-space-1); text-align:center; cursor:pointer; }
.detail-choices input { accent-color:var(--wao-primary); }
.detail-preview { position:relative; display:block; min-height:74px; background:var(--wao-bg-card); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); }
.detail-preview span:first-child { position:absolute; inset:12% 44% 12% 14%; background:repeating-linear-gradient(to bottom,var(--wao-line) 0 3px,transparent 3px 11px); }
.detail-preview span:last-child { position:absolute; inset:6% 4% 6% 58%; border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-primary-soft); }
.detail-preview.popup span:last-child { inset:21% 22%; box-shadow:var(--wao-shadow-pop); }
.switch { display:flex; align-items:center; flex:none; width:36px; height:21px; padding:2px; border:0; border-radius:var(--wao-radius-pill); background:var(--wao-text-3); cursor:pointer; }
.switch[aria-checked=true] { justify-content:flex-end; background:var(--wao-primary); }
.switch span { width:17px; height:17px; border-radius:50%; background:var(--wao-on-primary); }
.sync-error { margin:0; color:var(--wao-danger-strong); font-size:var(--wao-font-help); }
button:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(pointer:coarse) { .control { width:44px; min-height:44px; } .order-panel button { min-height:44px; } }
</style>
