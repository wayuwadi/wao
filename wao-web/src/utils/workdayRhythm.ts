export interface WorkdayRhythmBlock {
  id: string
  start: string
  end: string
  title: string
  guidance: string
}

export interface WorkdayRhythmConfig {
  version: 1
  blocks: WorkdayRhythmBlock[]
  energyNotification: WorkdayRhythmEnergyNotification
}

export interface WorkdayRhythmEnergyNotification {
  enabled: boolean
  chatId: string
  chatName: string
  notifyWater: boolean
  notifyMeditation: boolean
}

export interface WorkdayRhythmDay {
  version: 1
  date: string
  workdayActive: boolean
  completed: string[]
  waterCount: number
  meditated: boolean
  focus: string[]
}

export interface ShanghaiClock {
  date: string
  weekday: number
  hours: number
  minutes: number
}

export const DEFAULT_WORKDAY_RHYTHM: WorkdayRhythmConfig = {
  version: 1,
  energyNotification: {
    enabled: false,
    chatId: '',
    chatName: '',
    notifyWater: true,
    notifyMeditation: true,
  },
  blocks: [
    {
      id: 'morning',
      start: '07:00',
      end: '09:00',
      title: '起床与通勤',
      guidance: '从容出发，路上自由安排；给自己和身边的人传递积极能量。',
    },
    {
      id: 'deep-work',
      start: '09:00',
      end: '12:00',
      title: '重点攻坚',
      guidance: '列好今日目标，把精力优先投给最重要、最困难的事项。',
    },
    {
      id: 'lunch-rest',
      start: '12:00',
      end: '13:00',
      title: '午饭与午休',
      guidance: '12:00 吃饭，12:30 回来休息，13:00 准时起床。',
    },
    {
      id: 'afternoon',
      start: '13:00',
      end: '18:00',
      title: '下午推进',
      guidance: '持续推进工作，记得喝水、走动，并至少留一次短暂冥想。',
    },
    {
      id: 'learning',
      start: '18:00',
      end: '20:00',
      title: '自主学习',
      guidance: '把这两个小时留给自己的长期成长和能力积累。',
    },
    {
      id: 'recovery',
      start: '20:00',
      end: '23:00',
      title: '生活与恢复',
      guidance: '放松、休息、回收能量，不把负面情绪传给别人。',
    },
    {
      id: 'sleep',
      start: '23:00',
      end: '07:00',
      title: '睡眠充能',
      guidance: '停止消耗，保证完整睡眠，为下一个工作日补充能量。',
    },
  ],
}

const TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/

export function cloneRhythmConfig(value: WorkdayRhythmConfig): WorkdayRhythmConfig {
  return JSON.parse(JSON.stringify(value)) as WorkdayRhythmConfig
}

export function parseRhythmConfig(raw: string): WorkdayRhythmConfig | null {
  if (!raw) return null
  try {
    const candidate = JSON.parse(raw) as WorkdayRhythmConfig
    return rhythmConfigError(candidate) ? null : normalizeRhythmConfig(candidate)
  } catch {
    return null
  }
}

export function rhythmConfigError(value: WorkdayRhythmConfig): string {
  if (
    value?.version !== 1 ||
    !Array.isArray(value.blocks) ||
    value.blocks.length < 2 ||
    value.blocks.length > 10
  ) {
    return '节奏模板需要保留 2 到 10 个时间块'
  }
  const ids = new Set<string>()
  for (const block of value.blocks) {
    if (!block || typeof block.id !== 'string' || !block.id || ids.has(block.id))
      return '时间块标识不正确'
    ids.add(block.id)
    if (
      !TIME_PATTERN.test(block.start) ||
      !TIME_PATTERN.test(block.end) ||
      block.start === block.end
    )
      return '请填写有效且不同的开始、结束时间'
    if (!block.title?.trim() || block.title.trim().length > 30)
      return '每个时间块都要有不超过 30 字的名称'
    if (!block.guidance?.trim() || block.guidance.trim().length > 120)
      return '每个时间块都要有不超过 120 字的行动提示'
  }
  for (let index = 0; index < value.blocks.length; index += 1) {
    const current = value.blocks[index]!
    const next = value.blocks[(index + 1) % value.blocks.length]!
    if (current.end !== next.start) return '相邻时间块必须首尾相接，完整覆盖一天'
  }
  const totalMinutes = value.blocks.reduce((sum, block) => {
    const start = minutesOf(block.start)
    const end = minutesOf(block.end)
    return sum + (end > start ? end - start : end + 24 * 60 - start)
  }, 0)
  if (totalMinutes !== 24 * 60) return '时间块必须完整覆盖一天且不能相互重叠'
  const notification = value.energyNotification
  if (notification !== undefined) {
    if (
      typeof notification.enabled !== 'boolean' ||
      typeof notification.notifyWater !== 'boolean' ||
      typeof notification.notifyMeditation !== 'boolean' ||
      typeof notification.chatId !== 'string' ||
      notification.chatId.length > 128 ||
      typeof notification.chatName !== 'string' ||
      notification.chatName.length > 100
    ) {
      return '精力补给通知配置不正确'
    }
    if (notification.enabled && !notification.chatId.trim()) return '请选择精力补给通知群'
    if (notification.enabled && !notification.notifyWater && !notification.notifyMeditation)
      return '请至少选择一种需要通知的精力补给动作'
  }
  return ''
}

export function normalizeRhythmConfig(value: WorkdayRhythmConfig): WorkdayRhythmConfig {
  const notification = value.energyNotification ?? DEFAULT_WORKDAY_RHYTHM.energyNotification
  return {
    version: 1,
    energyNotification: {
      enabled: notification.enabled,
      chatId: notification.chatId.trim(),
      chatName: notification.chatName.trim(),
      notifyWater: notification.notifyWater,
      notifyMeditation: notification.notifyMeditation,
    },
    blocks: value.blocks.map((block) => ({
      id: block.id,
      start: block.start,
      end: block.end,
      title: block.title.trim(),
      guidance: block.guidance.trim(),
    })),
  }
}

export function defaultRhythmDay(date: string, weekday: number): WorkdayRhythmDay {
  return {
    version: 1,
    date,
    workdayActive: weekday >= 1 && weekday <= 5,
    completed: [],
    waterCount: 0,
    meditated: false,
    focus: ['', '', ''],
  }
}

export function parseRhythmDay(
  raw: string,
  date: string,
  weekday: number,
  validIds: string[],
): WorkdayRhythmDay {
  const fallback = defaultRhythmDay(date, weekday)
  if (!raw) return fallback
  try {
    const candidate = JSON.parse(raw) as Partial<WorkdayRhythmDay>
    if (candidate.version !== 1 || candidate.date !== date) return fallback
    const allowed = new Set(validIds)
    const completed = Array.isArray(candidate.completed)
      ? [
          ...new Set(
            candidate.completed.filter(
              (id): id is string => typeof id === 'string' && allowed.has(id),
            ),
          ),
        ]
      : []
    const focus = Array.isArray(candidate.focus)
      ? [0, 1, 2].map((index) =>
          typeof candidate.focus?.[index] === 'string' ? candidate.focus[index]!.slice(0, 100) : '',
        )
      : fallback.focus
    return {
      version: 1,
      date,
      workdayActive:
        typeof candidate.workdayActive === 'boolean'
          ? candidate.workdayActive
          : fallback.workdayActive,
      completed,
      waterCount: Number.isInteger(candidate.waterCount)
        ? Math.max(0, Math.min(99, candidate.waterCount!))
        : 0,
      meditated: candidate.meditated === true,
      focus,
    }
  } catch {
    return fallback
  }
}

export function shanghaiClock(now = new Date()): ShanghaiClock {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    weekday: 'short',
  }).formatToParts(now)
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ''
  const weekdays: Record<string, number> = {
    Mon: 1,
    Tue: 2,
    Wed: 3,
    Thu: 4,
    Fri: 5,
    Sat: 6,
    Sun: 7,
  }
  return {
    date: `${get('year')}-${get('month')}-${get('day')}`,
    weekday: weekdays[get('weekday')] ?? 1,
    hours: Number(get('hour')),
    minutes: Number(get('minute')),
  }
}

export function minutesOf(value: string): number {
  const [hours, minutes] = value.split(':').map(Number)
  return (hours ?? 0) * 60 + (minutes ?? 0)
}

export function currentBlockIndex(blocks: WorkdayRhythmBlock[], clock: ShanghaiClock): number {
  const now = clock.hours * 60 + clock.minutes
  return blocks.findIndex((block) => {
    const start = minutesOf(block.start)
    const end = minutesOf(block.end)
    return start < end ? now >= start && now < end : now >= start || now < end
  })
}

export function blockProgress(block: WorkdayRhythmBlock, clock: ShanghaiClock): number {
  const now = clock.hours * 60 + clock.minutes
  const start = minutesOf(block.start)
  let end = minutesOf(block.end)
  let cursor = now
  if (end <= start) {
    end += 24 * 60
    if (cursor < start) cursor += 24 * 60
  }
  return Math.max(0, Math.min(100, Math.round(((cursor - start) / (end - start)) * 100)))
}

export function minutesUntilEnd(block: WorkdayRhythmBlock, clock: ShanghaiClock): number {
  const now = clock.hours * 60 + clock.minutes
  const start = minutesOf(block.start)
  let end = minutesOf(block.end)
  if (end <= start && now >= start) end += 24 * 60
  return Math.max(0, end - now)
}
