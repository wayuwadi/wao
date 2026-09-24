import { test } from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'
import { webcrypto } from 'node:crypto'
import { edgeBootstrapHtml } from './edge-build.mjs'

const sha = 'a'.repeat(40)
const origin = 'https://wao.wayuwa.cn'
const node = 'https://linux1.wayuwa.cn:9443'
const path = `/releases/${sha}/assets/app.js`
const asset = new TextEncoder().encode('export const value = 42')
const digest = Buffer.from(await webcrypto.subtle.digest('SHA-256', asset)).toString('hex')
const manifest = { release: sha, files: { 'assets/app.js': { size: asset.length, sha256: digest } } }
const config = { schema: 1, enabled: true, staticEnabled: true, forceCloud: false, allowedReleases: [sha], nodes: [{ origin: node, enabled: true }] }

function privateMediaHarness(extra = {}) {
  let account = 'account-a'
  const h = harness({
    config: { ...config, apiEnabled: true, mediaEnabled: true, forceCloud: false }, ...extra,
    respond: async (url, options) => {
      const custom = await extra.respond?.(url, options)
      if (custom) return custom
      if (url === '/api/edge/csrf') return Response.json({ data: { csrf: account } })
      if (url === '/api/edge/tickets') return Response.json({ data: { ticket: 'single-use' } })
      if (/\/api\/edge\/(ready|exchange|session)$/.test(url)) return Response.json({ data: {} })
      if (url === node + '/api/files/sample/download') return new Response(account, {
        headers: { 'Content-Type': 'text/plain', 'Content-Disposition': 'attachment; filename=test.txt', 'Set-Cookie': 'do-not-forward' },
      })
    },
  })
  return { ...h, switchAccount: value => { account = value } }
}

test('private downloads get fresh root authority per request and never cache identity or cookies', async () => {
  const h = privateMediaHarness()
  const first = await h.request(origin + '/api/files/sample/download')
  assert.equal(await first.text(), 'account-a')
  assert.equal(first.headers.get('cache-control'), 'private, no-store')
  assert.equal(first.headers.get('set-cookie'), null)
  assert.match(first.headers.get('content-disposition'), /^attachment/)
  h.switchAccount('account-b')
  assert.equal(await (await h.request(origin + '/api/files/sample/download')).text(), 'account-b')
  const tickets = h.calls.filter(call => call.url === '/api/edge/tickets')
  assert.equal(tickets.length, 2)
  assert.equal(tickets[1].options.headers['X-WAO-CSRF'], 'account-b')
  assert.equal(h.calls.filter(call => call.url === origin + '/api/files/sample/download').length, 0)
})

test('private auth rejection stops immediately; ticket URLs and media writes stay untouched', async () => {
  const h = privateMediaHarness({ respond: url => url === '/api/edge/csrf' ? new Response('', { status: 401 }) : null })
  const response = await h.request(origin + '/api/files/sample/download')
  assert.equal(response.status, 401)
  assert.equal(h.calls.filter(call => call.url.startsWith(node) && !call.url.endsWith('/ready')).length, 0)
  assert.equal(h.request(origin + '/api/files/sample/download?ticket=one'), undefined)
  assert.equal(h.request(origin + '/api/files/sample/download', { method: 'POST' }), undefined)
})

test('media ranges preserve status and range headers without forwarding cookies', async () => {
  const h = privateMediaHarness({ respond: (url, options) => {
    if (!url.endsWith('/stream')) return null
    assert.equal(options.headers.get('range'), 'bytes=2-4')
    return new Response('234', { status: 206, headers: { 'Content-Type': 'video/mp4',
      'Content-Range': 'bytes 2-4/10', 'Accept-Ranges': 'bytes', 'Content-Length': '3', 'Set-Cookie': 'private' } })
  } })
  const response = await h.request(origin + '/api/files/sample/stream', { headers: { Range: 'bytes=2-4' }, destination: 'video' })
  assert.equal(response.status, 206)
  assert.equal(response.headers.get('content-range'), 'bytes 2-4/10')
  assert.equal(response.headers.get('set-cookie'), null)
  assert.equal(await response.text(), '234')
})

test('unsafe inline downloads fall back once; body failures after headers never replay', async () => {
  const unsafe = privateMediaHarness({ respond: url => url === node + '/api/files/sample/download'
    ? new Response('<script>bad</script>', { headers: { 'Content-Type': 'text/html' } }) : null })
  assert.equal(await (await unsafe.request(origin + '/api/files/sample/download')).text(), 'cloud same release')
  assert.equal(unsafe.calls.filter(call => call.url === origin + '/api/files/sample/download').length, 1)
  const broken = privateMediaHarness({ respond: url => url === node + '/api/files/sample/download'
    ? new Response(new ReadableStream({ pull(controller) { controller.error(new Error('lost stream')) } }),
      { headers: { 'Content-Disposition': 'attachment; filename=test.txt' } }) : null })
  const response = await broken.request(origin + '/api/files/sample/download')
  await assert.rejects(response.text(), /lost stream/)
  assert.equal(broken.calls.filter(call => call.url === origin + '/api/files/sample/download').length, 0)
})

function harness(overrides = {}) {
  const listeners = new Map()
  const calls = []
  const self = { location: { origin }, addEventListener: (name, fn) => listeners.set(name, fn) }
  const fetch = async (input, options) => {
    const url = typeof input === 'string' ? input : input.url
    calls.push({ url, options })
    if (url === '/_edge/routing.json') return Response.json(overrides.config || config)
    if (url.endsWith('/release-manifest.json')) return Response.json(overrides.manifest || manifest)
    if (overrides.respond) {
      const response = await overrides.respond(url, options)
      if (response) return response
    }
    if (url.startsWith(node)) {
      if (overrides.nodeFailure) throw new Error('Network unavailable')
      return new Response(overrides.corrupt ? 'wrong' : (overrides.body ?? asset), { headers: { 'content-type': overrides.contentType || 'text/javascript', 'set-cookie': 'must-not-forward' } })
    }
    return new Response('cloud same release', { headers: { 'content-type': 'text/javascript' } })
  }
  vm.runInNewContext(readFileSync(new URL('../public/edge-static-worker.js', import.meta.url), 'utf8'),
    { self, fetch, URL, Response, Headers, ReadableStream, Uint8Array, TextDecoder, AbortController, crypto: webcrypto, setTimeout, clearTimeout })
  return {
    calls,
    request(url = origin + path, options = {}) {
      let pending
      const request = new Request(url, options)
      if (options.destination) Object.defineProperty(request, 'destination', { value: options.destination })
      listeners.get('fetch')({ request, respondWith(value) { pending = value } })
      return pending
    },
  }
}

test('public asset goes directly to a node without cookies and retains same-origin relative imports', async () => {
  const h = harness()
  const response = await h.request()
  assert.equal(await response.text(), new TextDecoder().decode(asset))
  assert.equal(response.url, '')
  assert.equal(response.headers.get('set-cookie'), null)
  assert.equal(response.headers.get('x-wao-static-route'), 'linux1.wayuwa.cn')
  assert.equal(h.calls.at(-1).options.credentials, 'omit')
})

test('public in-page images route anonymously while the original URL remains unchanged', async () => {
  const h = harness({ config: { ...config, publicImagesEnabled: true }, contentType: 'image/png' })
  const response = await h.request(origin + '/api/images/img123/raw', { destination: 'image' })
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), asset)
  assert.equal(response.headers.get('x-wao-image-route'), 'linux1.wayuwa.cn')
  assert.equal(response.headers.get('set-cookie'), null)
  assert.equal(response.url, '')
  assert.equal(h.calls.at(-1).options.credentials, 'omit')
})

test('image rollout defaults off; private files, navigation, query tickets and uploads stay untouched', async () => {
  const h = harness()
  const image = origin + '/api/images/img123/raw'
  assert.equal(await (await h.request(image, { destination: 'image' })).text(), 'cloud same release')
  assert.equal(h.calls.some(c => c.url.startsWith(node)), false)
  for (const [url, options] of [[image, {}], [image, { destination: 'document' }],
    [origin + '/api/files/file123/download', { destination: 'image' }],
    [image + '?ticket=private', { destination: 'image' }],
    [image, { destination: 'image', method: 'POST', body: 'upload' }],
    [image, { destination: 'image', headers: { Range: 'bytes=0-10' } }]]) {
    assert.equal(h.request(url, options), undefined)
  }
})

test('image network failure, HTML response, empty or oversized body fall back exactly once', async () => {
  for (const change of [{ nodeFailure: true }, { contentType: 'text/html' }, { body: '' },
    { body: new Uint8Array(10 * 1024 * 1024 + 1) }]) {
    const h = harness({ config: { ...config, publicImagesEnabled: true }, contentType: 'image/png', ...change })
    const url = origin + '/api/images/img123/raw'
    assert.equal(await (await h.request(url, { destination: 'image' })).text(), 'cloud same release')
    assert.equal(h.calls.filter(c => c.url === url).length, 1)
  }
})

test('network failure, corrupt bytes and wrong release fall back to the exact original request', async () => {
  for (const options of [{ nodeFailure: true }, { corrupt: true }, { manifest: { ...manifest, release: 'b'.repeat(40) } }]) {
    const h = harness(options)
    assert.equal(await (await h.request()).text(), 'cloud same release')
    assert.equal(h.calls.at(-1).url, origin + path)
  }
})

test('force-cloud, disabled node, unknown release and untrusted origin never contact a node', async () => {
  for (const change of [{ forceCloud: true }, { enabled: false }, { staticEnabled: false }, { allowedReleases: [] },
    { nodes: [{ origin: 'https://attacker.invalid', enabled: true }] }, { nodes: [{ origin: node, enabled: false }] }]) {
    const h = harness({ config: { ...config, ...change } })
    assert.equal(await (await h.request()).text(), 'cloud same release')
    assert.equal(h.calls.length, 2)
  }
})

test('API, navigation, credentials in queries, range and writes are never intercepted or retried', () => {
  const h = harness()
  for (const [url, options] of [[origin + '/api/tasks', {}], [origin + '/tasks', {}],
    [origin + path + '?ticket=private', {}], [origin + path, { method: 'POST', body: 'change' }],
    [origin + path, { headers: { Range: 'bytes=0-10' } }], [node + path, {}]]) {
    assert.equal(h.request(url, options), undefined)
  }
  assert.equal(h.calls.length, 0)
})

test('failed node is cooled down and not retried for each chunk', async () => {
  const h = harness({ nodeFailure: true })
  await h.request()
  const count = h.calls.filter((c) => c.url.startsWith(node)).length
  await h.request()
  assert.equal(h.calls.filter((c) => c.url.startsWith(node)).length, count)
})

test('build loader delays entry and styles while preserving main-site icons', () => {
  const html = `<html><link rel="icon" href="/releases/${sha}/brand/icon.png"><script type="module" crossorigin src="/releases/${sha}/assets/app.js"></script><link rel="modulepreload" href="/releases/${sha}/assets/dep.js"><link rel="stylesheet" crossorigin href="/releases/${sha}/assets/app.css"></html>`
  const output = edgeBootstrapHtml(html)
  assert.match(output, /src="\/edge-bootstrap.js"/)
  assert.match(output, /href="\/brand\/icon.png"/)
  assert.doesNotMatch(output, /rel="(?:stylesheet|modulepreload)"/)
  assert.match(output, /data-styles="\[&quot;\/releases\//)
  assert.throws(() => edgeBootstrapHtml('<script type="module" src="/assets/unversioned.js"></script>'))
})

test('an unresponsive connection aborts early while a responding slow body keeps its download budget', async () => {
  const source = readFileSync(new URL('../public/edge-static-worker.js', import.meta.url), 'utf8')
  const delays = []
  const context = {
    self: { addEventListener() {} }, AbortController, Uint8Array,
    setTimeout: (fn, ms) => { delays.push(ms); return setTimeout(fn, ms / 100) }, clearTimeout,
    fetch: (_url, options) => new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')))),
  }
  const bounded = vm.runInNewContext(source + '\n;boundedFetch', context)
  await assert.rejects(bounded('https://node.invalid/asset', {}, 12000), /aborted/)
  assert.equal(delays[0], 1500)
  context.fetch = async () => new Response(new ReadableStream({
    start(controller) { setTimeout(() => { controller.enqueue(new Uint8Array([42])); controller.close() }, 35) },
  }))
  delays.length = 0
  const result = await bounded('https://node.invalid/asset', {}, 12000)
  assert.equal(result.bytes[0], 42)
  assert.equal(delays[0], 1500)
  assert.ok(delays[1] > 10000)
})
