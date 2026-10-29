<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoutinesStore } from '@/stores/routines'
import { convertCalendarDate } from '@/api/importantDates'
import type { RoutineKind } from '@/api/personalRoutines'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoField from '@/components/ui/WaoField.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
const props=defineProps<{kind:RoutineKind}>(),emit=defineEmits<{close:[]}>(),store=useRoutinesStore()
const initial=store.drafts[props.kind]!
const draft=computed(()=>store.drafts[props.kind]??initial), localError=ref(''), lunar=ref(''),reminder=ref('')
const remote=computed(()=>store.item(draft.value.id)),conflict=computed(()=>remote.value&&remote.value.revision!==draft.value.revision)
const title=computed(()=>`${draft.value.revision?'编辑':'添加'}${props.kind==='habit'?'习惯':'倒数日'}`)
const icons=['🌱','😊','💧','📚','🏃','🧘','💪','☀️','🌙','🎯','⏳','🎂','🎉','❤️','🏖️','🎓']
let conversion=0
watch(()=>[draft.value.date,draft.value.calendar],async()=>{const n=++conversion;lunar.value='';if(draft.value.calendar!=='lunar'||!draft.value.date)return;try{const v=await convertCalendarDate({calendarType:'SOLAR',solarDate:draft.value.date});if(n===conversion)lunar.value=v.lunarLabel}catch(e:any){if(n===conversion)lunar.value=e.message||'农历换算失败'}},{immediate:true})
function addReminder(){localError.value='';if(props.kind==='habit'){if(!/^\d{2}:\d{2}$/.test(reminder.value))return;draft.value.reminderTimes=[...new Set([...draft.value.reminderTimes,reminder.value])].sort()}else{const n=Number(reminder.value);if(!Number.isInteger(n)||n<0||n>365){localError.value='提前天数应为 0 至 365';return}draft.value.remindBefore=[...new Set([...draft.value.remindBefore,n])].sort((a,b)=>a-b)}reminder.value=''}
async function save(){localError.value='';if(!draft.value.name.trim()||!draft.value.date){localError.value='请填写名称和日期';return}if(await store.save(draft.value,true))emit('close')}
function adoptRemote(){if(remote.value)store.edit(remote.value)}
</script>
<template>
 <SettingsDialogShell :title="title" width="720px" @close="!store.busy&&emit('close')">
  <form class="routine-form" :inert="store.busy" @submit.prevent="save">
   <p v-if="localError||store.error||store.draftError" role="alert" class="error">{{localError||store.error||store.draftError}}</p>
   <p class="hint">关闭后保留本机草稿。提醒仅发到你自己的 WAO 通知中心。</p>
   <div v-if="conflict" class="conflict" role="alert"><p>云端记录已更新：{{remote?.name}} · {{remote?.date}} · {{remote?.note||'无备注'}}。本机草稿未覆盖。</p><WaoButton @click="adoptRemote">使用云端内容</WaoButton><WaoButton @click="draft.revision=remote!.revision">保留本机内容，采用最新版本</WaoButton></div>
   <WaoField label="名称" required v-slot="f"><input :id="f.id" v-model="draft.name" maxlength="120" autocomplete="off" /></WaoField>
   <WaoField label="图标或短文字" v-slot="f"><input :id="f.id" v-model="draft.emoji" maxlength="16" /><div class="icons"><button v-for="icon in icons" :key="icon" type="button" :aria-label="`使用图标 ${icon}`" :aria-pressed="draft.emoji===icon" @click="draft.emoji=icon">{{icon}}</button></div></WaoField>
   <div class="pair">
    <WaoField :label="kind==='habit'?'开始日期':'日期'" :hint="draft.calendar==='lunar'?'选择对应公历日期，按换算后的农历月日重复。'+lunar:''" v-slot="f"><input :id="f.id" v-model="draft.date" type="date" :min="kind==='habit'?'2000-01-01':'1900-01-01'" max="2100-12-31" /></WaoField>
    <WaoField v-if="kind==='countdown'" label="历法" v-slot="f"><select :id="f.id" v-model="draft.calendar" @change="draft.calendar==='lunar'&&(draft.repeat='yearly')"><option value="solar">公历</option><option value="lunar">农历</option></select></WaoField>
    <WaoField v-else label="坚持天数" hint="0 表示长期坚持" v-slot="f"><input :id="f.id" v-model.number="draft.durationDays" type="number" min="0" max="3650" list="habit-duration" /><datalist id="habit-duration"><option v-for="n in [0,7,21,30,100,365]" :key="n" :value="n" /></datalist></WaoField>
   </div>
   <template v-if="kind==='countdown'">
    <div class="pair"><WaoField label="类型" v-slot="f"><select :id="f.id" v-model="draft.category"><option value="countdown">倒数日</option><option value="anniversary">纪念日</option><option value="birthday">生日</option><option value="holiday">节日</option></select></WaoField><WaoField label="重复" v-slot="f"><select :id="f.id" v-model="draft.repeat"><option value="none">不重复</option><option v-if="draft.calendar==='solar'" value="weekly">每周</option><option v-if="draft.calendar==='solar'" value="monthly">每月</option><option value="yearly">每年</option></select></WaoField></div>
    <WaoField label="计时方式" v-slot="f"><select :id="f.id" v-model="draft.direction"><option value="down">倒计时 · 距离下次日期</option><option value="up">正计时 · 从原日期开始</option></select></WaoField>
    <label class="check"><input v-model="draft.showAge" type="checkbox" />显示周岁（按公历）</label><label class="check"><input v-model="draft.showToday" type="checkbox" />当天在待办「今天」中显示</label>
   </template>
   <template v-else>
    <WaoField label="频率" v-slot="f"><select :id="f.id" v-model="draft.frequency"><option value="daily">按天 · 指定星期</option><option value="weekly">每周完成若干天</option><option value="interval">每隔若干天</option></select></WaoField>
    <fieldset v-if="draft.frequency==='daily'"><legend>计划星期</legend><label v-for="(day,index) in ['一','二','三','四','五','六','日']" :key="day" class="check"><input v-model="draft.weekdays" type="checkbox" :value="index+1" />周{{day}}</label></fieldset>
    <WaoField v-if="draft.frequency==='weekly'" label="每周目标天数" v-slot="f"><input :id="f.id" v-model.number="draft.weeklyTarget" type="number" min="1" max="7" /></WaoField>
    <WaoField v-if="draft.frequency==='interval'" label="间隔天数" v-slot="f"><input :id="f.id" v-model.number="draft.intervalDays" type="number" min="1" max="365" /></WaoField>
    <div class="pair"><WaoField label="每日目标数量" hint="一次打卡填 1；也可记录杯数、分钟等" v-slot="f"><input :id="f.id" v-model.number="draft.target" type="number" min="1" max="1000000" /></WaoField><WaoField label="单位" v-slot="f"><input :id="f.id" v-model="draft.unit" maxlength="16" /></WaoField></div>
    <WaoField label="所属分组" v-slot="f"><input :id="f.id" v-model="draft.group" list="habit-groups" maxlength="40" /><datalist id="habit-groups"><option v-for="g in [...new Set(['上午','下午','晚上','其他',...(store.state?.items.filter(i=>i.kind==='habit').map(i=>i.group)||[])])]" :key="g" :value="g" /></datalist></WaoField>
    <label class="check"><input v-model="draft.autoLog" type="checkbox" />打卡时打开日志输入</label>
   </template>
   <fieldset><legend>提醒（最多 5 个，留空不提醒）</legend><div class="reminders"><WaoField :label="kind==='habit'?'提醒时间':'提前天数（0 为当天）'" v-slot="f"><input :id="f.id" v-model="reminder" :type="kind==='habit'?'time':'number'" min="0" max="365" /></WaoField><WaoButton :disabled="(kind==='habit'?draft.reminderTimes.length:draft.remindBefore.length)>=5" @click="addReminder">添加提醒</WaoButton></div><div class="reminder-chips"><WaoButton v-for="value in (kind==='habit'?draft.reminderTimes:draft.remindBefore)" :key="value" variant="ghost" @click="kind==='habit'?draft.reminderTimes=draft.reminderTimes.filter(v=>v!==value):draft.remindBefore=draft.remindBefore.filter(v=>v!==value)">{{kind==='habit'?value:`提前 ${value} 天`}} ×</WaoButton><span v-if="!(kind==='habit'?draft.reminderTimes.length:draft.remindBefore.length)">不提醒</span></div><WaoField v-if="kind==='countdown'&&draft.remindBefore.length" label="提醒时刻（上海时间）" v-slot="f"><input :id="f.id" v-model="draft.reminderTime" type="time" /></WaoField></fieldset>
   <div class="pair"><WaoField label="颜色" v-slot="f"><select :id="f.id" v-model="draft.color"><option value="primary">蓝</option><option value="green">绿</option><option value="orange">金</option><option value="pink">粉</option><option value="purple">紫</option></select></WaoField><WaoField label="卡片样式" v-slot="f"><select :id="f.id" v-model="draft.style"><option value="plain">简洁</option><option value="soft">浅色背景</option><option value="outline">彩色边框</option></select></WaoField></div>
   <WaoField label="备注" v-slot="f"><textarea :id="f.id" v-model="draft.note" rows="3" maxlength="5000" /></WaoField>
  </form>
  <template #footer><WaoButton :disabled="store.busy" @click="emit('close')">关闭并保留草稿</WaoButton><WaoButton variant="primary" :loading="store.busy" :disabled="!!conflict||!!store.draftError" @click="save">保存</WaoButton></template>
 </SettingsDialogShell>
</template>
<style scoped>
.routine-form { display:grid;gap:var(--wao-space-2);padding:var(--wao-space-3);color:var(--wao-text); }
.pair { display:grid;grid-template-columns:1fr 1fr;gap:var(--wao-space-2); }.icons,.reminder-chips {display:flex;flex-wrap:wrap;gap:var(--wao-space-1);}.icons button {font-size:24px;background:var(--wao-bg-sub);border:1px solid var(--wao-line);border-radius:var(--wao-radius-control);min-width:44px;min-height:44px;cursor:pointer;}.icons button[aria-pressed=true] {border-color:var(--wao-primary);}
fieldset {border:1px solid var(--wao-line);border-radius:var(--wao-radius-control);padding:var(--wao-space-2);min-width:0;}legend {color:var(--wao-text-2);}.check {display:inline-flex;align-items:center;gap:var(--wao-space-1);min-height:44px;margin-right:var(--wao-space-1);}.check input {width:18px;height:18px;}.reminders {display:flex;align-items:end;gap:var(--wao-space-1);}.hint,.reminder-chips {font-size:13px;color:var(--wao-text-3);}.error {color:var(--wao-danger-strong);}.conflict {padding:var(--wao-space-2);background:var(--wao-warning-bg);border-radius:var(--wao-radius-control);}
@media(max-width:600px){.routine-form{padding:var(--wao-space-2);}.pair{grid-template-columns:1fr;}.routine-form :deep(input),.routine-form :deep(select),.routine-form :deep(textarea){font-size:16px;}}
</style>
