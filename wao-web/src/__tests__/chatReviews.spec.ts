import { describe, expect, it } from 'vitest'
import {
  chatReviewRangeError,
  defaultChatReviewRange,
  inclusiveDays,
  parseShanghaiDateTime,
  shanghaiDate,
  shiftDate,
} from '@/utils/chatReviews'

describe('聊天复盘上海自然日', () => {
  it('在 UTC 仍是前一天时，按上海日期生成最近 7 天', () => {
    const now = new Date('2026-08-19T16:30:00Z')

    expect(shanghaiDate(now)).toBe('2026-08-20')
    expect(defaultChatReviewRange(now)).toEqual({
      startDate: '2026-08-14',
      endDate: '2026-08-20',
    })
  })

  it('跨月、闰日偏移不受本地时区影响', () => {
    expect(shiftDate('2028-03-01', -1)).toBe('2028-02-29')
    expect(shiftDate('2026-01-01', -1)).toBe('2025-12-31')
  })

  it('日期范围首尾都计入，31 天允许而 32 天拒绝', () => {
    expect(inclusiveDays('2026-07-20', '2026-08-19')).toBe(31)
    expect(chatReviewRangeError('2026-07-20', '2026-08-19', '2026-08-19')).toBe('')
    expect(chatReviewRangeError('2026-07-19', '2026-08-19', '2026-08-19')).toContain('31')
  })

  it('拒绝倒序、未来和非法日期', () => {
    expect(chatReviewRangeError('2026-08-20', '2026-08-19', '2026-08-19')).toContain('不能晚于')
    expect(chatReviewRangeError('2026-08-19', '2026-08-20', '2026-08-19')).toContain('今天')
    expect(chatReviewRangeError('2026-02-30', '2026-03-01', '2026-08-19')).toContain('格式')
    expect(chatReviewRangeError('0999-12-31', '0999-12-31', '2026-08-19')).toContain('1970')
  })

  it('把后端无 offset 时间按上海时区解析', () => {
    expect(parseShanghaiDateTime('2026-08-19T20:30:00').toISOString()).toBe(
      '2026-08-19T12:30:00.000Z',
    )
    expect(parseShanghaiDateTime('2026-08-19T12:30:00Z').toISOString()).toBe(
      '2026-08-19T12:30:00.000Z',
    )
  })
})
