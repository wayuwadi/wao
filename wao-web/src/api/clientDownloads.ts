export type ClientPlatform = 'android' | 'ios' | 'windows'
export interface ClientDownload {
  platform: ClientPlatform
  version: string
  url: string
  size: number
  sha256?: string
}
const semver = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
const hash = /^[a-f0-9]{64}$/
const origin = 'https://wao.wayuwa.cn'

export function parseMobileManifest(value: unknown): ClientDownload[] {
  const data = value as {
    version?: string
    files?: Record<string, { name?: string; size?: number; sha256?: string }>
  }
  if (!data || typeof data.version !== 'string' || !semver.test(data.version))
    throw new Error('发布版本无效')
  return (['android', 'ios'] as const).map((platform) => {
    const file = data.files?.[platform]
    const name = `WAO-${data.version}-${platform === 'android' ? 'android.apk' : 'ios-unsigned.ipa'}`
    if (
      !file ||
      file.name !== name ||
      !Number.isSafeInteger(file.size) ||
      file.size! <= 0 ||
      !hash.test(file.sha256 ?? '')
    )
      throw new Error('安装包信息不完整')
    return {
      platform,
      version: data.version!,
      url: `/downloads/mobile/${data.version}/${name}`,
      size: file.size!,
      sha256: file.sha256,
    }
  })
}
export function parseWindowsManifest(value: unknown): Omit<ClientDownload, 'size'> {
  const data = value as {
    version?: string
    platforms?: Record<string, { url?: string; signature?: string }>
  }
  if (!data || typeof data.version !== 'string' || !semver.test(data.version))
    throw new Error('发布版本无效')
  const platform = data.platforms?.['windows-x86_64']
  const url = `/downloads/desktop/client/WAO_${data.version}_windows-x64-setup.exe`
  if (
    platform?.url !== origin + url ||
    typeof platform.signature !== 'string' ||
    platform.signature.length < 20
  )
    throw new Error('安装包信息不完整')
  return { platform: 'windows', version: data.version, url }
}
// 使用基础 AbortController，兼容 iOS 15；读取 JSON 正文也计入超时。
async function withTimeout<T>(request: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 12000)
  try {
    return await request(controller.signal)
  } finally {
    clearTimeout(timer)
  }
}
async function manifest(path: string): Promise<unknown | null> {
  return withTimeout(async (signal) => {
    const response = await fetch(path, { cache: 'no-store', signal })
    if (response.status === 404) return null
    if (!response.ok || !response.headers.get('content-type')?.includes('application/json'))
      throw new Error('暂时无法读取发布信息')
    return response.json()
  })
}
async function verifyFile(
  file: Omit<ClientDownload, 'size'> & { size?: number },
): Promise<ClientDownload> {
  return withTimeout(async (signal) => {
    const response = await fetch(file.url, { method: 'HEAD', cache: 'no-store', signal })
    const size = Number(response.headers.get('content-length'))
    const type = response.headers.get('content-type') ?? ''
    if (
      !response.ok ||
      !Number.isSafeInteger(size) ||
      size <= 0 ||
      /text\/|json/i.test(type) ||
      (file.size && file.size !== size)
    )
      throw new Error('安装包暂时不可用')
    return { ...file, size }
  })
}
export async function fetchMobileDownloads(): Promise<ClientDownload[]> {
  const data = await manifest('/downloads/mobile/latest.json')
  return data === null ? [] : Promise.all(parseMobileManifest(data).map(verifyFile))
}
export async function fetchWindowsDownload(): Promise<ClientDownload[]> {
  const data = await manifest('/downloads/desktop/client/latest.json')
  return data === null ? [] : [await verifyFile(parseWindowsManifest(data))]
}
export function detectClientPlatform(
  userAgent: string,
  platform: string,
  touchPoints = 0,
): ClientPlatform | 'macos' | 'other' {
  if (/android/i.test(userAgent)) return 'android'
  if (/iphone|ipad|ipod/i.test(userAgent) || (platform === 'MacIntel' && touchPoints > 1))
    return 'ios'
  if (/win/i.test(platform)) return 'windows'
  if (/mac/i.test(platform)) return 'macos'
  return 'other'
}
