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

function harness(overrides = {}) {
  const listeners = new Map()
  const calls = []
  const self = { location: { origin }, addEventListener: (name, fn) => listeners.set(name, fn) }
  const fetch = async (input, options) => {
    const url = typeof input === 'string' ? input : input.url
    calls.push({ url, options })
    if (url === '/_edge/routing.json') return Response.json(overrides.config || config)
    if (url.endsWith('/release-manifest.json')) return Response.json(overrides.manifest || manifest)
    if (url.startsWith(node)) {
      if (overrides.nodeFailure) throw new Error('Network unavailable')
      return new Response(overrides.corrupt ? 'wrong' : asset, { headers: { 'content-type': 'text/javascript', 'set-cookie': 'must-not-forward' } })
    }
    return new Response('cloud same release', { headers: { 'content-type': 'text/javascript' } })
  }
  vm.runInNewContext(readFileSync(new URL('../public/edge-static-worker.js', import.meta.url), 'utf8'),
    { self, fetch, URL, Response, Uint8Array, TextDecoder, AbortController, crypto: webcrypto, setTimeout, clearTimeout })
  return {
    calls,
    request(url = origin + path, options = {}) {
      let pending
      listeners.get('fetch')({ request: new Request(url, options), respondWith(value) { pending = value } })
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
