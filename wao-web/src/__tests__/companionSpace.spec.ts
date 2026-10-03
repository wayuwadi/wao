import { afterEach, expect, it, vi } from 'vitest'
import { companionSpace } from '@/components/companions/companionSpace'
afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); vi.unstubAllGlobals() })
it('拥挤控件不遮挡；被抽屉遮住的底层控件不误拦截空白', () => {
  document.body.innerHTML = '<input><div id="drawer"></div>'
  const input = document.querySelector('input')!, drawer = document.querySelector('#drawer')!
  vi.spyOn(input, 'getClientRects').mockReturnValue([new DOMRect(0,0,900,700)] as unknown as DOMRectList)
  vi.spyOn(input, 'getBoundingClientRect').mockReturnValue(new DOMRect(0,0,900,700))
  vi.stubGlobal('innerWidth', 900); vi.stubGlobal('innerHeight', 700)
  const top = vi.fn(() => input as Element)
  vi.stubGlobal('document', document)
  Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: top })
  const anchor = { left: 400, right: 460, top: 300, bottom: 332 }
  expect(companionSpace(anchor)).toBeUndefined()
  top.mockReturnValue(drawer)
  expect(companionSpace(anchor)).toEqual({ left: '336px', top: '276px' })
})
