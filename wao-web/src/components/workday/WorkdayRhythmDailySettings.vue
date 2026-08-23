<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchDocTemplates, fetchKbs } from '@/api/board'
import { fetchFeishuChats, fetchWebhookEndpoints, type FeishuChat, type FeishuWebhookEndpoint } from '@/api/system'
import {
  createRhythmChecklistItem,
  deleteRhythmChecklistItem,
  fetchRhythmChecklistItems,
  fetchRhythmSettings,
  saveRhythmSettings,
  updateRhythmChecklistItem,
  type RhythmChecklistItem,
  type RhythmSettings,
  type SaveRhythmChecklistItem,
} from '@/api/workdayRhythm'
import type { DocTemplate, KnowledgeBase } from '@/types'

const emit = defineEmits<{ saved: [] }>()
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const saved = ref(false)
const form = ref<RhythmSettings | null>(null)
const items = ref<Array<RhythmChecklistItem & { fresh?: boolean }>>([])
const kbs = ref<KnowledgeBase[]>([])
const templates = ref<DocTemplate[]>([])
const chats = ref<FeishuChat[]>([])
const webhooks = ref<FeishuWebhookEndpoint[]>([])
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const folders = computed(() => kbs.value.find((kb) => kb.id === form.value?.documentKbId)?.folders ?? [])

function payload(item: RhythmChecklistItem): SaveRhythmChecklistItem {
  return {
    category: item.category, title: item.title, scheduleType: item.scheduleType,
    weekdays: item.weekdays, startTime: item.startTime, dueTime: item.dueTime,
    dueNextDay: item.dueNextDay, active: item.active, order: item.order,
    notifyDirect: item.notifyDirect, notifyChat: item.notifyChat,
    chatId: item.chatId, chatName: item.chatName, notifyWebhook: item.notifyWebhook,
    webhookId: item.webhookId, webhookName: item.webhookName,
    reminderTime: item.reminderTime, reminderNextDay: item.reminderNextDay,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [settings, checklist, knowledgeBases, docTemplates, chatOptions, webhookOptions] = await Promise.all([
      fetchRhythmSettings(), fetchRhythmChecklistItems(), fetchKbs(), fetchDocTemplates(),
      fetchFeishuChats().catch(() => []), fetchWebhookEndpoints().catch(() => []),
    ])
    form.value = structuredClone(settings)
    items.value = structuredClone(checklist)
    kbs.value = knowledgeBases
    templates.value = docTemplates
    chats.value = chatOptions
    webhooks.value = webhookOptions
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日面板设置读取失败'
  } finally {
    loading.value = false
  }
}

function selectKb() {
  if (!form.value) return
  form.value.documentFolderId = null
}

function addItem() {
  items.value.push({
    id: `new-${Date.now()}`, kind: 'CUSTOM', category: 'LIFE', title: '', scheduleType: 'DAILY',
    weekdays: [], startTime: '07:00', dueTime: '23:59', dueNextDay: false, active: true,
    order: items.value.length * 10 + 50, notifyDirect: false, notifyChat: false,
    chatId: null, chatName: null, notifyWebhook: false, webhookId: null, webhookName: null,
    reminderTime: null, reminderNextDay: false, fresh: true,
  })
}

function toggleWeekday(item: RhythmChecklistItem, day: number) {
  item.weekdays = item.weekdays.includes(day)
    ? item.weekdays.filter((value) => value !== day)
    : [...item.weekdays, day].sort()
}

function selectChat(item: RhythmChecklistItem) {
  item.chatName = chats.value.find((chat) => chat.chatId === item.chatId)?.name ?? null
}

function selectWebhook(item: RhythmChecklistItem) {
  item.webhookName = webhooks.value.find((webhook) => webhook.id === item.webhookId)?.name ?? null
}

async function removeItem(item: RhythmChecklistItem & { fresh?: boolean }) {
  if (item.kind !== 'CUSTOM') {
    item.active = false
    return
  }
  if (item.fresh) {
    items.value = items.value.filter((entry) => entry.id !== item.id)
    return
  }
  if (!window.confirm(`删除“${item.title}”？历史完成记录会继续保留。`)) return
  try {
    await deleteRhythmChecklistItem(item.id)
    items.value = items.value.filter((entry) => entry.id !== item.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '清单项删除失败'
  }
}

async function submit() {
  if (!form.value || saving.value) return
  saving.value = true
  saved.value = false
  error.value = ''
  try {
    await saveRhythmSettings(form.value)
    await Promise.all(items.value.map((item) => item.fresh
      ? createRhythmChecklistItem(payload(item))
      : updateRhythmChecklistItem(item.id, payload(item))))
    saved.value = true
    emit('saved')
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日面板设置保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="daily-settings">
    <header><div><small>DAILY DASHBOARD</small><h3>每日面板与必做清单</h3><p>把每天需要惦记的事情模板化；通知默认关闭，只有主动选择通道后才发送。</p></div><button type="button" @click="load"><i class="ri-refresh-line"></i>刷新</button></header>
    <div v-if="loading" class="settings-state"><i class="ri-loader-4-line spin"></i>正在读取每日面板设置…</div>
    <div v-else-if="!form" class="settings-state error"><i class="ri-error-warning-line"></i>{{ error }}<button @click="load">重试</button></div>
    <template v-else>
      <div class="setting-grid">
        <label class="switch-row"><input v-model="form.followAlternateWeek" type="checkbox" /><span><b>自动跟随大小周</b><small>周一至周五及大周周日执行；当天仍可手动覆盖</small></span></label>
        <label class="switch-row"><input v-model="form.tianrenEnabled" type="checkbox" /><span><b>展示每日卦象</b><small>只展示入口，不会打开页面就自动起卦</small></span></label>
        <label class="switch-row"><input v-model="form.tianrenRequired" type="checkbox" :disabled="!form.tianrenEnabled" /><span><b>卦象纳入每日必做</b><small>起卦成功后自动完成对应清单项</small></span></label>
      </div>

      <section class="document-settings"><h4><i class="ri-file-text-line"></i>每日工作文档</h4><div class="document-grid">
        <label><span>知识库</span><select v-model="form.documentKbId" @change="selectKb"><option :value="null">请选择知识库</option><option v-for="kb in kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option></select></label>
        <label><span>文件夹</span><select v-model="form.documentFolderId" :disabled="!form.documentKbId"><option :value="null">请选择文件夹</option><option v-for="folder in folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label>
        <label><span>正文模板</span><select v-model="form.documentTemplateId"><option :value="null">空白文档</option><option v-for="template in templates" :key="template.id" :value="template.id">{{ template.name }}</option></select></label>
        <label><span>标题格式</span><input v-model="form.documentTitlePattern" maxlength="128" placeholder="{date} 每日工作记录" /><small>支持 {date} 和 {weekday}</small></label>
      </div></section>

      <section class="items-settings"><header><div><h4><i class="ri-checkbox-multiple-line"></i>每日必做项目</h4><p>系统项保留自动联动行为，但名称、时间、适用日和通知都能调整。</p></div><button type="button" @click="addItem"><i class="ri-add-line"></i>添加项目</button></header>
        <article v-for="(item,index) in items" :key="item.id" class="item-editor" :class="{ inactive: !item.active }">
          <header><span>{{ index + 1 }}</span><input v-model="item.title" maxlength="80" placeholder="必做事项名称" /><em>{{ item.kind === 'CUSTOM' ? '自定义' : item.kind === 'PLAN' ? '计划联动' : item.kind === 'TIANREN' ? '卦象联动' : '手动确认' }}</em><label><input v-model="item.active" type="checkbox" />启用</label><button type="button" title="删除或停用" @click="removeItem(item)"><i class="ri-delete-bin-line"></i></button></header>
          <div class="item-fields">
            <label><span>分类</span><select v-model="item.category"><option value="WORK">工作</option><option value="LIFE">生活</option></select></label>
            <label><span>适用日</span><select v-model="item.scheduleType"><option value="WORKDAY">工作日</option><option value="DAILY">每天</option><option value="WEEKDAYS">自选星期</option></select></label>
            <label><span>开始</span><input v-model="item.startTime" type="time" /></label>
            <label><span>截止</span><input v-model="item.dueTime" type="time" /></label>
            <label class="inline"><input v-model="item.dueNextDay" type="checkbox" />截止在次日</label>
          </div>
          <div v-if="item.scheduleType === 'WEEKDAYS'" class="weekday-row"><button v-for="(label,day) in weekdays" :key="day" type="button" :class="{ active: item.weekdays.includes(day+1) }" @click="toggleWeekday(item,day+1)">周{{ label }}</button></div>
          <div class="notify-row"><b>未完成提醒</b><label><input v-model="item.notifyDirect" type="checkbox" />飞书私聊</label><label><input v-model="item.notifyChat" type="checkbox" />飞书群</label><label><input v-model="item.notifyWebhook" type="checkbox" />Webhook</label><label><span>提醒时间</span><input v-model="item.reminderTime" type="time" :disabled="!item.notifyDirect&&!item.notifyChat&&!item.notifyWebhook" /></label><label class="inline"><input v-model="item.reminderNextDay" type="checkbox" />次日提醒</label></div>
          <div v-if="item.notifyChat || item.notifyWebhook" class="target-row"><label v-if="item.notifyChat"><span>通知群聊</span><select v-model="item.chatId" @change="selectChat(item)"><option :value="null">请选择群聊</option><option v-for="chat in chats" :key="chat.chatId" :value="chat.chatId">{{ chat.name }}</option></select></label><label v-if="item.notifyWebhook"><span>Webhook</span><select v-model="item.webhookId" @change="selectWebhook(item)"><option :value="null">请选择配置</option><option v-for="webhook in webhooks.filter(entry=>entry.enabled)" :key="webhook.id" :value="webhook.id">{{ webhook.name }}</option></select></label></div>
        </article>
      </section>
      <p v-if="error" class="save-error" role="alert"><i class="ri-error-warning-line"></i>{{ error }}</p>
      <footer><span v-if="saved"><i class="ri-check-line"></i>每日面板设置已保存</span><button class="primary" type="button" :disabled="saving" @click="submit">{{ saving ? '保存中…' : '保存每日面板' }}</button></footer>
    </template>
  </section>
</template>

<style scoped>
.daily-settings{padding-bottom:20px;margin-bottom:20px;border-bottom:1px solid var(--wao-divider)}.daily-settings>header,.items-settings>header{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.daily-settings h3,.daily-settings h4{margin:3px 0}.daily-settings p{margin:4px 0;color:var(--wao-text-weak);font-size:10.5px}.daily-settings>header small{font-size:9px;letter-spacing:.16em;color:var(--wao-primary)}.daily-settings button{border:0;background:none;color:var(--wao-primary);cursor:pointer}.settings-state{min-height:100px;display:flex;justify-content:center;align-items:center;gap:8px;color:var(--wao-text-weak)}.settings-state.error,.save-error{color:var(--wao-danger-strong)}
.setting-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:14px 0}.switch-row{display:flex;gap:8px;padding:10px;border:1px solid var(--wao-line);border-radius:10px}.switch-row span{display:grid;gap:3px}.switch-row b{font-size:11px}.switch-row small{font-size:9px;color:var(--wao-text-weak)}.document-settings,.items-settings{margin-top:16px}.document-settings h4,.items-settings h4{font-size:12px}.document-grid{display:grid;grid-template-columns:1fr 1fr;gap:9px}.document-grid label,.item-fields label,.target-row label{display:grid;gap:4px}.document-grid span,.item-fields span,.target-row span,.notify-row>label>span{font-size:9.5px;color:var(--wao-text-weak)}.document-grid input,.document-grid select,.item-editor input,.item-editor select{min-width:0;height:34px;box-sizing:border-box;border:1px solid var(--wao-line-control);border-radius:8px;padding:0 8px;background:var(--wao-bg-card);color:var(--wao-text)}.document-grid label>small{font-size:8.5px;color:var(--wao-text-faint)}
.item-editor{margin-top:9px;padding:11px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-sub)}.item-editor.inactive{opacity:.65}.item-editor>header{display:grid;grid-template-columns:22px minmax(0,1fr) auto auto 25px;gap:7px;align-items:center}.item-editor>header>span{font-size:10px;color:var(--wao-text-faint)}.item-editor>header>em{font-style:normal;font-size:9px;color:var(--wao-primary)}.item-editor>header>label{font-size:9px}.item-editor>header input[type=text]{font-weight:650}.item-fields{display:grid;grid-template-columns:1fr 1fr .8fr .8fr auto;gap:7px;margin-top:9px}.inline{display:flex!important;align-items:end!important;grid-auto-flow:column;justify-content:start;font-size:9.5px}.inline input,.item-editor input[type=checkbox]{width:auto;height:auto}.weekday-row{display:flex;gap:5px;margin-top:8px}.weekday-row button{padding:5px 8px;border:1px solid var(--wao-line);border-radius:7px;color:var(--wao-text-weak)}.weekday-row button.active{border-color:var(--wao-primary);background:var(--wao-primary-soft);color:var(--wao-primary)}.notify-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:9px;padding-top:8px;border-top:1px dashed var(--wao-line)}.notify-row b{font-size:10px}.notify-row label{display:flex;align-items:center;gap:4px;font-size:9.5px}.target-row{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px}.daily-settings>footer{display:flex;justify-content:flex-end;align-items:center;gap:15px;margin-top:14px;font-size:10px;color:#267c65}.daily-settings>footer .primary{padding:8px 15px;border-radius:8px;background:var(--wao-primary);color:#fff}.save-error{font-size:10px}
@media(max-width:700px){.setting-grid,.document-grid,.target-row{grid-template-columns:1fr}.item-fields{grid-template-columns:1fr 1fr}.item-editor>header{grid-template-columns:20px minmax(0,1fr) auto}.item-editor>header>em,.item-editor>header>label{display:none}.weekday-row{flex-wrap:wrap}}
</style>
