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
  energyItems: WorkdayRhythmEnergyItem[]
}

export type WorkdayNotificationKind = 'energy' | 'focus' | 'tianren'

export interface WorkdayNotificationTarget {
  channel: 'WEBHOOK' | 'CHAT'
  webhookId: string
  webhookName: string
  chatId: string
  chatName: string
}

export interface WorkdayRhythmEnergyNotification extends WorkdayNotificationTarget {
  enabled: boolean
  notifyFocus: boolean
  notifyEnergy?: boolean
  /** 缺省时沿用旧版共用目标；首次保存后独立持久化。 */
  focusTarget?: WorkdayNotificationTarget
  tianrenTarget?: WorkdayNotificationTarget
  /** 旧配置默认关闭，开启即允许把当天解读分享到所选群。 */
  notifyTianren?: boolean
  /** 旧版配置字段只用于读取迁移，新版是否通知由每个补给项目决定。 */
  notifyWater?: boolean
  notifyMeditation?: boolean
}

export type WorkdayRhythmEnergyMode = 'COUNT' | 'DAILY_ONCE'

export interface WorkdayRhythmEnergyItem {
  id: string
  title: string
  guidance: string
  mode: WorkdayRhythmEnergyMode
  notify: boolean
}

export interface WorkdayRhythmDay {
  version: 1
  date: string
  workdayActive: boolean
  completed: string[]
  waterCount: number
  meditated: boolean
  energyRecords: Record<string, number>
  energySnapshot: Record<string, Pick<WorkdayRhythmEnergyItem, 'title' | 'mode'>>
  focus: string[]
  focusCompleted: boolean[]
}

export interface ShanghaiClock {
  date: string
  weekday: number
  hours: number
  minutes: number
}

export const DEFAULT_FOCUS_COUNT = 3
export const MAX_FOCUS_COUNT = 20

export const DEFAULT_WORKDAY_RHYTHM: WorkdayRhythmConfig = {
  version: 1,
  energyNotification: {
    enabled: false,
    notifyFocus: true,
    notifyTianren: false,
    channel: 'WEBHOOK',
    webhookId: '',
    webhookName: '',
    chatId: '',
    chatName: '',
  },
  energyItems: [
    {
      id: 'WATER',
      title: '喝水',
      guidance: '今天已真实记录',
      mode: 'COUNT',
      notify: true,
    },
    {
      id: 'MEDITATION',
      title: '至少冥想一次',
      guidance: '累之前，先停下来几分钟',
      mode: 'DAILY_ONCE',
      notify: true,
    },
  ],
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
    const normalized = normalizeRhythmConfig(candidate)
    return rhythmConfigError(normalized) ? null : normalized
  } catch {
    return null
  }
}

/** 设置弹窗恢复未完成草稿时不做业务校验，避免空名称等中间态被当成损坏配置丢弃。 */
export function parseRhythmConfigDraft(raw: string): WorkdayRhythmConfig | null {
  if (!raw) return null
  try {
    return normalizeRhythmConfig(JSON.parse(raw) as WorkdayRhythmConfig)
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
      typeof notification.notifyFocus !== 'boolean' ||
      (notification.notifyEnergy !== undefined && typeof notification.notifyEnergy !== 'boolean') ||
      (notification.notifyTianren !== undefined && typeof notification.notifyTianren !== 'boolean')
    )
      return '今日行动通知配置不正确'
    const routes = [
      [
        'energy',
        '精力补给',
        notification.notifyEnergy !== false && value.energyItems?.some((item) => item.notify),
      ],
      ['focus', '今日重点', notification.notifyFocus],
      ['tianren', '卦象解读', notification.notifyTianren],
    ] as const
    for (const [kind, label, enabled] of routes) {
      const target = workdayNotificationTarget(notification, kind)
      if (
        !target ||
        !['WEBHOOK', 'CHAT'].includes(target.channel) ||
        typeof target.webhookId !== 'string' ||
        target.webhookId.length > 64 ||
        typeof target.webhookName !== 'string' ||
        target.webhookName.length > 64 ||
        typeof target.chatId !== 'string' ||
        target.chatId.length > 128 ||
        typeof target.chatName !== 'string' ||
        target.chatName.length > 100
      )
        return `${label}通知配置不正确`
      if (
        notification.enabled &&
        enabled &&
        !(target.channel === 'WEBHOOK' ? target.webhookId : target.chatId).trim()
      )
        return `请选择${label}通知群`
    }
  }
  if (
    !Array.isArray(value.energyItems) ||
    value.energyItems.length < 1 ||
    value.energyItems.length > 8
  )
    return '精力补给需要保留 1 到 8 个项目'
  const energyIds = new Set<string>()
  for (const item of value.energyItems) {
    if (
      !item ||
      typeof item.id !== 'string' ||
      !/^[A-Za-z0-9_-]{1,40}$/.test(item.id) ||
      energyIds.has(item.id)
    )
      return '精力补给项目标识不正确'
    energyIds.add(item.id)
    if (!item.title?.trim() || item.title.trim().length > 20)
      return '每个精力补给项目都要有不超过 20 字的名称'
    if (!item.guidance?.trim() || item.guidance.trim().length > 60)
      return '每个精力补给项目都要有不超过 60 字的提示'
    if (item.mode !== 'COUNT' && item.mode !== 'DAILY_ONCE') return '请选择正确的点击机制'
    if (typeof item.notify !== 'boolean') return '精力补给项目通知配置不正确'
  }
  if (
    notification.enabled &&
    !notification.notifyFocus &&
    !notification.notifyTianren &&
    (notification.notifyEnergy === false || !value.energyItems.some((item) => item.notify))
  )
    return '请至少选择一个需要通知的今日行动'
  return ''
}

/** 旧成员继续使用原目标；已有独立配置缺失字段时不得回落到另一个群。 */
export function workdayNotificationTarget(
  notification: WorkdayRhythmEnergyNotification,
  kind: WorkdayNotificationKind,
): WorkdayNotificationTarget {
  if (kind === 'focus' && notification.focusTarget !== undefined) return notification.focusTarget
  if (kind === 'tianren' && notification.tianrenTarget !== undefined)
    return notification.tianrenTarget
  return notification
}

function normalizedNotificationTarget(value: WorkdayNotificationTarget): WorkdayNotificationTarget {
  return {
    channel:
      value.channel === undefined ? (value.chatId?.trim() ? 'CHAT' : 'WEBHOOK') : value.channel,
    webhookId: value.webhookId?.trim() ?? '',
    webhookName: value.webhookName?.trim() ?? '',
    chatId: value.chatId?.trim() ?? '',
    chatName: value.chatName?.trim() ?? '',
  }
}

export function normalizeRhythmConfig(value: WorkdayRhythmConfig): WorkdayRhythmConfig {
  const notification = value.energyNotification ?? DEFAULT_WORKDAY_RHYTHM.energyNotification
  const legacyNotification = notification as WorkdayRhythmEnergyNotification
  const target = normalizedNotificationTarget(notification)
  const energyItems = Array.isArray(value.energyItems)
    ? value.energyItems
    : DEFAULT_WORKDAY_RHYTHM.energyItems.map((item) => ({
        ...item,
        notify:
          item.id === 'WATER'
            ? legacyNotification.notifyWater !== false
            : legacyNotification.notifyMeditation !== false,
      }))
  return {
    version: 1,
    energyNotification: {
      enabled: notification.enabled === true,
      notifyFocus: notification.notifyFocus !== false,
      notifyTianren: notification.notifyTianren === true,
      notifyEnergy: notification.notifyEnergy !== false,
      ...target,
      focusTarget: normalizedNotificationTarget(
        notification.focusTarget === undefined ? target : notification.focusTarget,
      ),
      tianrenTarget: normalizedNotificationTarget(
        notification.tianrenTarget === undefined ? target : notification.tianrenTarget,
      ),
      notifyWater: energyItems.find((item) => item.id === 'WATER')?.notify ?? true,
      notifyMeditation: energyItems.find((item) => item.id === 'MEDITATION')?.notify ?? true,
    },
    energyItems: energyItems.map((item) => ({
      id: item.id,
      title: item.title.trim(),
      guidance: item.guidance.trim(),
      mode: item.mode,
      notify: item.notify,
    })),
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
    energyRecords: {},
    energySnapshot: {},
    focus: Array.from({ length: DEFAULT_FOCUS_COUNT }, () => ''),
    focusCompleted: Array.from({ length: DEFAULT_FOCUS_COUNT }, () => false),
  }
}

export function parseRhythmDay(
  raw: string,
  date: string,
  weekday: number,
  validIds: string[],
  validEnergyIds: string[] = DEFAULT_WORKDAY_RHYTHM.energyItems.map((item) => item.id),
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
    const storedFocus = Array.isArray(candidate.focus)
      ? candidate.focus
          .slice(0, MAX_FOCUS_COUNT)
          .map((item) => (typeof item === 'string' ? item.slice(0, 100) : ''))
      : []
    const focus = [
      ...storedFocus,
      ...Array.from({ length: Math.max(0, DEFAULT_FOCUS_COUNT - storedFocus.length) }, () => ''),
    ]
    const focusCompleted = focus.map(
      (_, index) => candidate.focusCompleted?.[index] === true && !!focus[index]?.trim(),
    )
    const energyRecords: Record<string, number> = {}
    const energySnapshot: WorkdayRhythmDay['energySnapshot'] = {}
    const candidateRecords =
      candidate.energyRecords && typeof candidate.energyRecords === 'object'
        ? candidate.energyRecords
        : {}
    for (const id of validEnergyIds) {
      const stored = candidateRecords[id]
      const legacy =
        id === 'WATER'
          ? candidate.waterCount
          : id === 'MEDITATION'
            ? candidate.meditated === true
              ? 1
              : 0
            : 0
      const value = Number.isInteger(stored) ? Number(stored) : Number(legacy)
      energyRecords[id] = Math.max(0, Math.min(99, Number.isFinite(value) ? value : 0))
    }
    // 配置中删除项目只影响今天的操作入口，不能抹掉这一天已经真实落库的历史次数。
    for (const [id, stored] of Object.entries(candidateRecords)) {
      if (/^[A-Za-z0-9_-]{1,40}$/.test(id) && Number.isInteger(stored)) {
        energyRecords[id] = Math.max(0, Math.min(99, Number(stored)))
      }
    }
    const candidateSnapshot =
      candidate.energySnapshot && typeof candidate.energySnapshot === 'object'
        ? candidate.energySnapshot
        : {}
    for (const [id, snapshot] of Object.entries(candidateSnapshot)) {
      if (
        /^[A-Za-z0-9_-]{1,40}$/.test(id) &&
        snapshot &&
        typeof snapshot.title === 'string' &&
        snapshot.title.trim() &&
        snapshot.title.trim().length <= 20 &&
        (snapshot.mode === 'COUNT' || snapshot.mode === 'DAILY_ONCE')
      ) {
        energySnapshot[id] = { title: snapshot.title.trim(), mode: snapshot.mode }
      }
    }
    return {
      version: 1,
      date,
      workdayActive:
        typeof candidate.workdayActive === 'boolean'
          ? candidate.workdayActive
          : fallback.workdayActive,
      completed,
      waterCount: energyRecords.WATER ?? 0,
      meditated: (energyRecords.MEDITATION ?? 0) > 0,
      energyRecords,
      energySnapshot,
      focus,
      focusCompleted,
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
