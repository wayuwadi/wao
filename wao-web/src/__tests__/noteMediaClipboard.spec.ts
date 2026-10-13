import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyOriginalImage, noteMediaCopyText } from '@/utils/noteMediaClipboard'
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); document.body.innerHTML = '' })
describe('小记图文剪贴板', () => {
  it('网络读取未完成时已在点击调用内发起剪贴板写入', async () => {
    let resolveFetch!: (value: unknown) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise(resolve => { resolveFetch = resolve })))
    class Item { constructor(public values: Record<string, Promise<Blob>>) {} }
    vi.stubGlobal('ClipboardItem', Item)
    const write = vi.fn(async (items: Item[]) => { await items[0]!.values['image/png'] })
    vi.stubGlobal('navigator', { clipboard: { write } })
    const copying = copyOriginalImage('/api/images/slow/raw')
    expect(write).toHaveBeenCalledOnce()
    resolveFetch({ ok: true, blob: async () => new Blob(['png'], { type: 'image/png' }) })
    await copying
  })

  it('已加载完整图片按自然尺寸复制，不再发起网络请求', async () => {
    const image = document.createElement('img')
    image.src = '/api/images/loaded/raw'; image.style.width = '80px'
    Object.defineProperties(image, { complete: { value: true }, naturalWidth: { value: 3200 }, naturalHeight: { value: 1800 } })
    document.body.append(image)
    const draw = vi.fn(), canvas = document.createElement('canvas'), png = new Blob(['pixels'], { type: 'image/png' })
    vi.spyOn(canvas, 'getContext').mockReturnValue({ drawImage: draw } as unknown as CanvasRenderingContext2D)
    vi.spyOn(canvas, 'toBlob').mockImplementation(callback => callback(png))
    const create = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation(tag => tag === 'canvas' ? canvas : create(tag))
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    class Item { constructor(public values: Record<string, Promise<Blob>>) {} }
    vi.stubGlobal('ClipboardItem', Item)
    vi.stubGlobal('navigator', { clipboard: { write: async (items: Item[]) => { expect(await items[0]!.values['image/png']).toBe(png) } } })
    await copyOriginalImage('/api/images/loaded/raw')
    expect(fetch).not.toHaveBeenCalled()
    expect([canvas.width, canvas.height]).toEqual([3200, 1800])
    expect(draw).toHaveBeenCalledWith(image, 0, 0)
  })

  it('已加载预览图不能冒充原图，仍读取完整地址', async () => {
    const image = document.createElement('img')
    image.src = '/api/images/loaded/preview'; image.dataset.fullSrc = '/api/images/loaded/raw'
    Object.defineProperties(image, { complete: { value: true }, naturalWidth: { value: 400 }, naturalHeight: { value: 200 } })
    document.body.append(image)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['original'], { type: 'image/png' }) }))
    class Item { constructor(public values: Record<string, Promise<Blob>>) {} }
    vi.stubGlobal('ClipboardItem', Item)
    vi.stubGlobal('navigator', { clipboard: { write: async (items: Item[]) => { await items[0]!.values['image/png'] } } })
    await copyOriginalImage('/api/images/loaded/raw')
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/images/loaded/raw', { credentials: 'same-origin' })
  })

  it('浏览器拒绝复制时返回可理解的原因，不报告已成功', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['png'], { type: 'image/png' }) }))
    class Item { constructor(public values: Record<string, Promise<Blob>>) {} }
    vi.stubGlobal('ClipboardItem', Item)
    vi.stubGlobal('navigator', { clipboard: { write: vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError')) } })
    await expect(copyOriginalImage('/api/images/loaded/raw')).rejects.toThrow('浏览器未允许复制图片')
  })
  it('位图解码不支持的图片回退到图片元素，保留完整尺寸并回收临时地址', async () => {
    const canvas = document.createElement('canvas'), png = new Blob(['png'], { type: 'image/png' })
    vi.spyOn(canvas, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D)
    vi.spyOn(canvas, 'toBlob').mockImplementation(callback => callback(png))
    const create = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation(tag => tag === 'canvas' ? canvas : create(tag))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['svg'], { type: 'image/svg+xml' }) }))
    vi.stubGlobal('createImageBitmap', vi.fn().mockRejectedValue(new Error('unsupported')))
    const decode = vi.fn().mockResolvedValue(undefined), revoke = vi.fn(), url = URL
    vi.stubGlobal('URL', class extends url { static createObjectURL = vi.fn(() => 'blob:qa-svg'); static revokeObjectURL = revoke })
    vi.stubGlobal('Image', class { src = ''; naturalWidth = 960; naturalHeight = 640; decode = decode })
    class Item { constructor(public values: Record<string, Promise<Blob>>) {} }
    vi.stubGlobal('ClipboardItem', Item)
    vi.stubGlobal('navigator', { clipboard: { write: async (items: Item[]) => { expect(await items[0]!.values['image/png']).toBe(png) } } })
    await copyOriginalImage('/api/images/svg/raw')
    expect([canvas.width, canvas.height]).toEqual([960, 640])
    expect(decode).toHaveBeenCalledOnce()
    expect(revoke).toHaveBeenCalledExactlyOnceWith('blob:qa-svg')
  })
  it('正文保留图号、列表和链接，代码中的图片语法不误识别', () => {
    const text = noteMediaCopyText({ version: 1, document: null, markdown: '# 标题\n\n第一段\n\n![原图](/api/images/qa/raw)\n\n- 条目\n\n[参考](https://example.com)\n\n`![不是图片](/fake)`' })
    expect(text).toMatch(/标题\s+第一段/)
    expect(text).toContain('[图 1：原图]')
    expect(text).toContain('• 条目')
    expect(text).toContain('参考 (https://example.com)')
    expect(text).toContain('![不是图片](/fake)')
  })
  it('原图用独立 PNG 剪贴板项，不混入文字、HTML 或预览缩略图', async () => {
    const blob = new Blob(['original'], { type: 'image/png' })
    const write = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { write } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => blob }))
    class Item { constructor(public values: Record<string, Promise<Blob>>) {} }
    vi.stubGlobal('ClipboardItem', Item)
    await copyOriginalImage('/api/images/qa/raw')
    expect(fetch).toHaveBeenCalledWith('/api/images/qa/raw', { credentials: 'same-origin' })
    const item = write.mock.calls[0]![0][0] as Item
    expect(Object.keys(item.values)).toEqual(['image/png'])
    expect(await item.values['image/png']).toBe(blob)
  })
  it('读取失败返回失败，不写空图片冒充成功', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    class Item { constructor(public values: Record<string, Promise<Blob>>) {} }
    vi.stubGlobal('ClipboardItem', Item)
    const write = vi.fn(async (items: Item[]) => { await items[0]!.values['image/png'] })
    vi.stubGlobal('navigator', { clipboard: { write } })
    await expect(copyOriginalImage('/api/images/missing/raw')).rejects.toThrow('原图读取失败')
  })
  it('非 PNG 按原始像素转换，保留透明通道且释放位图', async () => {
    const close = vi.fn(), draw = vi.fn()
    const png = new Blob(['png'], { type: 'image/png' })
    const canvas = document.createElement('canvas')
    vi.spyOn(canvas, 'getContext').mockReturnValue({ drawImage: draw } as unknown as CanvasRenderingContext2D)
    vi.spyOn(canvas, 'toBlob').mockImplementation(callback => callback(png))
    const create = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation(tag => tag === 'canvas' ? canvas : create(tag))
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue({ width: 3200, height: 1800, close }))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['jpeg'], { type: 'image/jpeg' }) }))
    class Item { constructor(public values: Record<string, Promise<Blob>>) {} }
    vi.stubGlobal('ClipboardItem', Item)
    vi.stubGlobal('navigator', { clipboard: { write: async (items: Item[]) => { expect(await items[0]!.values['image/png']).toBe(png) } } })
    await copyOriginalImage('/api/images/full/raw')
    expect([canvas.width, canvas.height]).toEqual([3200, 1800])
    expect(draw).toHaveBeenCalledWith(expect.objectContaining({ width: 3200 }), 0, 0)
    expect(close).toHaveBeenCalledOnce()
  })
})
