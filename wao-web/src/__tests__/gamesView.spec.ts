import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import GamesView from '@/views/GamesView.vue'
import {
  fetchNumberMergeGame,
  moveNumberMergeGame,
  restartNumberMergeGame,
  undoNumberMergeGame,
  type MoveDirection,
  type NumberMergeGame,
} from '@/api/games'

vi.mock('@/api/games', () => ({
  fetchNumberMergeGame: vi.fn<() => Promise<NumberMergeGame>>(),
  moveNumberMergeGame: vi.fn<(direction: MoveDirection, revision: number) => Promise<NumberMergeGame>>(),
  restartNumberMergeGame: vi.fn<(revision: number) => Promise<NumberMergeGame>>(),
  undoNumberMergeGame: vi.fn<(revision: number) => Promise<NumberMergeGame>>(),
}))

function snapshot(patch: Partial<NumberMergeGame> = {}): NumberMergeGame {
  return {
    board: [2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    score: 0,
    moves: 0,
    won: false,
    gameOver: false,
    canUndo: false,
    revision: 0,
    startedAt: '2026-07-30T18:00:00',
    updatedAt: '2026-07-30T18:00:00',
    stats: { bestScore: 3200, highestTile: 256, gamesPlayed: 4, totalMoves: 188, wins: 0 },
    recentRuns: [],
    ...patch,
  }
}

function dispatchPointer(target: Element, type: string, clientX: number, clientY: number) {
  const event = new Event(type, { bubbles: true })
  Object.defineProperties(event, {
    clientX: { value: clientX },
    clientY: { value: clientY },
  })
  target.dispatchEvent(event)
}

describe('游戏厅 · 数字熔炉', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    vi.mocked(fetchNumberMergeGame).mockResolvedValue(snapshot())
  })

  it('恢复账号存档并展示长期统计', async () => {
    const wrapper = mount(GamesView)
    await flushPromises()

    expect(fetchNumberMergeGame).toHaveBeenCalledOnce()
    expect(wrapper.findAll('.number-tile')).toHaveLength(16)
    expect(wrapper.text()).toContain('历史最高')
    expect(wrapper.text()).toContain('3,200')
    expect(wrapper.text()).toContain('已保存到账号')
    wrapper.unmount()
  })

  it('方向键提交服务端权威移动并刷新棋盘版本', async () => {
    vi.mocked(moveNumberMergeGame).mockResolvedValue(snapshot({
      board: [4, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
      score: 4,
      moves: 1,
      canUndo: true,
      revision: 1,
    }))
    const wrapper = mount(GamesView)
    await flushPromises()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    await flushPromises()

    expect(moveNumberMergeGame).toHaveBeenCalledWith('LEFT', 0)
    expect(wrapper.text()).toContain('第 1 步')
    expect(wrapper.get('.toolbar-actions button').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('重开前确认并把服务端返回的上一局放进战绩', async () => {
    vi.mocked(fetchNumberMergeGame).mockResolvedValue(snapshot({ moves: 12, score: 88, revision: 3 }))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(restartNumberMergeGame).mockResolvedValue(snapshot({
      revision: 4,
      recentRuns: [{
        id: 'gr1', score: 88, highestTile: 16, moves: 12, outcome: 'RESTARTED',
        startedAt: '2026-07-30T18:00:00', endedAt: '2026-07-30T18:10:00',
      }],
    }))
    const wrapper = mount(GamesView)
    await flushPromises()

    await wrapper.get('.toolbar-actions .primary').trigger('click')
    await flushPromises()

    expect(restartNumberMergeGame).toHaveBeenCalledWith(3)
    expect(wrapper.text()).toContain('88 分')
    expect(wrapper.text()).toContain('主动重开')
    wrapper.unmount()
  })

  it('手机滑动和屏幕方向按钮都进入同一服务端移动队列', async () => {
    vi.mocked(moveNumberMergeGame)
      .mockResolvedValueOnce(snapshot({ revision: 1 }))
      .mockResolvedValueOnce(snapshot({ revision: 2 }))
    const wrapper = mount(GamesView)
    await flushPromises()

    dispatchPointer(wrapper.get('.number-board').element, 'pointerdown', 20, 80)
    dispatchPointer(wrapper.get('.number-board').element, 'pointerup', 120, 82)
    await flushPromises()
    await wrapper.get('.direction-pad button[aria-label="向上"]').trigger('click')
    await flushPromises()

    expect(moveNumberMergeGame).toHaveBeenNthCalledWith(1, 'RIGHT', 0)
    expect(moveNumberMergeGame).toHaveBeenNthCalledWith(2, 'UP', 1)
    wrapper.unmount()
  })

  it('撤销使用当前 revision 并展示服务端恢复的前态', async () => {
    vi.mocked(fetchNumberMergeGame).mockResolvedValue(snapshot({ score: 4, moves: 1, canUndo: true, revision: 6 }))
    vi.mocked(undoNumberMergeGame).mockResolvedValue(snapshot({ revision: 7 }))
    const wrapper = mount(GamesView)
    await flushPromises()

    await wrapper.get('.toolbar-actions button').trigger('click')
    await flushPromises()

    expect(undoNumberMergeGame).toHaveBeenCalledWith(6)
    expect(wrapper.text()).toContain('第 0 步')
    wrapper.unmount()
  })
})
