import { http, type ApiResponse } from './http'
export type RoutineKind = 'countdown' | 'habit'
export interface RoutineItem {
 id: string; kind: RoutineKind; name: string; date: string; emoji: string; note: string; status: 'active'|'archived'|'trash'; revision: number
 color: string; style: string; pinned: boolean; category: string; calendar: string; repeat: string; direction: string; showAge: boolean; showToday: boolean
 remindBefore: number[]; reminderTime: string; frequency: string; group: string; unit: string; weekdays: number[]; weeklyTarget: number; intervalDays: number; target: number; durationDays: number; autoLog: boolean; reminderTimes: string[]
}
export interface HabitLog { habitId: string; date: string; revision: number; amount: number; target: number; note: string }
export interface RoutineSummary { id:string; occurrence:string|null; days:number|null; lunarLabel:string; age:number|null; total:number; monthCount:number; monthRate:number; streak:number; bestStreak:number; streakUnit:string; weekCount:number; scheduledDates:string[] }
export interface RoutineState { today:string; month:string; items:RoutineItem[]; summaries:RoutineSummary[]; logs:HabitLog[] }
export const fetchRoutines = async (month?:string) => (await http.get<ApiResponse<RoutineState>>('/api/routines'+(month?`?month=${encodeURIComponent(month)}`:''))).data.data
export const saveRoutine = async (value:RoutineItem) => (await http.put<ApiResponse<RoutineItem>>(`/api/routines/${value.id}`,value)).data.data
export const saveHabitLog = async (value:HabitLog) => (await http.put<ApiResponse<HabitLog>>(`/api/routines/${value.habitId}/checkins/${value.date}`,value)).data.data
export function newRoutine(kind:RoutineKind,today:string):RoutineItem { return { id:crypto.randomUUID().replace(/-/g,''),kind,name:'',date:today,emoji:kind==='habit'?'🌱':'⏳',note:'',status:'active',revision:0,color:'primary',style:'plain',pinned:false,category:'countdown',calendar:'solar',repeat:'none',direction:'down',showAge:false,showToday:true,remindBefore:[],reminderTime:'09:00',frequency:'daily',group:'其他',unit:'次',weekdays:[1,2,3,4,5,6,7],weeklyTarget:3,intervalDays:2,target:1,durationDays:0,autoLog:false,reminderTimes:[] } }
export function shiftDay(day:string,n:number) { return new Date(Date.parse(day+'T12:00:00Z')+n*86400000).toISOString().slice(0,10) }
export function habitScheduled(item:RoutineItem,day:string) {
 const offset=Math.round((Date.parse(day+'T12:00:00Z')-Date.parse(item.date+'T12:00:00Z'))/86400000)
 if(offset<0 || item.durationDays>0 && offset>=item.durationDays)return false
 return item.frequency==='weekly' || (item.frequency==='interval'?offset%item.intervalDays===0:item.weekdays.includes(new Date(day+'T12:00:00Z').getUTCDay()||7))
}
export const routineColors:Record<string,string>={primary:'var(--wao-primary)',green:'var(--wao-area-health)',orange:'var(--wao-area-family)',pink:'var(--wao-area-growth)',purple:'var(--wao-area-finance)'}
