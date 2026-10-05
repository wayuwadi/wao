import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { useNotesCompanionLayout } from '@/composables/notesCompanionLayout'
import { toast } from '@/composables/toast'
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
const key = (id: string) => `wao-notes-companion-layout-v1:${id}`
const wrappers: ReturnType<typeof mount>[] = []
function fixture(h = 816, id = 'a') {
  const member = ref(id)
  const pane = document.createElement('div')
  vi.spyOn(pane, 'getBoundingClientRect').mockReturnValue({ top: 100, height: h } as DOMRect)
  let layout!: ReturnType<typeof useNotesCompanionLayout>
  const wrapper = mount(defineComponent({ setup() { layout = useNotesCompanionLayout(ref(pane), member); return () => null } }))
  wrappers.push(wrapper)
  const target = document.createElement('button')
  const start = () => layout.start({ button: 0, pointerId: 3, currentTarget: target, preventDefault: vi.fn() } as unknown as PointerEvent)
  const pointer = (type: string, y: number, pointerId = 3) => {
    const event = new Event(type)
    Object.assign(event, { pointerId, clientY: y })
    window.dispatchEvent(event)
  }
  return { layout, member, start, pointer }
}
beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); vi.stubGlobal('innerWidth', 1600) })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('成员隔离的伴读空间', () => {
  it('拖动立即分配高度，忽略其他指针；取消后移除监听并保存', () => {
    const { layout, start, pointer } = fixture()
    start(); pointer('pointermove', 668, 99)
    expect(layout.percentage.value).toBe(65)
    pointer('pointermove', 668)
    expect(layout.percentage.value).toBe(70)
    pointer('pointercancel', 0)
    expect(layout.resizing.value).toBe(false)
    pointer('pointermove', 400)
    expect(layout.percentage.value).toBe(70)
    expect(JSON.parse(localStorage.getItem(key('a'))!).ratio).toBe(.7)
  })
  it('收起恢复比例，成员切换和刷新只读取本人成员偏好', async () => {
    const { layout, member } = fixture()
    layout.keydown(new KeyboardEvent('keydown', { key: 'ArrowDown', shiftKey: true }))
    layout.setCollapsed(true)
    expect(layout.percentage.value).toBe(70)
    member.value = 'b'; await nextTick()
    expect(layout.collapsed.value).toBe(false)
    expect(layout.percentage.value).toBe(65)
    layout.setCollapsed(true)
    member.value = 'a'; await nextTick()
    expect(layout.collapsed.value).toBe(true)
    layout.setCollapsed(false)
    expect(layout.percentage.value).toBe(70)
    expect(fixture().layout.percentage.value).toBe(70)
  })
  it('短窗口限制显示而不改写偏好，破损存储回到默认', () => {
    localStorage.setItem(key('a'), JSON.stringify({ ratio: .75, collapsed: false }))
    const { layout } = fixture(400)
    expect(layout.percentage.value).toBe(55)
    expect(JSON.parse(localStorage.getItem(key('a'))!).ratio).toBe(.75)
    localStorage.setItem(key('b'), '{broken')
    expect(fixture(816, 'b').layout.percentage.value).toBe(65)
  })
  it('切换成员、窗口变化及卸载终止拖动，窄屏不启用分隔线', async () => {
    const { layout, start, pointer, member } = fixture()
    start(); pointer('pointermove', 668)
    member.value = 'b'; await nextTick()
    expect(localStorage.getItem(key('b'))).toBeNull()
    pointer('pointermove', 408)
    expect(layout.percentage.value).toBe(65)
    start(); window.dispatchEvent(new Event('resize'))
    expect(layout.resizing.value).toBe(false)
    start(); wrappers[0]!.unmount(); pointer('pointermove', 408)
    expect(layout.resizing.value).toBe(false)
    vi.stubGlobal('innerWidth', 390)
    start()
    expect(layout.resizing.value).toBe(false)
  })
  it('存储失败不妨碍当前收起和恢复，并提示再次操作重试', () => {
    const { layout } = fixture()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    layout.setCollapsed(true)
    expect(layout.collapsed.value).toBe(true)
    expect(toast).toHaveBeenCalled()
    layout.setCollapsed(false)
    expect(layout.collapsed.value).toBe(false)
  })
})
