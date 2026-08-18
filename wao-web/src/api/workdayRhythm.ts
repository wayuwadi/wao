import { fetchPreference, savePreference } from './favorites'
import { http, type ApiResponse } from './http'
import {
  DEFAULT_WORKDAY_RHYTHM,
  cloneRhythmConfig,
  normalizeRhythmConfig,
  parseRhythmConfig,
  parseRhythmDay,
  rhythmConfigError,
  type WorkdayRhythmConfig,
  type WorkdayRhythmDay,
} from '@/utils/workdayRhythm'

const CONFIG_KEY = 'workday-rhythm-config-v1'
const dayKey = (date: string) => `workday-rhythm-day-${date}`

export async function fetchWorkdayRhythm(): Promise<WorkdayRhythmConfig> {
  const raw = await fetchPreference(CONFIG_KEY)
  return parseRhythmConfig(raw) ?? cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
}

export async function saveWorkdayRhythm(value: WorkdayRhythmConfig): Promise<WorkdayRhythmConfig> {
  const error = rhythmConfigError(value)
  if (error) throw new Error(error)
  const normalized = normalizeRhythmConfig(value)
  await savePreference(CONFIG_KEY, JSON.stringify(normalized))
  return normalized
}

export async function fetchWorkdayRhythmDay(
  date: string,
  weekday: number,
  validIds: string[],
): Promise<WorkdayRhythmDay> {
  const raw = await fetchPreference(dayKey(date))
  return parseRhythmDay(raw, date, weekday, validIds)
}

export async function saveWorkdayRhythmDay(value: WorkdayRhythmDay): Promise<void> {
  await savePreference(dayKey(value.date), JSON.stringify(value))
}

export type WorkdayRhythmEnergyAction = 'WATER' | 'MEDITATION'

export interface EnergyNotificationResult {
  sent: boolean
  chatName: string
}

/** 当天记录成功落库后，由服务端复核本人记录并用服务端飞书凭据向本人配置的群发通知。 */
export async function sendEnergyNotification(
  action: WorkdayRhythmEnergyAction,
  date: string,
): Promise<EnergyNotificationResult> {
  const res = await http.post<ApiResponse<EnergyNotificationResult>>(
    '/api/workday-rhythm/energy-notifications',
    { action, date },
  )
  return res.data.data
}
