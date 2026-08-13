import { fetchPreference, savePreference } from './favorites'
import {
  defaultAlternateWorkWeek,
  normalizeAlternateWorkWeek,
  parseAlternateWorkWeek,
  type AlternateWorkWeekSchedule,
} from '@/utils/workSchedule'

const PREFERENCE_KEY = 'alternate-work-week'

/** 读取当前登录成员的大小周配置；空值或旧的损坏值安全回退为未启用。 */
export async function fetchAlternateWorkWeek(today: string): Promise<AlternateWorkWeekSchedule> {
  const raw = await fetchPreference(PREFERENCE_KEY)
  return parseAlternateWorkWeek(raw) ?? defaultAlternateWorkWeek(today)
}

/** 保存前统一把参照日期归一到周一，保证显示周起始偏好不会改变轮换。 */
export async function saveAlternateWorkWeek(value: AlternateWorkWeekSchedule): Promise<AlternateWorkWeekSchedule> {
  const normalized = normalizeAlternateWorkWeek(value)
  if (!normalized) throw new Error('请选择有效的参照日期和周类型')
  await savePreference(PREFERENCE_KEY, JSON.stringify(normalized))
  return normalized
}
