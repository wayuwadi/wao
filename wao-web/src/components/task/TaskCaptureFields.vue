<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { recognizeTaskTime, type CaptureFields } from '@/utils/taskCapture'
import type { Project } from '@/types'
import WaoButton from '@/components/ui/WaoButton.vue'
const props = defineProps<{ title: string; disabled?: boolean; saved?: boolean; projects?: Pick<Project, 'id' | 'name'>[] }>()
const fields = defineModel<CaptureFields>({ required: true })
const emit = defineEmits<{ title: [value: string] }>()
const data = useDataStore(), dismissed = ref('')
const availableProjects = computed(() => props.projects ?? data.projects)
const preview = computed(() => dismissed.value === props.title ? null : recognizeTaskTime(props.title, data.today))
watch(() => props.title, value => { if (!value) dismissed.value = '' })
function update(key: keyof CaptureFields, event: Event) { if (props.disabled || (props.saved && key !== 'tags')) return; fields.value={...fields.value,[key]:(event.target as HTMLInputElement).value} }
function apply() {
  const result = preview.value
  if (!result || props.disabled || props.saved) return
  fields.value = { ...fields.value, start: result.start, startAt: result.startAt, endAt: result.endAt }
  emit('title', result.title || props.title)
  dismissed.value = result.title || props.title
}
</script>
<template>
  <div class="capture-fields">
    <p v-if="saved" role="status">任务已保存，可调整标签后重试同步。其他属性可在任务详情中修改。</p>
    <div v-if="preview && !saved" class="time-preview" role="status"><span>识别到执行安排：{{ preview.startAt.replace('T',' ') || preview.start }} · 上海时间</span><WaoButton type="button" :disabled="disabled || saved" @click="apply">采用</WaoButton><WaoButton type="button" :disabled="disabled || saved" @click="dismissed=title">忽略</WaoButton></div>
    <details><summary>清单、日期、优先级与标签</summary><div class="field-grid">
      <label>清单<select aria-label="所属清单" :value="fields.project" @input="update('project',$event)" :disabled="disabled || saved"><option value="">收集箱</option><option v-if="fields.project && !availableProjects.some(p => p.id === fields.project)" :value="fields.project">当前清单暂不可用</option><option v-for="p in availableProjects" :key="p.id" :value="p.id">{{ p.name }}</option></select></label>
      <label>优先级<select aria-label="优先级" :value="fields.pr" @input="update('pr',$event)" :disabled="disabled || saved"><option v-for="p in ['P0','P1','P2','P3']" :key="p" :value="p">{{ p }}</option></select></label>
      <label>截止日期<input aria-label="截止日期" :value="fields.due" @input="update('due',$event)" type="date" :disabled="disabled || saved" /></label><label>执行日期<input aria-label="执行日期" :value="fields.start" @input="update('start',$event)" type="date" :disabled="disabled || saved || !!fields.startAt" /></label>
      <label>开始时间<input aria-label="开始时间" :value="fields.startAt" @input="update('startAt',$event)" type="datetime-local" :disabled="disabled || saved" /></label><label>结束时间<input aria-label="结束时间" :value="fields.endAt" @input="update('endAt',$event)" type="datetime-local" :disabled="disabled || saved" /></label>
      <label class="tags">标签<input :value="fields.tags" @input="update('tags',$event)" placeholder="用逗号分隔" :disabled="disabled" maxlength="820" /></label>
    </div><small>执行安排与截止日期独立；时间按上海时间记录。</small></details>
    <small v-if="fields.start || fields.startAt || fields.due || fields.tags">{{ fields.startAt.replace('T',' ') || fields.start }}{{ fields.due ? ` · 截止 ${fields.due}` : '' }}{{ fields.tags ? ` · ${fields.tags}` : '' }}</small>
  </div>
</template>
<style scoped>
.capture-fields { font:400 var(--wao-font-help)/1.5 var(--wao-font); color:var(--wao-text-3); padding:var(--wao-space-1); }
summary { cursor:pointer; min-height:28px; } .time-preview { display:flex; align-items:center; flex-wrap:wrap; gap:var(--wao-space-1); color:var(--wao-primary-text); }
.field-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:var(--wao-space-1); margin-block:var(--wao-space-1); }
label { display:grid; gap:4px; min-width:0; } .tags { grid-column:1/-1; }
input,select { width:100%; min-width:0; box-sizing:border-box; font:inherit; color:var(--wao-text); background:var(--wao-bg-card); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); padding:var(--wao-space-1); }
small { font-size:var(--wao-font-caption); } input:focus-visible,select:focus-visible,summary:focus-visible { outline:2px solid var(--wao-primary); }
@media(max-width:390px) { .field-grid { grid-template-columns:minmax(0,1fr); } }
@media(pointer:coarse) { summary,input,select { min-height:44px; } }
</style>
