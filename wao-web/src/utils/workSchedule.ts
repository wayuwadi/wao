export type WorkWeekType = 'big' | 'small'

export interface AlternateWorkWeekSchedule {
  enabled: boolean
  /** 参照周的周一，使用 YYYY-MM-DD，避免显示周起始偏好改变轮换结果。 */
  anchorWeek: string
  anchorType: WorkWeekType
}

export interface WorkWeekInfo {
  type: WorkWeekType
  label: '大周' | '小周'
  restLabel: '周日上班' | '双休'
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/
const DAY_MS = 24 * 60 * 60 * 1000

function epochDay(value: string): number | null {
  if (!ISO_DATE.test(value)) return null
  const [year, month, day] = value.split('-').map(Number)
  const timestamp = Date.UTC(year!, month! - 1, day)
  const date = new Date(timestamp)
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month! - 1 || date.getUTCDate() !== day) return null
  return Math.floor(timestamp / DAY_MS)
}

function fromEpochDay(value: number): string {
  return new Date(value * DAY_MS).toISOString().slice(0, 10)
}

/** 业务周固定按周一到周日计算，不受日历“周一/周日开始”显示偏好影响。 */
export function businessWeekStart(value: string): string | null {
  const day = epochDay(value)
  if (day == null) return null
  const dayOfWeek = new Date(day * DAY_MS).getUTCDay()
  const offset = (dayOfWeek + 6) % 7
  return fromEpochDay(day - offset)
}

export function defaultAlternateWorkWeek(today: string): AlternateWorkWeekSchedule {
  return {
    enabled: false,
    anchorWeek: businessWeekStart(today) ?? today,
    anchorType: 'small',
  }
}

export function parseAlternateWorkWeek(raw: string): AlternateWorkWeekSchedule | null {
  if (!raw) return null
  try {
    const value = JSON.parse(raw) as Partial<AlternateWorkWeekSchedule>
    const anchorWeek = typeof value.anchorWeek === 'string' ? businessWeekStart(value.anchorWeek) : null
    if (typeof value.enabled !== 'boolean' || !anchorWeek || !['big', 'small'].includes(value.anchorType ?? '')) return null
    return { enabled: value.enabled, anchorWeek, anchorType: value.anchorType! }
  } catch {
    return null
  }
}

export function normalizeAlternateWorkWeek(value: AlternateWorkWeekSchedule): AlternateWorkWeekSchedule | null {
  const anchorWeek = businessWeekStart(value.anchorWeek)
  if (!anchorWeek || !['big', 'small'].includes(value.anchorType)) return null
  return { ...value, anchorWeek }
}

/** 根据参照周向前、向后无限交替计算某天所属的大周或小周。 */
export function workWeekForDate(
  value: string,
  schedule: AlternateWorkWeekSchedule,
): WorkWeekInfo | null {
  if (!schedule.enabled) return null
  const targetWeek = businessWeekStart(value)
  const anchorWeek = businessWeekStart(schedule.anchorWeek)
  const targetDay = targetWeek ? epochDay(targetWeek) : null
  const anchorDay = anchorWeek ? epochDay(anchorWeek) : null
  if (targetDay == null || anchorDay == null) return null

  const distance = Math.trunc((targetDay - anchorDay) / 7)
  const alternates = ((distance % 2) + 2) % 2 === 1
  const type: WorkWeekType = alternates
    ? schedule.anchorType === 'big' ? 'small' : 'big'
    : schedule.anchorType
  return type === 'big'
    ? { type, label: '大周', restLabel: '周日上班' }
    : { type, label: '小周', restLabel: '双休' }
}
