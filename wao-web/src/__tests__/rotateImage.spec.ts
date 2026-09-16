import { afterEach, describe, expect, it, vi } from 'vitest'
import { rotateImage } from '@/utils/rotateImage'
describe('旋转真实图片像素', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
  it.each([true, false])('交换画布宽高并按指定方向旋转：%s', async clockwise => {
    const bitmap = { width: 120, height: 80, close: vi.fn() }
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap))
    const context = { translate: vi.fn(), rotate: vi.fn(), drawImage: vi.fn() }
    let canvas: HTMLCanvasElement
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function (this: HTMLCanvasElement) { canvas = this; return context as never })
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => callback(new Blob(['png'], { type: 'image/png' })))
    const result = await rotateImage(new Blob(['original']), clockwise)
    expect([canvas!.width, canvas!.height]).toEqual([80, 120])
    expect(context.rotate).toHaveBeenCalledWith(clockwise ? Math.PI / 2 : -Math.PI / 2)
    expect(context.drawImage).toHaveBeenCalledWith(bitmap, -60, -40)
    expect(result.type).toBe('image/png')
    expect(bitmap.close).toHaveBeenCalledOnce()
  })
})
