<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import AttendanceTrend from './AttendanceTrend.vue'
import { leaveDescription } from '@/utils/attendancePresentation'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'
import type { HolidayEvent } from '@/api/holiday'
import { clearAttendanceDay, fetchAttendance, saveAttendanceConfig, saveAttendanceDay, syncAttendance,
  type AttendanceDay, type AttendanceDayType, type AttendanceView } from '@/api/attendance'

const props=defineProps<{active:boolean;from:string;to:string;selectedDate:string|null;holidays?:HolidayEvent[];holidayState?:'loading'|'ready'|'error'}>()
const emit=defineEmits<{(e:'loaded',days:AttendanceDay[]):void;(e:'close-day'):void}>()
const data=useDataStore();const session=useSessionStore();const router=useRouter()
const view=ref<AttendanceView|null>(null);const loading=ref(false);const syncing=ref(false);const error=ref('')
const showConfig=ref(false);const savingConfig=ref(false);const configError=ref('')
const configDraft=reactive({enabled:true,username:'',vaultItemId:''})
const editing=ref<AttendanceDay|null>(null);const savingDay=ref(false);const dayError=ref('')
const dayDraft=reactive<{clockInTime:string;clockOutTime:string;clockOutNextDay:boolean;dayType:AttendanceDayType}>({clockInTime:'',clockOutTime:'',clockOutNextDay:false,dayType:'WORKDAY'})
const vaultItems=computed(()=>data.vault.flatMap(group=>group.items).filter(item=>item.hasSecret))
const canSync=computed(()=>props.active&&Boolean(view.value?.config.enabled)&&view.value?.config.status!=='SYNCING'&&!loading.value&&!syncing.value)
const selectedVaultItem=computed(()=>vaultItems.value.find(item=>item.id===configDraft.vaultItemId)??null)
const configDraftKey=computed(()=>`wao-attendance-config-draft:${session.currentUser.id}`)
const dayDraftKey=computed(()=>editing.value?`wao-attendance-day-draft:${session.currentUser.id}:${editing.value.date}`:'')
const DAILY_TARGET_HOURS=10
const REQUIRED_CLOCK_OUT_MINUTES=19*60+30

function timeMinutes(value:string|null|undefined){if(!value)return null;const [hourText,minuteText]=value.slice(11,16).split(':');const hour=Number(hourText);const minute=Number(minuteText);return Number.isFinite(hour)&&Number.isFinite(minute)?hour*60+minute:null}
function formatTargetClockOut(minutes:number){const nextDay=minutes>=24*60;const normalized=minutes%(24*60);return `${nextDay?'次日 ':''}${String(Math.floor(normalized/60)).padStart(2,'0')}:${String(normalized%60).padStart(2,'0')}`}
const attendanceGoals=computed(()=>{
  const current=view.value
  const average=current?.monthDailyAverageHours??0
  // CalendarView supplies the full displayed month, including future scheduled workdays.
  const holidayDays=new Map((props.holidays??[]).filter(day=>day.category==='CHINA'&&day.dayType!=='FESTIVAL').map(day=>[day.date,day.dayType]))
  const isWorkday=(day:AttendanceDay)=>holidayDays.has(day.date)?holidayDays.get(day.date)==='ADJUSTED_WORKDAY':day.expectedWorkday
  const monthWorkdays=(current?.days??[]).filter(isWorkday).length
  const earlyDepartureAllowance=Math.floor(monthWorkdays/5)
  const earlyDepartureDays=(current?.days??[]).filter(day=>{
    if(!isWorkday(day)||day.dayType==='LEAVE'||day.workHours===null||!day.clockOutAt)return false
    const clockOut=timeMinutes(day.clockOutAt)
    return day.clockOutAt.slice(0,10)===day.date&&clockOut!==null&&clockOut<REQUIRED_CLOCK_OUT_MINUTES
  }).length
  const today=(current?.days??[]).find(day=>day.date===data.today)
  const todayClockIn=timeMinutes(today?.clockInAt)
  const todayClockOut=timeMinutes(today?.clockOutAt)
  const todayClockOutNextDay=Boolean(today?.clockOutAt&&today.clockOutAt.slice(0,10)!==today.date)
  let todayValue='等待上班卡'
  let todayHint='上班打卡后自动计算最早目标下班时间'
  let todayState:'pending'|'achieved'|'warn'='pending'
  if(today?.dayType==='LEAVE'){
    todayValue='今日请假';todayHint='请假不参与日均，具体时段见日期详情'
  }else if(today?.clockOutAt&&todayClockOut!==null){
    todayValue=today.clockOutAt.slice(11,16)
    todayState=todayClockOutNextDay||todayClockOut>=REQUIRED_CLOCK_OUT_MINUTES?'achieved':'warn'
    todayHint=todayState==='achieved'?'已满足 19:30 最低下班要求':'本日已使用一次 19:30 前下班例外'
  }else if(todayClockIn!==null){
    const target=Math.max(todayClockIn+(DAILY_TARGET_HOURS+1)*60,REQUIRED_CLOCK_OUT_MINUTES)
    todayValue=`最早 ${formatTargetClockOut(target)}`
    todayHint='按 10h 工时 + 1h 午休计算，且不早于 19:30'
  }
  return {
    average,
    averageGap:Math.abs(average-DAILY_TARGET_HOURS),
    averageAchieved:average>=DAILY_TARGET_HOURS,
    monthWorkdays,earlyDepartureAllowance,earlyDepartureDays,
    earlyDepartureRemaining:Math.max(0,earlyDepartureAllowance-earlyDepartureDays),
    earlyDepartureExceeded:earlyDepartureDays>earlyDepartureAllowance,
    todayValue,todayHint,todayState,
  }
})

async function load(){if(!props.active)return;loading.value=true;error.value='';try{view.value=await fetchAttendance(props.from,props.to);emit('loaded',view.value.config.status==='UNCONFIGURED'?view.value.days.filter(day=>day.manualOverride):view.value.days)}catch(e){view.value=null;emit('loaded',[]);error.value=e instanceof Error?e.message:'读取考勤失败'}finally{loading.value=false}}
function localIso(value:Date){return `${value.getFullYear()}-${String(value.getMonth()+1).padStart(2,'0')}-${String(value.getDate()).padStart(2,'0')}`}
async function sync() {
  if (!canSync.value) return
  const needsConfirmation = view.value?.config.status === 'NEEDS_CONFIRMATION'
    || (view.value?.config.status === 'NEEDS_LOGIN' && Boolean(view.value.config.lastError?.includes('其他会话')))
  if (needsConfirmation && !window.confirm('Moka 检测到其他登录会话。确认由 WAO 重新连接并同步考勤？这可能使其他设备上的 Moka 登录失效。本次确认不会用于以后的自动登录。')) return
  syncing.value = true
  error.value = ''
  try {
    const start = new Date(`${props.from}T00:00:00`)
    const end = new Date(`${props.to}T00:00:00`)
    let from = props.from
    let to = props.to
    if ((end.getTime() - start.getTime()) / 86400000 > 62) {
      const pivot = new Date()
      const year = start.getFullYear()
      const month = year === pivot.getFullYear() ? pivot.getMonth() : 0
      from = localIso(new Date(year, month, 1))
      to = localIso(new Date(year, month + 1, 0))
    }
    await syncAttendance(from, to, needsConfirmation)
    await load()
    toast(`Moka 考勤已同步${from === props.from ? '' : '（当前月）'}`, 'ri-refresh-line')
  } catch (e) {
    // 刷新后端的登录状态，让下一次手动重试能展示确认；保留本次错误。
    try {
      view.value = await fetchAttendance(props.from, props.to)
      emit('loaded', view.value.config.status==='UNCONFIGURED'?view.value.days.filter(day=>day.manualOverride):view.value.days)
    } catch { /* 保留已经显示的数据和原始同步错误 */ }
    error.value = e instanceof Error ? e.message : '同步失败'
    toast(error.value, 'ri-error-warning-line')
  } finally {
    syncing.value = false
  }
}
defineExpose({ sync, syncing, loading, canSync })
function openConfig(){configError.value='';const stored=localStorage.getItem(configDraftKey.value);if(stored){try{Object.assign(configDraft,JSON.parse(stored))}catch{copyConfig()}}else copyConfig();if(view.value?.config.status==='UNCONFIGURED')configDraft.enabled=true;showConfig.value=true}
function copyConfig(){configDraft.enabled=view.value?.config.status==='UNCONFIGURED'||(view.value?.config.enabled??true);configDraft.username=view.value?.config.username||'';configDraft.vaultItemId=view.value?.config.vaultItemId||''}
function fillUsernameFromVault(){configDraft.username=selectedVaultItem.value?.user.trim()||''}
async function submitConfig(){savingConfig.value=true;configError.value='';try{await saveAttendanceConfig({...configDraft,vaultItemId:configDraft.vaultItemId||null});localStorage.removeItem(configDraftKey.value);showConfig.value=false;await load();toast(configDraft.enabled?'Moka 考勤连接已保存':'Moka 自动同步已关闭','ri-shield-keyhole-line')}catch(e){configError.value=e instanceof Error?e.message:'保存失败'}finally{savingConfig.value=false}}
function openDay(day:AttendanceDay){editing.value=day;dayError.value='';const key=`wao-attendance-day-draft:${session.currentUser.id}:${day.date}`;const stored=localStorage.getItem(key);if(stored){try{Object.assign(dayDraft,JSON.parse(stored));return}catch{}}dayDraft.clockInTime=day.clockInAt?.slice(11,16)||'';dayDraft.clockOutTime=day.clockOutAt?.slice(11,16)||'';dayDraft.clockOutNextDay=Boolean(day.clockInAt&&day.clockOutAt&&day.clockOutAt.slice(0,10)!==day.clockInAt.slice(0,10));dayDraft.dayType=day.dayType}
async function submitDay(){if(!editing.value)return;savingDay.value=true;dayError.value='';try{const saved=await saveAttendanceDay(editing.value.date,{...dayDraft});replace(saved);localStorage.removeItem(dayDraftKey.value);editing.value=null;emit('close-day');toast('模拟考勤已保存，工时与每日节奏已重算','ri-calculator-line')}catch(e){dayError.value=e instanceof Error?e.message:'保存失败'}finally{savingDay.value=false}}
async function restoreDay(){if(!editing.value)return;savingDay.value=true;dayError.value='';try{const saved=await clearAttendanceDay(editing.value.date);replace(saved);localStorage.removeItem(dayDraftKey.value);editing.value=null;emit('close-day');toast('已恢复 Moka 原始考勤','ri-refresh-line')}catch(e){dayError.value=e instanceof Error?e.message:'恢复失败'}finally{savingDay.value=false}}
function replace(day:AttendanceDay){if(!view.value)return;const index=view.value.days.findIndex(item=>item.date===day.date);if(index>=0)view.value.days[index]=day;emit('loaded',[...view.value.days]);load()}
function closeDay(){if(savingDay.value)return;editing.value=null;emit('close-day')}
function statusText(){const s=view.value?.config.status;return s==='OK'?'同步正常':s==='READY'?'等待首次同步':s==='SYNCING'?'同步中':s==='NEEDS_CONFIRMATION'?'等待确认连接':s==='NEEDS_LOGIN'?'需要重新登录':s==='ERROR'?'同步异常':s==='DISABLED'?'自动同步已关闭':'尚未配置'}
function historyText(){const config=view.value?.config;if(!config?.enabled)return '';if(config.historyComplete)return `近 12 个月已补齐 · ${config.historyTargetFrom} 起`;if(config.historySyncedFrom)return `历史自动补齐中 · 已回溯至 ${config.historySyncedFrom}，目标 ${config.historyTargetFrom}`;return `历史自动补齐准备中 · 将逐月回溯至 ${config.historyTargetFrom}`}
function dayTypeText(v:AttendanceDayType){return v==='LEAVE'?'请假':v==='REST'?'休息日':'工作日'}
watch(()=>[props.active,props.from,props.to],load)
watch(()=>props.selectedDate,(date)=>{if(!date){editing.value=null;return}const day=view.value?.days.find(item=>item.date===date);if(day)openDay(day)})
watch(configDraft,value=>{if(showConfig.value)localStorage.setItem(configDraftKey.value,JSON.stringify(value))},{deep:true})
watch(()=>configDraft.vaultItemId,fillUsernameFromVault)
watch(vaultItems,()=>{if(configDraft.vaultItemId)fillUsernameFromVault()},{deep:true})
watch(dayDraft,value=>{if(editing.value)localStorage.setItem(dayDraftKey.value,JSON.stringify(value))},{deep:true})
onMounted(()=>{data.reloadVault();load()})
</script>

<template>
  <div v-if="active" class="attendance-panel">
    <div v-if="loading" class="attendance-state"><i class="ri-loader-4-line spinning"></i>正在读取我的考勤…</div>
    <div v-else-if="error" class="attendance-state error"><span>{{ error }}</span><button @click="load">重试读取</button><button @click="openConfig">检查设置</button></div>
    <template v-else-if="view">
      <div class="attendance-metrics">
        <div><small>本月总工时</small><strong class="wao-tabular">{{ view.totalHours.toFixed(2) }}<em> h</em></strong></div>
        <div><small>本月日均工时</small><strong class="wao-tabular">{{ view.monthDailyAverageHours.toFixed(2) }}<em> h</em></strong></div>
        <div><small>有效出勤</small><strong class="wao-tabular">{{ view.validDays }}<em> 天</em></strong></div>
      </div>
      <div class="attendance-goals">
        <div class="attendance-goal" :class="attendanceGoals.averageAchieved?'achieved':'warn'"><span><b>日均 10h 目标</b><em>{{ attendanceGoals.averageAchieved?'已达标':'需提升' }}</em></span><strong class="wao-tabular">{{ attendanceGoals.average.toFixed(2) }} / 10.00 h</strong><small>{{ view.validDays?`${attendanceGoals.averageAchieved?'高于':'距离'}目标 ${attendanceGoals.averageGap.toFixed(2)} h`:'等待完整上下班记录' }}</small></div>
        <div class="attendance-goal" :class="attendanceGoals.todayState"><span><b>今日下班规划</b><em>最低 19:30</em></span><strong class="wao-tabular">{{ attendanceGoals.todayValue }}</strong><small>{{ attendanceGoals.todayHint }}</small></div>
        <div v-if="!holidayState||holidayState==='ready'" class="attendance-goal" :class="attendanceGoals.earlyDepartureExceeded?'danger':attendanceGoals.earlyDepartureDays?'warn':'achieved'"><span><b :title="`本月 ${attendanceGoals.monthWorkdays} 个工作日 × 20%，向下取整；19:30 出勤率至少 80%`">19:30 前下班</b><em>本月可用 {{ attendanceGoals.earlyDepartureAllowance }} 天</em></span><strong class="wao-tabular">{{ attendanceGoals.earlyDepartureDays }} / {{ attendanceGoals.earlyDepartureAllowance }} 天</strong><small>{{ attendanceGoals.earlyDepartureExceeded?`已超出 ${attendanceGoals.earlyDepartureDays-attendanceGoals.earlyDepartureAllowance} 天`:`本月还可使用 ${attendanceGoals.earlyDepartureRemaining} 天` }}</small></div>
        <div v-else class="attendance-goal pending"><span><b>19:30 前下班</b></span><small>{{ holidayState==='error'?'节假日读取失败，暂无法计算额度':'正在读取本月工作日…' }}</small></div>
      </div>
      <div v-if="view.weeks.length" class="attendance-weeks" aria-label="各周平均工时"><b>周均</b><span v-for="(week,index) in view.weeks" :key="week.from" tabindex="0" :title="`第 ${index+1} 周 · ${week.from} 至 ${week.to} · ${week.label} · ${week.validDays} 天有效出勤`" :aria-label="`第 ${index+1} 周，${week.validDays ? week.averageHours.toFixed(2)+' 小时' : '无有效出勤'}`">{{ week.validDays ? week.averageHours.toFixed(2) : '—' }}</span><small>h</small></div>
      <AttendanceTrend :days="view.days" :weeks="view.weeks" />
      <div class="attendance-actions"><span class="status" :class="view.config.status.toLowerCase()"><i></i>{{ statusText() }}<template v-if="view.config.lastSyncedAt"> · {{ view.config.lastSyncedAt.replace('T',' ').slice(0,16) }}</template></span><button @click="openConfig"><i class="ri-settings-3-line"></i>设置</button></div>
      <p v-if="view.config.lastError" class="attendance-error">{{ view.config.lastError }}</p>
      <p v-if="historyText()" class="attendance-history" :class="{ complete:view.config.historyComplete }"><i :class="view.config.historyComplete?'ri-checkbox-circle-line':'ri-history-line'"></i>{{ historyText() }}<small v-if="!view.config.historyComplete">每 15 分钟最多补一个月，失败后从原进度续跑</small></p>
      <p v-if="view.config.status==='UNCONFIGURED'" class="attendance-tip">请先把 Moka 密码维护到 WAO 密码本，再点“设置”选择该条目；密码不会复制或显示在考勤页面。</p>
    </template>
  </div>

  <template v-if="showConfig"><div class="attendance-mask" @click="!savingConfig&&(showConfig=false)"></div><div class="attendance-modal" role="dialog" aria-modal="true"><header><div><b>我的考勤 · Moka 连接</b><small>密码留在密码本，考勤页只读取账号并引用密码条目</small></div><button aria-label="关闭" @click="showConfig=false"><i class="ri-close-line"></i></button></header><form @submit.prevent="submitConfig"><label class="toggle"><span><b>启用 Moka 考勤</b><small>每 15 分钟刷新当天；打开每日节奏时也会刷新过期数据</small></span><input v-model="configDraft.enabled" type="checkbox"></label><fieldset :disabled="!configDraft.enabled"><label><span>密码本条目</span><select v-model="configDraft.vaultItemId" required><option value="">请选择已保存密码的条目</option><option v-for="item in vaultItems" :key="item.id" :value="item.id">{{ item.name }} · {{ item.user||'未写账号' }}</option></select></label><label><span>Moka 登录邮箱</span><input :value="configDraft.username" type="email" autocomplete="username" readonly required><small>自动读取所选密码本条目的账号；需要修改请前往密码本。</small></label><p v-if="configDraft.vaultItemId&&!configDraft.username" class="form-error">所选密码本条目没有账号，请先在密码本补充 Moka 登录邮箱。</p><p v-if="!vaultItems.length" class="hint">密码本里还没有带密码的条目。<button type="button" @click="router.push('/vault')">去密码本创建</button></p></fieldset><p v-if="!configDraft.enabled" class="hint disabled-hint">开启 Moka 考勤后即可选择密码本条目。</p><p class="hint security-hint"><i class="ri-shield-keyhole-line"></i><span>WAO 会加密缓存 Moka 会话；若 Moka 要求验证码或确认挤下线，会停止自动登录并提示你处理。</span></p><p v-if="configError" class="form-error">{{ configError }}，草稿已保留，可直接重试。</p><footer><button type="button" @click="showConfig=false">取消</button><button class="primary" :disabled="savingConfig||(configDraft.enabled&&(!configDraft.vaultItemId||!configDraft.username))">{{ savingConfig?'保存中…':configError?'重试保存':'保存设置' }}</button></footer></form></div></template>

  <template v-if="editing"><div class="attendance-mask" @click="closeDay"></div><div class="attendance-modal day-modal" role="dialog" aria-modal="true"><header><div><b>{{ editing.date }} 考勤</b><small>{{ editing.expectedWorkday?editing.workWeekLabel:'休息日' }} · 当前{{ dayTypeText(editing.dayType) }}</small></div><button aria-label="关闭" @click="closeDay"><i class="ri-close-line"></i></button></header><form @submit.prevent="submitDay"><div class="day-summary"><span>有效工时</span><b class="wao-tabular">{{ editing.workHours===null?'尚不可计算':`${editing.workHours.toFixed(2)} 小时` }}</b><small>下班 − 上班 − 午休 1 小时；请假不参与日均</small></div><div v-if="editing.leaves?.length" class="leave-details"><b>Moka 请假记录</b><p v-for="(leave,index) in editing.leaves" :key="index">{{ leaveDescription(leave) }}</p><small>时长以 Moka 为准；跨日记录展示整段申请时长。</small></div><label><span>日期类型</span><select v-model="dayDraft.dayType"><option value="WORKDAY">工作日</option><option value="LEAVE">请假（排除日均）</option><option value="REST">休息日</option></select></label><div class="time-fields"><label><span>上班打卡</span><input v-model="dayDraft.clockInTime" type="time"></label><label><span>下班打卡</span><input v-model="dayDraft.clockOutTime" type="time"></label></div><label class="next-day"><input v-model="dayDraft.clockOutNextDay" type="checkbox">下班时间在次日</label><p v-if="editing.anomaly" class="attendance-error">{{ editing.anomaly }}</p><p v-if="dayError" class="form-error">{{ dayError }}，草稿已保留，可直接重试。</p><footer><button v-if="editing.manualOverride" type="button" @click="restoreDay">恢复 Moka 原值</button><span class="spacer"></span><button type="button" @click="closeDay">取消</button><button class="primary" :disabled="savingDay">{{ savingDay?'保存中…':dayError?'重试保存':'保存模拟值' }}</button></footer></form></div></template>
</template>

<style scoped>
.attendance-panel{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px 14px;align-items:center;padding:12px 14px;border-bottom:1px solid var(--wao-divider);background:linear-gradient(90deg,var(--wao-primary-tint),var(--wao-bg-card))}.attendance-metrics{grid-column:1;grid-row:1;display:flex;gap:22px}.attendance-metrics div{display:grid;gap:2px}.attendance-metrics small,.attendance-weeks,.status,.hint,.day-summary small,header small{font-size:11px;color:var(--wao-text-weak)}.attendance-metrics strong{font-size:19px;color:var(--wao-text)}.attendance-metrics em{font-size:11px;font-style:normal;color:var(--wao-text-weak)}.attendance-goals{grid-column:1/-1;grid-row:2;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px}.attendance-goal{display:grid;gap:4px;min-width:0;padding:9px 10px;border:1px solid var(--wao-line);border-left:3px solid #9a9da9;border-radius:9px;background:var(--wao-bg-card)}.attendance-goal>span{display:flex;align-items:center;justify-content:space-between;gap:8px}.attendance-goal b{font-size:11.5px}.attendance-goal em,.attendance-goal small{overflow:hidden;color:var(--wao-text-weak);font-size:10.5px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.attendance-goal strong{font-size:15px;color:var(--wao-text)}.attendance-goal.achieved{border-left-color:#2c8a5b}.attendance-goal.warn{border-left-color:#b9821f}.attendance-goal.danger{border-left-color:#d9634c}.attendance-goal.pending{border-left-color:#79808f}.attendance-weeks{grid-column:1/-1;grid-row:3;display:grid;grid-template-columns:repeat(auto-fit,minmax(135px,1fr));gap:6px;min-width:0}.attendance-weeks span{min-width:0;padding:5px 8px;border:1px solid var(--wao-line);border-radius:7px;background:var(--wao-bg-card);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.attendance-actions{grid-column:2;grid-row:1;display:flex;align-items:center;gap:7px}.attendance-actions button,.attendance-state button{border:1px solid var(--wao-line-control);border-radius:7px;background:var(--wao-bg-card);color:var(--wao-text-2);padding:5px 8px;cursor:pointer}.status{white-space:nowrap}.status i{display:inline-block;width:6px;height:6px;margin-right:5px;border-radius:50%;background:#9a9da9}.status.ok i{background:#2c8a5b}.status.error i,.status.needs_login i{background:#d9634c}.attendance-state{padding:12px;color:var(--wao-text-weak)}.attendance-state.error,.attendance-error,.form-error{color:var(--wao-danger)}.attendance-tip,.attendance-error,.attendance-history{grid-column:1/-1;margin:0;font-size:12px}.attendance-history{display:flex;align-items:center;gap:6px;color:var(--wao-text-2)}.attendance-history.complete{color:#2c8a5b}.attendance-history small{margin-left:auto;color:var(--wao-text-weak);font-size:10.5px}.attendance-mask{position:fixed;inset:0;z-index:110;background:rgba(23,25,35,.34)}.attendance-modal{position:fixed;z-index:111;top:50%;left:50%;transform:translate(-50%,-50%);width:min(500px,calc(100vw - 32px));max-height:calc(100vh - 32px);overflow-x:hidden;overflow-y:auto;border-radius:14px;background:var(--wao-bg-page);box-shadow:var(--wao-shadow-modal)}.attendance-modal header{display:flex;align-items:center;justify-content:space-between;padding:16px 20px;border-bottom:1px solid var(--wao-divider)}.attendance-modal header>div{display:grid;min-width:0;gap:3px}.attendance-modal header button{flex:none;border:0;background:transparent;color:var(--wao-text-weak);cursor:pointer}.attendance-modal form{display:grid;min-width:0;gap:14px;padding:18px 20px}.attendance-modal label{display:grid;min-width:0;gap:6px;font-size:12px;color:var(--wao-text-3)}.attendance-modal input,.attendance-modal select{box-sizing:border-box;width:100%;min-width:0;height:36px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);padding:0 10px}.attendance-modal input[readonly]{background:var(--wao-bg-sub);color:var(--wao-text-2)}.attendance-modal fieldset{display:grid;min-width:0;gap:12px;border:0;padding:0;margin:0}.attendance-modal fieldset:disabled{opacity:.55}.attendance-modal .toggle{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:12px;border-radius:10px;background:var(--wao-primary-tint)}.toggle span,.toggle small{display:block;min-width:0}.toggle input,.next-day input{width:18px;min-width:18px;height:18px;padding:0;accent-color:var(--wao-primary)}.disabled-hint{margin:0;color:var(--wao-primary-text)}.security-hint{display:flex;align-items:flex-start;gap:7px;margin:0;line-height:1.5}.security-hint i{margin-top:1px;color:var(--wao-primary-text)}.attendance-modal footer{display:flex;gap:8px;align-items:center;justify-content:flex-end}.attendance-modal footer button,.hint button{border:1px solid var(--wao-line-control);border-radius:8px;padding:7px 12px;background:var(--wao-bg-card);color:var(--wao-text-2);cursor:pointer}.attendance-modal footer button:disabled{cursor:not-allowed;opacity:.55}.attendance-modal footer .primary{background:var(--wao-primary);color:#fff;border-color:var(--wao-primary)}.time-fields{display:grid;grid-template-columns:1fr 1fr;gap:12px}.next-day{display:flex!important;align-items:center;gap:7px}.day-summary{display:grid;gap:3px;padding:12px;border-radius:10px;background:var(--wao-primary-tint)}.day-summary b{font-size:18px}.spacer{flex:1}@media(max-width:900px){.attendance-panel{grid-template-columns:1fr}.attendance-metrics,.attendance-actions,.attendance-goals,.attendance-weeks{grid-column:1;grid-row:auto}.attendance-goals{grid-template-columns:1fr}.attendance-actions{flex-wrap:wrap}.attendance-tip,.attendance-error,.attendance-history{grid-column:auto}.attendance-history{align-items:flex-start;flex-wrap:wrap}.attendance-history small{width:100%;margin-left:22px}.attendance-metrics{justify-content:space-between}.time-fields{grid-template-columns:1fr}}
.attendance-weeks{display:flex;flex-wrap:nowrap;align-items:center;gap:4px;overflow-x:auto}.attendance-weeks>b,.attendance-weeks>small{flex-shrink:0;font-size:11px;font-weight:400}.attendance-weeks span{flex:1 0 auto;border:0;border-radius:0;background:transparent;padding:4px 2px;text-align:center;color:var(--wao-text-2);font-variant-numeric:tabular-nums}.leave-details{padding:var(--wao-space-1);border-radius:var(--wao-radius-control);background:var(--wao-warning-bg);color:var(--wao-warning-strong);font-size:var(--wao-font-help)}.leave-details p{margin:var(--wao-space-1) 0;overflow-wrap:anywhere}.leave-details small{color:var(--wao-text-3)}
</style>
