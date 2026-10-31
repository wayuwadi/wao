import { http, type ApiResponse } from './http'

export type AttendanceDayType = 'WORKDAY' | 'LEAVE' | 'REST'
export interface AttendanceConfig { enabled: boolean; username: string | null; vaultItemId: string | null; status: 'UNCONFIGURED'|'DISABLED'|'READY'|'SYNCING'|'OK'|'NEEDS_LOGIN'|'NEEDS_CONFIRMATION'|'ERROR'; lastSyncedAt: string|null; lastError: string|null; historySyncedFrom:string|null; historyTargetFrom:string; historyComplete:boolean }
export interface AttendanceLeave { title:string; startAt:string; endAt:string; duration:number|null; durationUnit:string|null }
export interface AttendanceDay { date:string; clockInAt:string|null; clockOutAt:string|null; dayType:AttendanceDayType; anomaly:string|null; manualOverride:boolean; workHours:number|null; expectedWorkday:boolean; workWeekLabel:string; leaves?:AttendanceLeave[] }
export interface AttendanceWeek { from:string; to:string; label:string; averageHours:number; validDays:number }
export interface AttendanceView { from:string; to:string; config:AttendanceConfig; days:AttendanceDay[]; totalHours:number; monthDailyAverageHours:number; validDays:number; weeks:AttendanceWeek[] }
export interface AttendanceConfigPayload { enabled:boolean; username:string; vaultItemId:string|null }
export interface AttendanceDayPayload { clockInTime:string; clockOutTime:string; clockOutNextDay:boolean; dayType:AttendanceDayType }

export async function fetchAttendance(from:string,to:string):Promise<AttendanceView>{const res=await http.get<ApiResponse<AttendanceView>>('/api/attendance',{params:{from,to}});return res.data.data}
export async function saveAttendanceConfig(payload:AttendanceConfigPayload):Promise<AttendanceConfig>{const res=await http.put<ApiResponse<AttendanceConfig>>('/api/attendance/config',payload);return res.data.data}
export async function syncAttendance(from:string,to:string,confirmSessionReplacement=false):Promise<AttendanceView>{const res=await http.post<ApiResponse<AttendanceView>>('/api/attendance/sync',undefined,{params:{from,to,confirmSessionReplacement},timeout:120_000});return res.data.data}
export async function saveAttendanceDay(date:string,payload:AttendanceDayPayload):Promise<AttendanceDay>{const res=await http.put<ApiResponse<AttendanceDay>>(`/api/attendance/days/${date}`,payload);return res.data.data}
export async function clearAttendanceDay(date:string):Promise<AttendanceDay>{const res=await http.delete<ApiResponse<AttendanceDay>>(`/api/attendance/days/${date}/manual-override`);return res.data.data}
