import { describe, expect, it } from 'vitest'
import { detectDesktopPlatform, orderedDesktopDownloads } from '@/utils/desktopDownload'

describe('desktop download platform recommendation', () => {
  it('recognizes Windows user agents', () => {
    expect(detectDesktopPlatform('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('windows')
    expect(orderedDesktopDownloads('windows')[0]?.id).toBe('windows')
  })

  it('recognizes both Intel and Apple Silicon macOS browsers', () => {
    expect(detectDesktopPlatform('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')).toBe('macos')
    expect(detectDesktopPlatform('Mozilla/5.0', 'MacIntel')).toBe('macos')
    expect(orderedDesktopDownloads('macos')[0]?.id).toBe('macos')
  })

  it('keeps both downloads visible for an unknown platform', () => {
    expect(detectDesktopPlatform('Mozilla/5.0 (X11; Linux x86_64)')).toBe('other')
    expect(orderedDesktopDownloads('other').map((item) => item.id)).toEqual(['windows', 'macos'])
  })
})
