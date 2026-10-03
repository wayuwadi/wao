import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import HomeCompanions from '@/components/home/HomeCompanions.vue'
import { boundCompanion, companionLayout, stepCompanion } from '@/components/home/companionMotion'

const fake = vi.hoisted(() => ({
  create: vi.fn(),
  dispose: vi.fn(),
  setQuiet: vi.fn(),
  setActive: vi.fn(),
  pop: vi.fn(() => true),
}))
vi.mock('@/components/home/companionScene', () => ({ createCompanionScene: fake.create }))
beforeEach(() => {
  vi.clearAllMocks()
  fake.create.mockImplementation(() => ({
    dispose: fake.dispose,
    setQuiet: fake.setQuiet,
    setActive: fake.setActive,
    pop: fake.pop,
  }))
})

describe('首页伙伴的边界和归位', () => {
  it('窄屏四个角色仍在横向边界内', () => {
    for (const width of [280, 390, 1200])
      for (let i = 0; i < 4; i++) {
        const p = companionLayout(width, 400, i)
        expect(Math.abs(p.homeX) + p.size * 1.1).toBeLessThan(width / 2)
      }
  })
  it('高速松手、后台恢复和连续动画后收敛到原位', () => {
    const p = { x: -250, y: 200, homeX: 50, homeY: -100, vx: -240, vy: 240 }
    stepCompanion(p, 600)
    expect(Math.abs(p.x)).toBeLessThan(300)
    for (let i = 0; i < 400; i++) stepCompanion(p, 1 / 30)
    expect(p.x).toBeCloseTo(50, 2)
    expect(p.y).toBeCloseTo(-100, 2)
  })
  it('拖动越界只能留在伙伴区域，不覆盖上方信息卡', () => {
    const p = boundCompanion(10000, 10000, 390, 250, 45)
    expect(p.x).toBe(150)
    expect(p.y).toBeCloseTo(62)
  })
})

describe('首页伙伴的组件生命周期', () => {
  it('左键只与被点的伙伴互动，右键才打开菜单', async () => {
    const w = mount(HomeCompanions)
    await flushPromises()
    await w.findAll('.companion-target')[2]!.trigger('click')
    expect(fake.pop).toHaveBeenCalledWith(2)
    expect(w.emitted('menu')).toBeUndefined()
    await w.findAll('.companion-target')[2]!.trigger('contextmenu')
    expect(w.emitted('menu')).toHaveLength(1)
    w.unmount()
  })
  it('安静开关交给场景处理，卸载释放资源', async () => {
    const w = mount(HomeCompanions)
    await flushPromises()
    expect(w.findAll('.companion-target')).toHaveLength(4)
    await w.find('.companion-actions button').trigger('click')
    expect(fake.setQuiet).toHaveBeenLastCalledWith(true)
    expect(w.text()).toContain('唤醒伙伴')
    w.unmount()
    expect(fake.dispose).toHaveBeenCalledOnce()
  })
  it('异步模块尚未返回时离开首页，不创建后台场景', async () => {
    const w = mount(HomeCompanions)
    w.unmount()
    await flushPromises()
    expect(fake.create).not.toHaveBeenCalled()
  })
  it('WebGL 不可用保留可操作的静态伙伴，并允许重试', async () => {
    fake.create.mockImplementationOnce(() => {
      throw new Error('WebGL unavailable')
    })
    const w = mount(HomeCompanions)
    await flushPromises()
    expect(w.text()).toContain('静态陪伴')
    const oldCanvas = w.find('canvas').element
    await w.find('.companion-target').trigger('click')
    expect(w.emitted('menu')).toBeUndefined()
    expect(w.text()).toContain('安静陪着你')
    await w.find('.companion-actions button').trigger('click')
    await flushPromises()
    expect(w.classes()).toContain('ready')
    expect(w.find('canvas').element).not.toBe(oldCanvas)
    w.unmount()
  })
  it('页签缓存停用时暂停，返回时恢复', async () => {
    const show = ref(true)
    const Parent = defineComponent({
      components: { HomeCompanions },
      setup: () => ({ show }),
      template: '<KeepAlive><HomeCompanions v-if="show" /></KeepAlive>',
    })
    const w = mount(Parent)
    await flushPromises()
    show.value = false
    await flushPromises()
    expect(fake.setActive).toHaveBeenLastCalledWith(false)
    show.value = true
    await flushPromises()
    expect(fake.setActive).toHaveBeenLastCalledWith(true)
    w.unmount()
  })
})
