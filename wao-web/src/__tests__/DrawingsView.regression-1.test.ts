import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DrawingsView from '@/views/DrawingsView.vue'
import {
  deleteDrawing,
  fetchDrawing,
  fetchDrawings,
  fetchDrawingVersions,
  saveDrawing,
  renameDrawing,
  type DrawingFile,
} from '@/api/drawings'
import { readDrawingDraft, removeDrawingDraft, writeDrawingDraft } from '@/utils/drawingDrafts'

vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'member-a' }) }))
vi.mock('@/api/drawings', () => ({
  createDrawing: vi.fn(),
  deleteDrawing: vi.fn(),
  fetchDrawing: vi.fn(),
  fetchDrawings: vi.fn(),
  fetchDrawingVersions: vi.fn(),
  renameDrawing: vi.fn(),
  restoreDrawingVersion: vi.fn(),
  saveDrawing: vi.fn(),
}))
vi.mock('@/utils/drawingDrafts', () => ({
  readDrawingDraft: vi.fn(),
  removeDrawingDraft: vi.fn().mockResolvedValue(undefined),
  writeDrawingDraft: vi.fn(),
}))
vi.mock('@/components/drawings/DrawingCanvas.vue', () => ({
  default: {
    name: 'DrawingCanvas',
    props: ['sceneJson', 'sceneKey', 'name'],
    emits: ['change', 'ready', 'save'],
    template: '<div class="canvas-stub" />',
  },
}))

const scene = (id: string) => JSON.stringify({ elements: [{ id }], appState: {} })
const file = (id = 'a', patch: Partial<DrawingFile> = {}): DrawingFile => ({
  id,
  title: `草图 ${id}`,
  sceneJson: scene(id),
  revision: 1,
  createdAt: '2026-10-01',
  updatedAt: '2026-10-01',
  ...patch,
})
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
const wrappers: ReturnType<typeof mount>[] = []
async function open() {
  const w = mount(DrawingsView)
  wrappers.push(w)
  await flushPromises()
  return w
}
function change(w: ReturnType<typeof mount>, value: string) {
  w.findComponent({ name: 'DrawingCanvas' }).vm.$emit('change', value)
}

// Regression: ISSUE-001 — pending edits must survive saves, switches and failures.
// Found by /qa on 2026-10-29. Report: artifacts/drawing-qa-report.md (local).
describe('草图保存与文件管理回归', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    window.history.replaceState({}, '', '/drawings?drawing=a')
    vi.mocked(fetchDrawings).mockResolvedValue([file(), file('b')])
    vi.mocked(fetchDrawing).mockImplementation(async (id) => file(id))
    vi.mocked(fetchDrawingVersions).mockResolvedValue([])
    vi.mocked(readDrawingDraft).mockResolvedValue(null)
    vi.mocked(writeDrawingDraft).mockResolvedValue(undefined)
    vi.mocked(saveDrawing).mockImplementation(async (id, sceneJson, revision) =>
      file(id, { sceneJson, revision: revision + 1 }),
    )
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })
  afterEach(() => {
    wrappers.splice(0).forEach((w) => w.unmount())
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('保存中继续编辑后切换文件，先保存最后一笔', async () => {
    const pending = deferred<DrawingFile>()
    vi.mocked(saveDrawing).mockReturnValueOnce(pending.promise)
    const w = await open()
    change(w, scene('first'))
    await w.vm.$nextTick()
    await w.get('.save-button').trigger('click')
    change(w, scene('last'))
    await w.vm.$nextTick()
    await w.findAll('.file-open')[1]!.trigger('click')
    pending.resolve(file('a', { sceneJson: scene('first'), revision: 2 }))
    await flushPromises()
    expect(saveDrawing).toHaveBeenLastCalledWith('a', scene('last'), 2)
    expect(fetchDrawing).toHaveBeenLastCalledWith('b')
  })

  it('云端和本机均保存失败时不能切换并遗失修改', async () => {
    vi.mocked(saveDrawing).mockRejectedValue(new Error('离线'))
    vi.mocked(writeDrawingDraft).mockRejectedValue(new Error('配额不足'))
    const w = await open()
    change(w, scene('unsaved'))
    await w.vm.$nextTick()
    await w.findAll('.file-open')[1]!.trigger('click')
    await flushPromises()
    expect(fetchDrawing).not.toHaveBeenCalledWith('b')
    expect(w.findComponent({ name: 'DrawingCanvas' }).props('sceneJson')).toBe(scene('unsaved'))
    expect(w.text()).not.toContain('草稿仍在本机')
  })

  it('删除失败保留未保存状态和当前内容', async () => {
    vi.mocked(deleteDrawing).mockRejectedValue(new Error('删除失败'))
    const w = await open()
    change(w, scene('keep'))
    await w.vm.$nextTick()
    await w.findAll('.file-row')[0]!.get('button[title="删除"]').trigger('click')
    await flushPromises()
    expect(w.find('.save-button').exists()).toBe(true)
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
  })

  it('冲突后继续绘制仍保留冲突状态，不能自动覆盖云端', async () => {
    vi.mocked(readDrawingDraft).mockResolvedValue({
      id: 'a',
      memberId: 'member-a',
      sceneJson: scene('draft'),
      revision: 0,
      updatedAt: 1,
    })
    const w = await open()
    change(w, scene('conflict-edit'))
    await w.vm.$nextTick()
    expect(w.find('.sync-pill.conflict').exists()).toBe(true)
    expect(w.text()).toContain('重新读取云端')
    expect(saveDrawing).not.toHaveBeenCalled()
  })

  it('读取单个文件失败时重试同一文件', async () => {
    vi.mocked(fetchDrawing).mockRejectedValueOnce(new Error('读取超时'))
    const w = await open()
    await w.get('.error-banner button').trigger('click')
    await flushPromises()
    expect(fetchDrawing).toHaveBeenCalledTimes(2)
    expect(fetchDrawing).toHaveBeenLastCalledWith('a')
    expect(w.find('.canvas-stub').exists()).toBe(true)
  })

  it('列表读取失败不显示为没有草图', async () => {
    vi.mocked(fetchDrawings).mockRejectedValue(new Error('列表不可用'))
    const w = await open()
    expect(w.text()).not.toContain('还没有草图')
    expect(w.text()).toContain('列表不可用')
  })

  it('保存失败应拒绝更新刷新请求', async () => {
    vi.mocked(saveDrawing).mockRejectedValue(new Error('离线'))
    const w = await open()
    change(w, scene('pending'))
    await w.vm.$nextTick()
    let pending!: Promise<unknown>
    window.dispatchEvent(
      new CustomEvent('wao-save-before-refresh', {
        detail: {
          waitUntil: (p: Promise<unknown>) => {
            pending = p
          },
        },
      }),
    )
    await expect(pending).rejects.toThrow()
  })

  it('导入内容与云端一致时使用云端最新版本号', async () => {
    vi.mocked(readDrawingDraft).mockResolvedValue({
      id: 'a',
      memberId: 'member-a',
      sceneJson: scene('a'),
      revision: 0,
      updatedAt: 1,
    })
    const w = await open()
    expect(removeDrawingDraft).toHaveBeenCalledWith('member-a', 'a')
    change(w, scene('next'))
    await w.vm.$nextTick()
    await w.get('.save-button').trigger('click')
    await flushPromises()
    expect(saveDrawing).toHaveBeenCalledWith('a', scene('next'), 1)
  })

  it('重新读取云端失败时保留原画布及本机草稿', async () => {
    vi.mocked(saveDrawing).mockRejectedValue(new Error('离线'))
    const w = await open()
    change(w, scene('keep'))
    await w.vm.$nextTick()
    await w.get('.save-button').trigger('click')
    await flushPromises()
    vi.mocked(fetchDrawing).mockRejectedValue(new Error('云端不可达'))
    await w
      .findAll('.error-banner button')
      .find((b) => b.text() === '重新读取云端')!
      .trigger('click')
    await flushPromises()
    expect(removeDrawingDraft).not.toHaveBeenCalled()
    expect(w.findComponent({ name: 'DrawingCanvas' }).props('sceneJson')).toBe(scene('keep'))
  })

  it('保存完成后延迟草稿定时器不能重新写入过期版本', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const w = await open()
    change(w, scene('saved'))
    await w.vm.$nextTick()
    await w.get('.save-button').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(500)
    expect(writeDrawingDraft).not.toHaveBeenCalled()
    expect(removeDrawingDraft).toHaveBeenCalledWith('member-a', 'a')
  })

  it('列表可收起展开，搜索无结果可清除', async () => {
    const w = await open()
    await w.get('[aria-label="收起文件列表"]').trigger('click')
    expect(w.find('.file-sidebar').exists()).toBe(false)
    await w.get('[aria-label="展开文件列表"]').trigger('click')
    await w.get('[aria-label="搜索草图"]').setValue('b')
    expect(w.findAll('.file-open')).toHaveLength(1)
    await w.get('[aria-label="搜索草图"]').setValue('不存在')
    expect(w.text()).toContain('没有匹配的草图')
    await w
      .findAll('button')
      .find((b) => b.text() === '清除搜索')!
      .trigger('click')
    expect(w.findAll('.file-open')).toHaveLength(2)
  })

  it('手机选中文件后收起列表，保存状态仍可读', async () => {
    vi.stubGlobal('innerWidth', 390)
    const w = await open()
    expect(w.find('.file-sidebar').exists()).toBe(false)
    expect(w.get('.sync-pill').text()).toContain('已保存到账号')
    vi.unstubAllGlobals()
  })

  it('快捷键保存事件触发实际账号保存', async () => {
    const w = await open()
    change(w, scene('shortcut'))
    await w.vm.$nextTick()
    w.findComponent({ name: 'DrawingCanvas' }).vm.$emit('save')
    await flushPromises()
    expect(saveDrawing).toHaveBeenCalledWith('a', scene('shortcut'), 1)
  })

  it('场景保存的迟到响应不回退已经完成的改名', async () => {
    const pending = deferred<DrawingFile>()
    vi.mocked(saveDrawing).mockReturnValueOnce(pending.promise)
    vi.mocked(renameDrawing).mockResolvedValue(file('a', { title: '新名称' }))
    const w = await open()
    change(w, scene('edit')); await w.vm.$nextTick()
    await w.get('.save-button').trigger('click')
    await w.get('.title-trigger').trigger('click')
    await w.get('[aria-label="草图名称"]').setValue('新名称')
    await w.get('[aria-label="草图名称"]').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    pending.resolve(file('a', { sceneJson: scene('edit'), revision: 2 }))
    await flushPromises()
    expect(w.get('.title-trigger').text()).toContain('新名称')
    expect(w.findAll('.file-main strong').some(node => node.text() === '新名称')).toBe(true)
  })
})
