/* Only immutable public assets and existing public image GETs are eligible. */
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
  }
})

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
