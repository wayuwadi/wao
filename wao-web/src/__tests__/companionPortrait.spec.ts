import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import CompanionPortrait from '@/components/companions/CompanionPortrait.vue'
const spies = vi.hoisted(() => ({ render: vi.fn(), dispose: vi.fn(), loss: vi.fn(), geometry: vi.fn(), material: vi.fn() }))
vi.mock('three', async original => ({
  ...await original<typeof import('three')>(),
  WebGLRenderer: class { setPixelRatio() {} setSize() {} render = spies.render; dispose = spies.dispose; forceContextLoss = spies.loss },
}))
vi.mock('@/components/home/companionModels', async () => {
  const { Group, Mesh, BoxGeometry, MeshBasicMaterial } = await import('three')
  return { createCompanionModels: () => {
    const group = new Group(), geometry = new BoxGeometry(), material = new MeshBasicMaterial()
    geometry.dispose = spies.geometry; material.dispose = spies.material
    group.add(new Mesh(geometry, material))
    return [{ group, body: new Group(), shadow: new Group() }]
  } }
})
let media: EventTarget & { matches: boolean }, frames: Map<number, FrameRequestCallback>, id: number
beforeEach(() => {
  vi.clearAllMocks(); frames = new Map(); id = 0
  media = Object.assign(new EventTarget(), { matches: false })
  vi.stubGlobal('matchMedia', () => media)
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { frames.set(++id, cb); return id })
  vi.stubGlobal('cancelAnimationFrame', (i: number) => frames.delete(i))
})
afterEach(() => vi.unstubAllGlobals())
it('减少动态效果只绘制一帧，卸载释放图形资源', () => {
  media.matches = true
  const w = mount(CompanionPortrait, { props: { kind: 'note' } })
  expect(w.emitted('ready')).toHaveLength(1)
  expect(spies.render).toHaveBeenCalledOnce(); expect(frames.size).toBe(0)
  w.unmount()
  expect(spies.dispose).toHaveBeenCalledOnce(); expect(spies.loss).toHaveBeenCalledOnce()
  expect(spies.geometry).toHaveBeenCalledOnce(); expect(spies.material).toHaveBeenCalledOnce()
})
it('动画有终点，图形上下文丢失不继续运行', async () => {
  const w = mount(CompanionPortrait, { props: { kind: 'task' } })
  const work = [...frames.values()]; frames.clear()
  work.forEach(cb => cb(performance.now() + 1700))
  expect(frames.size).toBe(0)
  await w.get('canvas').trigger('webglcontextlost')
  expect(w.emitted('failed')).toHaveLength(1)
  w.unmount()
})
