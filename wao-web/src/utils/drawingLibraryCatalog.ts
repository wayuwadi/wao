const catalog = 'https://libraries.excalidraw.com'
const maxBytes = 8 * 1024 * 1024

export function officialDrawingLibraryUrl(value: string) {
  try {
    const url = new URL(value)
    if (url.origin === catalog && !url.username && !url.password && !url.search && !url.hash &&
        /^\/libraries\/[^/]+\/[^/]+\.excalidrawlib$/i.test(url.pathname)) return url
  } catch { /* Ignore an invalid install URL. */ }
  return null
}

export async function loadOfficialDrawingLibrary(url: URL, signal: AbortSignal) {
  const response = await fetch(url, { credentials: 'omit', redirect: 'error', signal })
  if (!response.ok || !response.body) throw new Error('官方素材下载失败，请从素材库重新添加。')
  const reader = response.body.getReader()
  const chunks: ArrayBuffer[] = []
  let size = 0
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > maxBytes) throw new Error('这套素材超过素材库容量。')
      chunks.push(new Uint8Array(value).buffer)
    }
  } finally {
    await reader.cancel()
  }
  const { loadLibraryFromBlob } = await import('@excalidraw/excalidraw')
  return loadLibraryFromBlob(new Blob(chunks), 'published')
}
