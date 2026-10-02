import { afterEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import PlatformGame from '@/components/relax/PlatformGame.vue'
import MemoryGame from '@/components/relax/MemoryGame.vue'
import JournalGame from '@/components/relax/JournalGame.vue'
import WorldScene from '@/components/relax/WorldScene.vue'
import { freshProgress } from '@/components/relax/progress'

const loop = vi.hoisted(() => ({ step: (_dt: number) => {} }))
vi.mock('@/components/relax/useGameLoop', () => ({
  useGameLoop: (_active: unknown, step: (dt: number) => void) => {
    loop.step = step
  },
}))
afterEach(() => vi.restoreAllMocks())
async function advance(seconds: number) {
  for (let i = 0; i < seconds * 60; i++) loop.step(1 / 60)
  await nextTick()
}

it('近期趣事不阻挡角色打招呼，换场景后重新展示趣事', async () => {
  const w = mount(WorldScene, { props: { world: 'theater', story: '刚刚顺利谢幕。' } })
  expect(w.get('[role="status"]').text()).toBe('刚刚顺利谢幕。')
  await w.findAll('button')[0]!.trigger('click')
  expect(w.get('[role="status"]').text()).toContain('灵感才有地方蹦进来')
  await w.setProps({ world: 'gallery' })
  expect(w.get('[role="status"]').text()).toBe('刚刚顺利谢幕。')
  w.unmount()
})

it('五名角色通过真实按键依次取得贴纸并抵达终点', async () => {
  const progress = reactive(freshProgress().platform)
  const w = mount(PlatformGame, { props: { progress, active: true } })
  const board = w.get('[role="group"]')
  const key = async (code: string) => {
    await board.trigger('keydown', { code })
    await board.trigger('keyup', { code })
  }
  const walk = async (seconds: number, direction = 'ArrowRight') => {
    await board.trigger('keydown', { code: direction })
    await advance(seconds)
    await board.trigger('keyup', { code: direction })
  }
  await advance(0.3)
  await key('KeyE')
  await walk(0.5)
  expect(progress.collected).toContain(0)
  await key('Digit3')
  await walk(0.65)
  await key('KeyE')
  await key('KeyE')
  await key('KeyE')
  expect(progress.crate).toBeGreaterThanOrEqual(330)
  await walk(0.5)
  expect(progress.collected).toContain(2)
  await key('Space')
  await walk(0.55)
  await key('Digit2')
  await key('KeyE')
  expect(progress.bridge).toBe(true)
  await walk(0.45)
  await advance(0.7)
  expect(progress.collected).toContain(1)
  await walk(1.45)
  await key('Digit4')
  await key('KeyE')
  await advance(0.5)
  expect(progress.collected).toContain(3)
  await key('Digit5')
  await walk(0.3)
  await key('KeyE')
  expect(progress.gate).toBe(true)
  await walk(0.9)
  expect(progress.collected).toHaveLength(5)
  expect(progress.won).toBe(true)
  expect(w.text()).toContain('五位伙伴一起到家了')
  expect(w.emitted('story')).toBeTruthy()
  w.unmount()
})

it('记忆接力暂停后重放，答错可重试，正确顺序才进入下一幕', async () => {
  const progress = reactive(freshProgress().memory)
  progress.sequence = [0, 2, 4]
  const w = mount(MemoryGame, { props: { progress, active: true } })
  await w.get('.game-primary').trigger('click')
  await advance(0.5)
  await w.setProps({ active: false })
  expect(w.text()).toContain('动作已暂停')
  await w.setProps({ active: true })
  await w.get('.game-primary').trigger('click')
  await advance(4)
  await w.findAll('.memory-buddy')[1]!.trigger('click')
  expect(w.text()).toContain('再看一遍就好')
  expect(progress.best).toBe(0)
  await w.get('.game-primary').trigger('click')
  await advance(4)
  for (const i of [0, 2, 4]) await w.findAll('.memory-buddy')[i]!.trigger('click')
  expect(progress.best).toBe(1)
  expect(w.text()).toContain('下一幕')
  w.unmount()
})

it('手账导出包含独立图片和转义后的文字，提供可重试下载链接', async () => {
  let file: Blob | undefined
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn((blob: Blob) => {
      file = blob
      return 'blob:journal'
    }),
    revokeObjectURL: vi.fn(),
  })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const progress = reactive(freshProgress().journal)
  progress.text = '<script> & 今天'
  const w = mount(JournalGame, { props: { progress, active: true } })
  await w.findAll('button')[0]!.trigger('click')
  await w
    .findAll('button')
    .find((b) => b.text().includes('导出这一页'))!
    .trigger('click')
  expect(file?.type).toBe('image/svg+xml')
  const xml = await new Promise<string>((resolve) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.readAsText(file!)
  })
  expect(xml).toContain('data:image/svg+xml')
  expect(xml).toContain('&lt;script&gt; &amp; 今天')
  const doc = new DOMParser().parseFromString(xml, 'image/svg+xml')
  expect(doc.querySelector('parsererror')).toBeNull()
  expect(w.get('a[download]').attributes('href')).toBe('blob:journal')
  w.unmount()
  vi.unstubAllGlobals()
})
