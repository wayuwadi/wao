<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { Project } from '@/types'
import { useTaskOrganizationPreference } from '@/composables/taskOrganizationPreference'
import { useSessionStore } from '@/stores/session'
import { decodeTaskFilters, emptyTaskOrganizationFilter, normalizeTaskOrganizationFilter, taskOrganizationFilterCount, type TaskOrganizationFilter } from '@/utils/taskOrganization'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'

defineProps<{ compact?: boolean; projects: Project[]; tags: string[] }>()
const filter = defineModel<TaskOrganizationFilter>({ required: true })
const preferences = useTaskOrganizationPreference('todo-named-filters-v1', decodeTaskFilters)
const session = useSessionStore()
const identity = computed(() => `${session.account?.accountId ?? ''}:${session.userId}`)
const open = ref(false), draft = ref(emptyTaskOrganizationFilter()), name = ref(''), error = ref(''), chosen = ref('')
const count = computed(() => taskOrganizationFilterCount(filter.value))
const ready = computed(() => preferences.loaded.value && preferences.state.value !== 'loading' && preferences.state.value !== 'saving' && !preferences.error.value)
const presets = preferences.value
const selectedPreset = computed(() => presets.value.find(item => item.id === chosen.value))
const draftKey = () => `wao-task-filter-draft:${identity.value}`
let generation = 0
onBeforeUnmount(() => { generation++ })
watch(identity, () => {
  generation++
  open.value = false; chosen.value = ''; name.value = ''; error.value = ''
  try { filter.value = normalizeTaskOrganizationFilter(JSON.parse(sessionStorage.getItem(`wao-task-filter:${identity.value}`) || 'null')) }
  catch { filter.value = emptyTaskOrganizationFilter() }
}, { immediate: true, flush: 'sync' })
watch(filter, value => {
  try { sessionStorage.setItem(`wao-task-filter:${identity.value}`, JSON.stringify(value)) }
  catch { error.value = '筛选已生效，但无法保留本标签页的选择。' }
}, { deep: true })
function protect() {
  try { localStorage.setItem(draftKey(), JSON.stringify({ filter: draft.value, name: name.value })); error.value = '' }
  catch { error.value = '筛选草稿未能写入本机，请保持窗口打开。' }
}
function edit() {
  error.value = ''; name.value = selectedPreset.value?.name ?? ''
  draft.value = normalizeTaskOrganizationFilter(filter.value)
  try {
    const saved = JSON.parse(localStorage.getItem(draftKey()) || 'null')
    if (saved) { draft.value = normalizeTaskOrganizationFilter(saved.filter); name.value = typeof saved.name === 'string' ? saved.name : '' }
  } catch { error.value = '无法读取筛选草稿，当前条件仍可编辑。' }
  open.value = true
}
function valid() {
  if (draft.value.from && draft.value.to && draft.value.from > draft.value.to) { error.value = '结束日期不能早于开始日期。'; return false }
  return true
}
function clearDraft() { try { localStorage.removeItem(draftKey()) } catch { /* Applied filter remains usable. */ } }
function apply() {
  if (!valid()) return
  filter.value = normalizeTaskOrganizationFilter(draft.value)
  chosen.value = ''; open.value = false; clearDraft()
}
async function savePreset() {
  if (!ready.value || !valid()) return
  const label = name.value.trim()
  if (!label) { error.value = '请输入筛选名称。'; return }
  if (presets.value.some(item => item.name === label && item.id !== chosen.value)) { error.value = '已有同名筛选，请使用其他名称。'; return }
  if (!chosen.value && presets.value.length >= 30) { error.value = '最多保存 30 个筛选。'; return }
  protect()
  const attempt = generation, owner = identity.value, id = chosen.value || crypto.randomUUID()
  const value = { id, name: label.slice(0, 40), filter: normalizeTaskOrganizationFilter(draft.value) }
  const next=[...presets.value.filter(item => item.id !== id), value]
  if(JSON.stringify(next).length>60_000) {error.value='筛选内容过多，请减少条件后重试。';return}
  await preferences.set(next)
  if (attempt !== generation || identity.value !== owner) return
  if (preferences.error.value) { error.value = preferences.error.value; return }
  filter.value = value.filter; chosen.value = id; open.value = false; clearDraft()
}
function usePreset(event: Event) {
  chosen.value = (event.target as HTMLSelectElement).value
  const item = presets.value.find(item => item.id === chosen.value)
  if (item) filter.value = normalizeTaskOrganizationFilter(item.filter)
  else filter.value = emptyTaskOrganizationFilter()
}
function useDraftPreset(event: Event) {
  chosen.value = (event.target as HTMLSelectElement).value
  const item = presets.value.find(item => item.id === chosen.value)
  draft.value = normalizeTaskOrganizationFilter(item?.filter)
  name.value = item?.name || ''
  protect()
}
async function removePreset() {
  if (!ready.value || !chosen.value) return
  const attempt = generation, previous = presets.value, id = chosen.value, owner = identity.value
  await preferences.set(previous.filter(item => item.id !== id))
  if (attempt !== generation || owner !== identity.value) return
  if (!preferences.error.value) { chosen.value = ''; name.value = '' }
}
function toggle(field: 'projects' | 'priorities' | 'tags', value: string) {
  const values = draft.value[field]
  draft.value[field] = values.includes(value) ? values.filter(item => item !== value) : [...values, value]
  protect()
}
</script>
<template>
  <div class="todo-organization" aria-label="组合筛选">
    <WaoButton variant="ghost" :aria-pressed="!!count" @click="edit"><i class="ri-filter-3-line" aria-hidden="true" />筛选<span v-if="count"> · {{ count }}</span></WaoButton>
    <select v-if="presets.length && !compact" :value="chosen" aria-label="已保存筛选" @change="usePreset"><option value="">已保存筛选</option><option v-for="item in presets" :key="item.id" :value="item.id">{{ item.name }}</option></select>
    <WaoButton v-if="count && !compact" variant="ghost" @click="filter=emptyTaskOrganizationFilter();chosen=''">清除筛选</WaoButton>
    <small v-if="count && !compact">仅筛选当前清单范围</small>
    <span v-if="preferences.error.value" class="feedback" role="status">{{ preferences.error.value }}<WaoButton variant="ghost" @click="preferences.retry()">重试同步</WaoButton></span>
    <SettingsDialogShell v-if="open" title="组合筛选" description="条件之间取交集；不会修改任务内容或共享范围。" icon="ri-filter-3-line" width="640px" height="min(680px, calc(100dvh - 48px))" :close-disabled="preferences.state.value==='saving'" @close="protect();open=false">
      <div class="filter-form" :inert="preferences.state.value==='saving'" @change="protect">
        <WaoField v-if="compact && presets.length" v-slot="field" label="已保存筛选"><select :id="field.id" :value="chosen" aria-label="已保存筛选" @change="useDraftPreset"><option value="">选择已保存筛选</option><option v-for="item in presets" :key="item.id" :value="item.id">{{ item.name }}</option></select></WaoField>
        <div class="field-pair">
          <WaoField v-slot="field" label="日期"><select :id="field.id" v-model="draft.date"><option value="all">不限日期</option><option value="today">今天（含逾期）</option><option value="tomorrow">明天</option><option value="week">最近 7 天</option><option value="overdue">已逾期未完成</option><option value="undated">无执行或截止日期</option></select></WaoField>
          <WaoField v-slot="field" label="完成状态"><select :id="field.id" v-model="draft.status"><option value="all">不限状态</option><option value="unfinished">未完成</option><option value="completed">已完成</option></select></WaoField>
          <WaoField v-slot="field" label="起始日期"><input :id="field.id" v-model="draft.from" type="date" /></WaoField>
          <WaoField v-slot="field" label="结束日期"><input :id="field.id" v-model="draft.to" type="date" /></WaoField>
        </div>
        <fieldset><legend>优先级 · 不选表示全部</legend><div class="choices"><WaoButton v-for="p in ['P0','P1','P2','P3']" :key="p" :aria-pressed="draft.priorities.includes(p)" @click="toggle('priorities',p)">{{ p }}</WaoButton></div></fieldset>
        <fieldset><legend>清单 · 不选表示全部</legend><div class="choices scroll-options"><WaoButton :aria-pressed="draft.projects.includes('')" @click="toggle('projects','')">收集箱</WaoButton><WaoButton v-for="project in projects" :key="project.id" :aria-pressed="draft.projects.includes(project.id)" @click="toggle('projects',project.id)">{{ project.name }}</WaoButton></div></fieldset>
        <fieldset><legend>标签</legend><select v-model="draft.tagMode" aria-label="标签匹配方式"><option value="any">匹配任一所选标签</option><option value="all">包含全部所选标签</option><option value="none">没有标签</option></select><div v-if="draft.tagMode!=='none'" class="choices scroll-options"><WaoButton v-for="tag in [...new Set([...tags,...draft.tags])]" :key="tag" :aria-pressed="draft.tags.includes(tag)" @click="toggle('tags',tag)">{{ tag }}</WaoButton><small v-if="!tags.length && !draft.tags.length">尚无任务标签</small></div></fieldset>
        <WaoField v-slot="field" label="保存为常用筛选（可选）"><input :id="field.id" v-model="name" maxlength="40" placeholder="例如：本周重要工作" @input="protect" /></WaoField>
        <div class="preset-actions"><WaoButton :disabled="!ready || !name.trim()" @click="savePreset">{{ chosen ? '更新已保存筛选' : '保存为筛选' }}</WaoButton><WaoButton v-if="chosen" variant="ghost" :disabled="!ready" @click="removePreset">移除已保存入口</WaoButton><small v-if="!preferences.loaded.value">{{ preferences.error.value ? '同步恢复后可保存筛选。' : '正在读取账号筛选…' }}</small></div>
        <div v-if="preferences.conflict.value" class="conflict-choice" role="status"><details><summary>查看账号版本</summary><ul><li v-for="item in preferences.remote.value" :key="item.id">{{ item.name }} · {{ item.filter.date==='all'?'不限日期':item.filter.date==='week'?'最近7天':item.filter.date==='today'?'今天':item.filter.date==='tomorrow'?'明天':item.filter.date==='overdue'?'逾期':'无日期' }} · {{ item.filter.priorities.join('、') || '全部优先级' }} · {{ item.filter.tags.join('、') || (item.filter.tagMode==='none'?'无标签':'全部标签') }}</li></ul></details><WaoButton :disabled="preferences.state.value==='loading' || !preferences.remoteReady.value" @click="preferences.useRemote();error=''">使用账号版本</WaoButton><WaoButton :disabled="preferences.state.value==='loading' || !preferences.remoteReady.value" @click="preferences.keepLocal();error=''">保留本机版本并保存</WaoButton></div>
        <p v-if="error || preferences.error.value" class="error" role="alert">{{ error || preferences.error.value }}</p>
      </div>
      <template #footer><div class="footer"><WaoButton variant="ghost" @click="draft=emptyTaskOrganizationFilter();protect()">重置条件</WaoButton><span /><WaoButton :disabled="preferences.state.value==='saving'" @click="open=false;clearDraft()">取消</WaoButton><WaoButton variant="primary" :disabled="preferences.state.value==='saving'" @click="apply">应用筛选</WaoButton></div></template>
    </SettingsDialogShell>
  </div>
</template>
<style scoped>
.conflict-choice { padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); display:flex; gap:var(--wao-space-1); flex-wrap:wrap; } .conflict-choice details { flex:1 0 100%; }
.todo-organization,.choices,.preset-actions,.footer { display:flex; align-items:center; gap:var(--wao-space-1); flex-wrap:wrap; }
.todo-organization { margin-bottom:var(--wao-space-1); font:400 var(--wao-font-body)/var(--wao-line-body) var(--wao-font); }
.filter-form { padding:var(--wao-space-2); display:grid; gap:var(--wao-space-2); color:var(--wao-text); font:400 var(--wao-font-body)/var(--wao-line-body) var(--wao-font); }
.field-pair { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:var(--wao-space-2); }
fieldset { margin:0; padding:0; border:0; min-width:0; } legend { margin-bottom:var(--wao-space-1); }
.scroll-options { max-height:160px; overflow:auto; margin-top:var(--wao-space-1); }
select { max-width:100%; min-width:0; border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); padding:var(--wao-space-1); color:var(--wao-text); background:var(--wao-bg-card); font:inherit; }
.choices :deep(button) { max-width:100%; overflow-wrap:anywhere; }.choices :deep([aria-pressed=true]) { background:var(--wao-primary-soft); color:var(--wao-primary-text); border-color:var(--wao-primary); }
small,.feedback { font-size:var(--wao-font-caption); color:var(--wao-text-3); }.error { color:var(--wao-danger-strong); margin:0; }.footer { width:100%; }.footer>span { flex:1; }
input,select { box-sizing:border-box; } input:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(max-width:390px) { .field-pair { grid-template-columns:minmax(0,1fr); } }
@media(pointer:coarse) { select { min-height:44px; } }
</style>
