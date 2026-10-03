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
    targets,
    controller: createCompanionScene({ host, canvas, targets, say: vi.fn(), failed: vi.fn() }),
  }
}
function paint(time = 100) {
  const work = [...frames.values()]
  frames.clear()
  work.forEach((fn) => fn(time))
}
describe('三维场景资源与省电', () => {
  it('只让被点的伙伴升空、散成泡沫再归位，重复点击不重置计时', () => {
    vi.spyOn(performance, 'now').mockReturnValue(0)
    const { controller } = mountScene()
    paint(100)
    const scene = spies.render.mock.lastCall![0]
    const groups = scene.children.filter((child: { type: string }) => child.type === 'Group')
    const original = groups.map((g: { position: { y: number } }) => g.position.y)
    expect(controller.pop(1)).toBe(true)
    paint(2300)
    expect(groups[1].position.y).toBeGreaterThan(original[1] + 90)
    expect(Math.abs(groups[0].position.y - original[0])).toBeLessThan(4)
    vi.mocked(performance.now).mockReturnValue(2300)
    expect(controller.pop(1)).toBe(true)
    paint(3100)
    expect(groups[1].visible).toBe(false)
    expect(scene.getObjectByName('companion-bubbles-1').visible).toBe(true)
    paint(4700)
    expect(groups[1].visible).toBe(true)
    expect(Math.abs(groups[1].position.y - original[1])).toBeLessThan(4)
    expect(scene.getObjectByName('companion-bubbles-1').visible).toBe(false)
    expect(groups[1].scale.x).toBe(78)
    controller.pop(2)
    controller.setQuiet(true)
    paint(6000)
    expect(controller.pop(0)).toBe(false)
    expect(groups[2].visible).toBe(true)
    expect(scene.getObjectByName('companion-bubbles-2').visible).toBe(false)
    controller.dispose()
    expect(controller.pop(0)).toBe(false)
  })
  it('静止和活动时点击区域都留在可见画布内，聚焦不撑出隐藏滚动区', () => {
    vi.spyOn(performance, 'now').mockReturnValue(0)
    const { controller, targets } = mountScene()
    for (const time of [100, 4000, 16000]) {
      if (time === 4000) controller.activity('ball')
      paint(time)
      targets.forEach((target) => {
        const [x, y] = target.style.transform.match(/-?[\d.]+/g)!.map(Number)
        expect(x).toBeGreaterThanOrEqual(0)
        expect(y).toBeGreaterThanOrEqual(0)
        expect(x! + parseFloat(target.style.width)).toBeLessThanOrEqual(900.001)
        expect(y! + parseFloat(target.style.height)).toBeLessThanOrEqual(300.001)
        expect(parseFloat(target.style.height)).toBeGreaterThan(0)
      })
    }
    controller.dispose()
  })
  it('抛球带动四人，活动互斥，十五秒截止后不续播', () => {
    vi.spyOn(performance, 'now').mockReturnValue(0)
    const { controller } = mountScene()
    paint(100)
    const scene = spies.render.mock.lastCall![0]
    const groups = scene.children.filter((child: { type: string }) => child.type === 'Group')
    const original = groups.map((g: { position: { y: number } }) => g.position.y)
    controller.activity('ball')
    paint(4000)
    groups.forEach((g: { position: { y: number } }, i: number) => expect(g.position.y).toBeGreaterThan(original[i] + 15))
    controller.activity('hiphop')
    expect(scene.getObjectByName('companion-play-ball').visible).toBe(false)
    paint(16000)
    groups.forEach((g: { position: { y: number } }, i: number) => expect(Math.abs(g.position.y - original[i])).toBeLessThan(4))
    paint(30000)
    groups.forEach((g: { position: { y: number } }, i: number) => expect(Math.abs(g.position.y - original[i])).toBeLessThan(4))
    controller.dispose()
  })
  it('小球只有主动触发才出现，安静或停用立即收起且不能后台重启', () => {
    const { controller } = mountScene()
    paint()
    const scene = spies.render.mock.lastCall![0]
    const toy = scene.getObjectByName('companion-play-ball')
    expect(toy.visible).toBe(false)
    expect(controller.play()).toBe(true)
    expect(toy.visible).toBe(true)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(toy.visible).toBe(false)
    controller.play()
    paint(performance.now() + 16000)
    expect(toy.visible).toBe(false)
    controller.play()
    controller.setQuiet(true)
    expect(toy.visible).toBe(false)
    expect(controller.play()).toBe(false)
    controller.setQuiet(false)
    controller.play()
    controller.setActive(false)
    expect(toy.visible).toBe(false)
    expect(controller.play()).toBe(false)
    controller.dispose()
    expect(controller.play()).toBe(false)
  })
  it('主动舞蹈尊重安静、减少动态效果和后台暂停', () => {
    const { controller } = mountScene()
    expect(controller.activity('ballet')).toBe(true)
    controller.setQuiet(true)
    expect(controller.activity('hiphop')).toBe(false)
    controller.setQuiet(false)
    media.matches = true
    media.dispatchEvent(new Event('change'))
    expect(controller.activity('ballet')).toBe(false)
    media.matches = false
    media.dispatchEvent(new Event('change'))
    controller.setActive(false)
    expect(controller.activity('ballet')).toBe(false)
    controller.dispose()
    expect(controller.activity('ballet')).toBe(false)
  })
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
