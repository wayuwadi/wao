import { describe, expect, it } from 'vitest'
import {
  DEFAULT_WORKDAY_RHYTHM,
  blockProgress,
  currentBlockIndex,
  defaultRhythmDay,
  minutesUntilEnd,
  parseRhythmConfig,
  parseRhythmConfigDraft,
  parseRhythmDay,
  rhythmConfigError,
  type ShanghaiClock,
} from '@/utils/workdayRhythm'

const clock = (hours: number, minutes: number): ShanghaiClock => ({
  date: '2026-08-17',
  weekday: 1,
  hours,
  minutes,
})

describe('工作日节奏规则', () => {
  it('在边界时刻只命中新的时间块，并正确处理跨午夜睡眠', () => {
    const blocks = DEFAULT_WORKDAY_RHYTHM.blocks
    expect(currentBlockIndex(blocks, clock(6, 59))).toBe(6)
    expect(currentBlockIndex(blocks, clock(7, 0))).toBe(0)
    expect(currentBlockIndex(blocks, clock(9, 0))).toBe(1)
    expect(currentBlockIndex(blocks, clock(23, 0))).toBe(6)
    expect(currentBlockIndex(blocks, clock(1, 0))).toBe(6)
  })

  it('跨午夜时段能计算进度和剩余时间', () => {
    const sleep = DEFAULT_WORKDAY_RHYTHM.blocks[6]!
    expect(blockProgress(sleep, clock(1, 0))).toBe(25)
    expect(minutesUntilEnd(sleep, clock(1, 0))).toBe(360)
    expect(minutesUntilEnd(sleep, clock(23, 30))).toBe(450)
  })

  it('拒绝有空洞的模板，并安全丢弃损坏配置', () => {
    const broken = structuredClone(DEFAULT_WORKDAY_RHYTHM)
    broken.blocks[0]!.end = '08:30'
    expect(rhythmConfigError(broken)).toContain('首尾相接')
    expect(parseRhythmConfig(JSON.stringify(broken))).toBeNull()
    expect(parseRhythmConfig('{broken')).toBeNull()

    const overlapping = structuredClone(DEFAULT_WORKDAY_RHYTHM)
    overlapping.blocks = [
      { ...overlapping.blocks[0]!, start: '07:00', end: '05:00' },
      { ...overlapping.blocks[1]!, start: '05:00', end: '03:00' },
      { ...overlapping.blocks[2]!, start: '03:00', end: '07:00' },
    ]
    expect(rhythmConfigError(overlapping)).toContain('不能相互重叠')
  })

  it('兼容旧模板并校验成员自己的精力通知设置', () => {
    const legacy = structuredClone(DEFAULT_WORKDAY_RHYTHM) as Partial<typeof DEFAULT_WORKDAY_RHYTHM>
    delete legacy.energyItems
    legacy.energyNotification!.notifyWater = false
    legacy.energyNotification!.notifyMeditation = true
    expect(parseRhythmConfig(JSON.stringify(legacy))?.energyItems).toEqual([
      expect.objectContaining({ id: 'WATER', notify: false, mode: 'COUNT' }),
      expect.objectContaining({ id: 'MEDITATION', notify: true, mode: 'DAILY_ONCE' }),
    ])

    const configured = structuredClone(DEFAULT_WORKDAY_RHYTHM)
    configured.energyNotification.enabled = true
    expect(rhythmConfigError(configured)).toContain('请选择')
    configured.energyNotification.chatId = 'oc_daily'
    configured.energyItems.forEach((item) => (item.notify = false))
    expect(rhythmConfigError(configured)).toContain('至少选择一个')

    configured.energyNotification.enabled = false
    configured.energyItems.push({
      id: 'WALK',
      title: '起身走动',
      guidance: '离开座位活动几分钟',
      mode: 'DAILY_ONCE',
      notify: true,
    })
    expect(rhythmConfigError(configured)).toBe('')
  })

  it('每日记录只恢复当天且仍属于模板的真实条目', () => {
    const ids = DEFAULT_WORKDAY_RHYTHM.blocks.map((block) => block.id)
    const restored = parseRhythmDay(
      JSON.stringify({
        version: 1,
        date: '2026-08-17',
        workdayActive: true,
        completed: ['morning', 'unknown', 'morning'],
        waterCount: 120,
        meditated: true,
        focus: ['重点一', '重点二', '重点三', '多余'],
      }),
      '2026-08-17',
      1,
      ids,
    )
    expect(restored.completed).toEqual(['morning'])
    expect(restored.waterCount).toBe(99)
    expect(restored.energyRecords).toEqual({ WATER: 99, MEDITATION: 1 })
    expect(restored.focus).toEqual(['重点一', '重点二', '重点三'])
    expect(
      parseRhythmDay(JSON.stringify({ ...restored, date: '2026-08-16' }), '2026-08-17', 1, ids),
    ).toEqual(defaultRhythmDay('2026-08-17', 1))
  })

  it('只恢复配置中仍存在的自定义补给记录', () => {
    const restored = parseRhythmDay(
      JSON.stringify({
        version: 1,
        date: '2026-08-17',
        workdayActive: true,
        completed: [],
        energyRecords: { WALK: 3, REMOVED: 8 },
        focus: ['', '', ''],
      }),
      '2026-08-17',
      1,
      [],
      ['WALK'],
    )
    expect(restored.energyRecords).toEqual({ WALK: 3 })
  })

  it('保留尚未填完的设置草稿，正式读取时仍拒绝无效配置', () => {
    const draft = structuredClone(DEFAULT_WORKDAY_RHYTHM)
    draft.energyItems.push({
      id: 'WALK',
      title: '',
      guidance: '',
      mode: 'DAILY_ONCE',
      notify: false,
    })
    const raw = JSON.stringify(draft)
    expect(parseRhythmConfig(raw)).toBeNull()
    expect(parseRhythmConfigDraft(raw)?.energyItems).toHaveLength(3)
  })
})
