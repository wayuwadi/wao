import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick, ref } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useDocumentOutline } from '@/composables/documentOutline'
import { documentMarkdown, downloadDocumentMarkdown } from '@/utils/documentExport'
import { richHeadings } from '@/utils/richDoc'

describe('document reading and outline', () => {
  let wrapper: VueWrapper | undefined
  let sequence = 0
  const frames = new Map<number, FrameRequestCallback>()
  beforeEach(() => {
    frames.clear()
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { const id = ++sequence; frames.set(id, callback); return id })
    vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks(); vi.unstubAllGlobals() })
  async function flushFrame() {
    await nextTick()
    const pending = [...frames.values()]
    frames.clear()
    for (const callback of pending) callback(0)
    await nextTick()
  }
  async function fixture() {
    let outline!: ReturnType<typeof useDocumentOutline>
    wrapper = mount(defineComponent({
      setup() { const root = ref<HTMLElement | null>(null); outline = useDocumentOutline(root, () => 'doc'); return { root } },
      template: '<div ref="root"><div class="doc-col"><div class="doc-body"><div class="tiptap"><h1>First</h1><h2>Second</h2><h2>Column</h2></div></div></div></div>',
    }))
    const root = wrapper.element as HTMLElement
    Object.defineProperty(root, 'scrollHeight', { configurable: true, value: 2000 })
    Object.defineProperty(root, 'clientHeight', { configurable: true, value: 500 })
    vi.spyOn(root, 'getBoundingClientRect').mockImplementation(() => ({ top: 0 } as DOMRect))
    // The third heading begins a new column and is higher than the second.
    const measures = wrapper.findAll('h1,h2').map((item, index) => vi.spyOn(item.element, 'getBoundingClientRect').mockImplementation(() => ({ top: [100, 900, 300][index]! - root.scrollTop } as DOMRect)))
    await flushFrame()
    return { outline, root, measures }
  }
  it('coalesces scrolling and reuses cached geometry, including columns', async () => {
    const { outline, root, measures } = await fixture()
    const before = measures.map(spy => spy.mock.calls.length)
    root.scrollTop = 400
    outline.onScroll(); outline.onScroll(); outline.onScroll()
    expect(frames.size).toBe(1)
    await flushFrame()
    expect(outline.activeIndex.value).toBe(2)
    expect(outline.progress.value).toBe(27)
    expect(measures.map(spy => spy.mock.calls.length)).toEqual(before)
    root.scrollTop = 1000; outline.onScroll(); await flushFrame()
    expect(outline.activeIndex.value).toBe(1)
  })
  it('recalculates explicit navigation and clamps scroll progress', async () => {
    const { outline, root } = await fixture()
    outline.goTo(1)
    expect(root.scrollTop).toBe(876)
    root.scrollTop = 2500; outline.onScroll(); await flushFrame()
    expect(outline.progress.value).toBe(100)
    outline.toTop(); await flushFrame()
    expect(root.scrollTop).toBe(0)
    expect(outline.progress.value).toBe(0)
  })
  it('cancels scheduled layout work on unmount', async () => {
    const { outline } = await fixture()
    outline.refresh(); expect(frames.size).toBe(1)
    wrapper!.unmount(); wrapper = undefined
    expect(frames.size).toBe(0)
  })
  it('includes nested headings in document order and retains stable IDs', () => {
    const headings = richHeadings({ version: 1, markdown: '', document: { type: 'doc', content: [
      { type: 'heading', attrs: { uid: 'intro', level: 1 }, content: [{ type: 'text', text: 'Intro' }] },
      { type: 'columns', content: [{ type: 'column', content: [{ type: 'heading', attrs: { uid: 'nested', level: 2 }, content: [{ type: 'text', text: 'Nested' }] }] }] },
      { type: 'codeBlock', content: [{ type: 'text', text: '# not a heading' }] },
    ] } })
    expect(headings).toEqual([{ id: 'intro', text: 'Intro', level: 1 }, { id: 'nested', text: 'Nested', level: 2 }])
  })
  it('does not invent headings from fenced or indented Markdown code', () => {
    const headings = richHeadings({ version: 1, document: null, markdown: '# Intro\n````md\n# code\n```\n## still code\n````\n~~~\n# code\n~~~\n    # indented\n## Actual ##' })
    expect(headings.map(heading => heading.text)).toEqual(['Intro', 'Actual'])
  })
})

describe('document Markdown export', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })
  it('keeps the draft body verbatim and escapes the title as text', () => {
    const body = '- draft\n\n```html\n<img src=x>\n```'
    expect(documentMarkdown('a\n[b]<c>', body)).toBe(`# a \\[b\\]\\<c\\>\n\n${body}\n`)
  })
  it('downloads a safe filename and releases the temporary object URL', () => {
    vi.useFakeTimers()
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:document')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    let filename = ''
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { filename = this.download })
    downloadDocumentMarkdown('a/b:*?', 'draft')
    expect(filename).toBe('a_b___.md')
    expect(create).toHaveBeenCalledOnce()
    expect(document.querySelector('a[download]')).toBeNull()
    vi.advanceTimersByTime(1000)
    expect(revoke).toHaveBeenCalledWith('blob:document')
  })
})
