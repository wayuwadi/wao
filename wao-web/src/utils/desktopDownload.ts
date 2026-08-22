export type DesktopPlatform = 'windows' | 'macos' | 'other'

export interface DesktopDownload {
  id: Exclude<DesktopPlatform, 'other'>
  label: string
  detail: string
  fileName: string
  href: string
}

export const DESKTOP_DOWNLOADS: DesktopDownload[] = [
  {
    id: 'windows',
    label: 'Windows',
    detail: 'Windows 10/11 · x64 · 每用户安装',
    fileName: 'WAO-Pet-windows-x64-setup.exe',
    href: '/downloads/desktop/WAO-Pet-windows-x64-setup.exe',
  },
  {
    id: 'macos',
    label: 'macOS',
    detail: 'macOS 10.15+ · Intel 与 Apple Silicon',
    fileName: 'WAO-Pet-macos-universal.dmg',
    href: '/downloads/desktop/WAO-Pet-macos-universal.dmg',
  },
]

export function detectDesktopPlatform(userAgent: string, platform = ''): DesktopPlatform {
  const value = `${userAgent} ${platform}`.toLowerCase()
  if (value.includes('windows') || value.includes('win32') || value.includes('win64')) return 'windows'
  if (value.includes('macintosh') || value.includes('mac os') || value.includes('macintel')) return 'macos'
  return 'other'
}

export function orderedDesktopDownloads(platform: DesktopPlatform): DesktopDownload[] {
  if (platform === 'other') return [...DESKTOP_DOWNLOADS]
  return [...DESKTOP_DOWNLOADS].sort((left, right) => {
    if (left.id === platform) return -1
    if (right.id === platform) return 1
    return left.id.localeCompare(right.id)
  })
}
