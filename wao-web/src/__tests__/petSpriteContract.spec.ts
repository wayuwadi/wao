import { describe, expect, it } from 'vitest'
import { petFrameAt, petLookFrame, PET_SEQUENCES } from '@/utils/petSprite'

describe('张三丰图集播放契约', () => {
  it('左右跑步逐帧播放并在整轮结束后循环，始终不进入相邻动作行', () => {
    for (const state of ['running-left', 'running-right'] as const) {
      const row = state === 'running-left' ? 2 : 1
      expect(petFrameAt(state, 0)).toEqual({ row, column: 0, finished: false })
      expect(petFrameAt(state, 121)).toEqual({ row, column: 1, finished: false })
      expect(petFrameAt(state, 850)).toEqual({ row, column: 7, finished: false })
      expect(petFrameAt(state, 1060)).toEqual({ row, column: 0, finished: false })
    }
  })
  it('一次性动作停在最后一帧，不进入透明空格', () => {
    expect(petFrameAt('waving', 700)).toEqual({ row: 3, column: 3, finished: true })
    expect(petFrameAt('jumping', 840)).toEqual({ row: 4, column: 4, finished: true })
    for (const state of Object.keys(PET_SEQUENCES) as (keyof typeof PET_SEQUENCES)[]) {
      expect(petFrameAt(state, 50000).column).toBeLessThan(PET_SEQUENCES[state].durations.length)
    }
  })
  it('视线以正上为零度，跨两行并在360度首尾衔接', () => {
    expect(petLookFrame(0)).toMatchObject({ row: 9, column: 0 })
    expect(petLookFrame(90)).toMatchObject({ row: 9, column: 4 })
    expect(petLookFrame(180)).toMatchObject({ row: 10, column: 0 })
    expect(petLookFrame(270)).toMatchObject({ row: 10, column: 4 })
    expect(petLookFrame(-22.5)).toMatchObject({ row: 10, column: 7 })
    expect(petLookFrame(360)).toEqual(petLookFrame(0))
  })
})
