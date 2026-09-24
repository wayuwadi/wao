/* Public assets and explicit authenticated media GETs; no write replay or HTML hosting. */
const ORIGINS = new Set(['https://linux1.wayuwa.cn:9443', 'https://linux2.wayuwa.cn:9443'])
const MAX_ASSET_BYTES = 32 * 1024 * 1024
let routing = null
let routingUntil = 0
let routingPending = null
const manifests = new Map()
const cooldown = new Map()

self.addEventListener('install', (event) => event.waitUntil(self.skipWaiting()))
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

async function boundedFetch(url, options, timeout, maxBytes = MAX_ASSET_BYTES) {
  const controller = new AbortController()
  const started = Date.now()
  // An offline listener must not consume the whole file-download budget.
  let timer = setTimeout(() => controller.abort(), Math.min(timeout, 1500))
  try {
    const response = await fetch(url, { ...options, signal: controller.signal })
    clearTimeout(timer)
    timer = setTimeout(() => controller.abort(), Math.max(1, timeout - (Date.now() - started)))
    if (!response.ok || response.redirected || response.type === 'opaque') throw new Error('Unavailable asset')
    if (Number(response.headers.get('content-length')) > maxBytes) throw new Error('Asset too large')
    const reader = response.body.getReader()
    const parts = []
    let size = 0
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.length
      if (size > maxBytes) { await reader.cancel(); throw new Error('Asset too large') }
      parts.push(value)
    }
    const bytes = new Uint8Array(size)
    let offset = 0
    for (const part of parts) { bytes.set(part, offset); offset += part.length }
    return { response, bytes }
  } finally { clearTimeout(timer); controller.abort() }
}

async function getRouting() {
  if (Date.now() < routingUntil) return routing
  if (!routingPending) routingPending = (async () => {
    try {
      const { bytes } = await boundedFetch('/_edge/routing.json', { credentials: 'omit', cache: 'no-store' }, 1500)
      const value = JSON.parse(new TextDecoder().decode(bytes))
      routing = value.schema === 1 ? value : null
    } catch { routing = null }
    routingUntil = Date.now() + 30_000
    routingPending = null
    return routing
  })()
  return routingPending
}

function assetPath(request) {
  const url = new URL(request.url)
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.search || request.headers.has('range')) return null
  const match = /^\/releases\/([0-9a-f]{40})\/(assets\/[A-Za-z0-9_./-]+)$/.exec(url.pathname)
  if (!match || match[2].includes('..') || match[2].includes('/.')) return null
  return { release: match[1], name: match[2], path: url.pathname }
}

async function getManifest(origin, release) {
  const key = `${origin}/${release}`
  if (!manifests.has(key)) {
    const pending = boundedFetch(`${origin}/releases/${release}/release-manifest.json`,
      { credentials: 'omit', mode: 'cors', redirect: 'error' }, 2500).then(({ bytes }) => {
      const value = JSON.parse(new TextDecoder().decode(bytes))
      if (value.release !== release || !value.files) throw new Error('Wrong release')
      return value
    }).catch((error) => { manifests.delete(key); throw error })
    if (manifests.size >= 8) manifests.delete(manifests.keys().next().value)
    manifests.set(key, pending)
  }
  return manifests.get(key)
}

async function routeAsset(request, asset) {
  const config = await getRouting()
  if (!config?.enabled || !config.staticEnabled || config.forceCloud
      || !Array.isArray(config.allowedReleases) || !config.allowedReleases.includes(asset.release)) return fetch(request)
  const nodes = Array.isArray(config.nodes) ? config.nodes : []
  for (const node of nodes.slice(0, 2)) {
    if (!node.enabled || !ORIGINS.has(node.origin) || (cooldown.get(node.origin) || 0) > Date.now()) continue
    try {
      const manifest = await getManifest(node.origin, asset.release)
      const expected = manifest.files[asset.name]
      if (!expected || !/^[0-9a-f]{64}$/.test(expected.sha256) || expected.size > MAX_ASSET_BYTES) throw new Error('Unlisted asset')
      const { response, bytes } = await boundedFetch(`${node.origin}${asset.path}`,
        { credentials: 'omit', mode: 'cors', redirect: 'error', cache: request.cache }, 12_000)
      if (bytes.length !== expected.size) throw new Error('Asset length mismatch')
      const actual = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), (b) => b.toString(16).padStart(2, '0')).join('')
      if (actual !== expected.sha256) throw new Error('Asset checksum mismatch')
      // A fresh Response keeps relative imports on the original main-site URL.
      // Forward no cookies, redirects, compressed length or node response URL.
      return new Response(bytes, { status: 200, headers: {
        'Content-Type': response.headers.get('content-type') || 'application/octet-stream',
        'Cache-Control': 'public, max-age=31536000, immutable',
        'X-WAO-Static-Route': new URL(node.origin).hostname,
      } })
    } catch { cooldown.set(node.origin, Date.now() + 60_000) }
  }
  return fetch(request)
}

self.addEventListener('fetch', (event) => {
  const asset = assetPath(event.request)
  if (asset) event.respondWith(routeAsset(event.request, asset))
  else {
    const image = publicImagePath(event.request)
    if (image) event.respondWith(routePublicImage(event.request, image))
    else {
      const file = privateFilePath(event.request)
      if (file) event.respondWith(routePrivateFile(event.request, file))
    }
  }
})

function privateFilePath(request) {
  const url = new URL(request.url)
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.search
      || !['', 'document', 'audio', 'video'].includes(request.destination)) return null
  return /^\/api\/files\/[A-Za-z0-9_-]+\/(?:download|stream)$/.test(url.pathname) ? url.pathname : null
}

async function mediaSessionJson(url, options = {}, timeout = 4000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    const response = await fetch(url, { ...options, credentials: 'include', cache: 'no-store', redirect: 'error', signal: controller.signal })
    if (!response.ok) throw Object.assign(new Error('Media session unavailable'), { auth: response.status === 401 || response.status === 403, status: response.status })
    return await response.json()
  } finally { clearTimeout(timer) }
}

function privateMediaResponse(response, controller, request, origin) {
  const headers = new Headers({ 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' })
  headers.set('X-WAO-Media-Route', new URL(origin).hostname)
  for (const name of ['Content-Type', 'Content-Disposition', 'Content-Range', 'Accept-Ranges']) {
    const value = response.headers.get(name)
    if (value) headers.set(name, value)
  }
  // Stream bytes without materializing a 50 MiB attachment in worker memory.
  // Once response headers have been delivered, failure ends this stream; never replay.
  const reader = response.body?.getReader()
  let size = 0
  const timer = setTimeout(() => controller.abort(), 120_000)
  const aborted = () => controller.abort()
  request.signal?.addEventListener('abort', aborted, { once: true })
  const cleanup = () => { clearTimeout(timer); request.signal?.removeEventListener('abort', aborted) }
  if (!reader) { cleanup(); return new Response(null, { status: response.status, headers }) }
  const body = new ReadableStream({
    async pull(stream) {
      try {
        const result = await reader.read()
        if (result.done) { cleanup(); stream.close(); return }
        size += result.value.byteLength
        if (size > 52 * 1024 * 1024) throw new Error('Media response exceeds upload limit')
        stream.enqueue(result.value)
      } catch (error) { cleanup(); controller.abort(); stream.error(error) }
    },
    cancel(reason) { cleanup(); controller.abort(); return reader.cancel(reason) },
  })
  return new Response(body, { status: response.status, headers })
}

async function routePrivateFile(request, path) {
  const config = await getRouting()
  if (config?.enabled !== true || config.apiEnabled !== true || config.mediaEnabled !== true || config.forceCloud !== false) return fetch(request)
  for (const node of (Array.isArray(config.nodes) ? config.nodes : []).slice(0, 2)) {
    if (node.enabled !== true || !ORIGINS.has(node.origin) || (cooldown.get(node.origin) || 0) > Date.now()) continue
    let controller
    try {
      // Check connectivity cheaply before the longer database-backed file read.
      await mediaSessionJson(`${node.origin}/api/edge/ready`, {}, 1500)
      // Derive authority afresh for every media request. Never retain a user's
      // ticket/CSRF/account in worker storage across logout or account switching.
      const csrf = await mediaSessionJson('/api/edge/csrf')
      const ticket = await mediaSessionJson('/api/edge/tickets', { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-WAO-CSRF': csrf.data.csrf },
        body: JSON.stringify({ targetOrigin: node.origin }) })
      await mediaSessionJson(`${node.origin}/api/edge/exchange`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ticket: ticket.data.ticket }) })
      await mediaSessionJson(`${node.origin}/api/edge/session`)
      controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), 30_000)
      let response
      try {
        const headers = new Headers()
        if (request.headers.has('range')) headers.set('Range', request.headers.get('range'))
        response = await fetch(`${node.origin}${path}`, { credentials: 'include', mode: 'cors', redirect: 'error',
          cache: 'no-store', headers, signal: controller.signal })
      } finally { clearTimeout(timer) }
      if ([502, 503, 504].includes(response.status)) throw new Error('Node unavailable')
      if (response.ok) {
        const type = response.headers.get('content-type') || ''
        if (path.endsWith('/download') && !/^attachment(?:;|$)/i.test(response.headers.get('content-disposition') || '')) throw new Error('Missing safe attachment disposition')
        if (path.endsWith('/stream') && !/^(audio|video)\//i.test(type)) throw new Error('Not playable media')
      }
      return privateMediaResponse(response, controller, request, node.origin)
    } catch (error) {
      controller?.abort()
      if (error.auth) return new Response(JSON.stringify({ code: error.status, message: '登录状态已过期，请重新登录' }),
        { status: error.status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'private, no-store' } })
      cooldown.set(node.origin, Date.now() + 60_000)
    }
  }
  return fetch(request)
}

function publicImagePath(request) {
  const url = new URL(request.url)
  // In-page image loads only: never intercept navigation, files, uploads or API fetches.
  if (request.method !== 'GET' || request.destination !== 'image' || url.origin !== self.location.origin
      || url.search || request.headers.has('range')) return null
  return /^\/api\/images\/[A-Za-z0-9_-]+\/raw$/.test(url.pathname) ? url.pathname : null
}

async function routePublicImage(request, path) {
  const config = await getRouting()
  if (config?.enabled !== true || config.publicImagesEnabled !== true || config.forceCloud !== false) return fetch(request)
  const nodes = Array.isArray(config.nodes) ? config.nodes : []
  for (const node of nodes.slice(0, 2)) {
    if (node.enabled !== true || !ORIGINS.has(node.origin) || (cooldown.get(node.origin) || 0) > Date.now()) continue
    try {
      const { response, bytes } = await boundedFetch(`${node.origin}${path}`,
        { credentials: 'omit', mode: 'cors', redirect: 'error' }, 12_000, 10 * 1024 * 1024)
      const type = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase()
      if (!['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/svg+xml', 'image/bmp'].includes(type)
          || bytes.length === 0) throw new Error('Not an image')
      return new Response(bytes, { status: 200, headers: {
        'Content-Type': type,
        'Cache-Control': 'public, max-age=31536000, immutable',
        'X-Content-Type-Options': 'nosniff',
        'X-WAO-Image-Route': new URL(node.origin).hostname,
      } })
    } catch { cooldown.set(node.origin, Date.now() + 60_000) }
  }
  return fetch(request)
}
