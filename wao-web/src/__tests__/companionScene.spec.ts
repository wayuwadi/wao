import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createCompanionScene } from '@/components/home/companionScene'

const spies = vi.hoisted(() => ({
  render: vi.fn(),
  dispose: vi.fn(),
  context: vi.fn(),
  disconnect: vi.fn(),
}))
vi.mock('three', async (original) => {
  const actual = await original<typeof import('three')>()
  return {
    ...actual,
    WebGLRenderer: class {
      setPixelRatio() {}
      setClearColor() {}
      setSize() {}
      render = spies.render
      dispose = spies.dispose
      forceContextLoss = spies.context
    },
  }
})
vi.mock('@/components/home/companionModels', async () => {
  const { Group } = await import('three')
  return {
    createCompanionModels: () =>
      Array.from({ length: 4 }, (_, i) => ({
        group: new Group(),
        body: new Group(),
        shadow: new Group(),
        eyes: [],
        pupils: [],
        i,
        x: 0,
        y: 0,
        vx: 0,
        vy: 0,
        homeX: 0,
        homeY: 0,
        size: 78,
        phase: 0,
        blinkAt: 5,
        blink: 0,
        action: 0,
        drag: false,
        hover: false,
      })),
  }
})
let frames: Map<number, FrameRequestCallback>, frameId: number
let media: EventTarget & { matches: boolean }
beforeEach(() => {
  vi.clearAllMocks()
  frames = new Map()
  frameId = 0
  media = Object.assign(new EventTarget(), { matches: false })
  vi.stubGlobal('matchMedia', () => media)
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
    frames.set(++frameId, cb)
    return frameId
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect = spies.disconnect
    },
  )
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      observe() {}
      disconnect = spies.disconnect
    },
  )
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
function mountScene() {
  const parent = document.createElement('div'),
    host = document.createElement('div'),
    canvas = document.createElement('canvas')
  parent.append(host)
  host.append(canvas)
  document.body.append(parent)
  Object.defineProperties(host, { clientWidth: { value: 900 }, clientHeight: { value: 300 } })
  const targets = Array.from({ length: 4 }, () => {
    const b = document.createElement('button')
    host.append(b)
    return b
  })
  return {
    canvas,
    controller: createCompanionScene({ host, canvas, targets, say: vi.fn(), failed: vi.fn() }),
  }
}
function paint(time = 100) {
  const work = [...frames.values()]
  frames.clear()
  work.forEach((fn) => fn(time))
}
describe('三维场景资源与省电', () => {
  it('安静模式只画一帧，唤醒后重新排帧', () => {
    const { controller } = mountScene()
    controller.setQuiet(true)
    paint()
    expect(spies.render).toHaveBeenCalledOnce()
    expect(frames.size).toBe(0)
    controller.setQuiet(false)
    paint(200)
    expect(frames.size).toBe(1)
    controller.dispose()
  })
  it('系统减少动态效果与页签停用均停止连续绘制', () => {
    const { controller } = mountScene()
    media.matches = true
    media.dispatchEvent(new Event('change'))
    paint()
    expect(frames.size).toBe(0)
    controller.setActive(false)
    expect(frames.size).toBe(0)
    controller.setActive(true)
    expect(frames.size).toBe(1)
    controller.dispose()
    expect(frames.size).toBe(0)
    media.dispatchEvent(new Event('change'))
    expect(frames.size).toBe(0)
    expect(spies.disconnect).toHaveBeenCalledTimes(2)
    expect(spies.context).toHaveBeenCalledOnce()
    expect(spies.dispose).toHaveBeenCalledOnce()
  })
  it('上下文丢失不继续绘制，释放后可见性事件不会重启场景', () => {
    const { controller, canvas } = mountScene()
    canvas.dispatchEvent(new Event('webglcontextlost', { cancelable: true }))
    expect(frames.size).toBe(0)
    controller.dispose()
    document.dispatchEvent(new Event('visibilitychange'))
    expect(frames.size).toBe(0)
  })
})
