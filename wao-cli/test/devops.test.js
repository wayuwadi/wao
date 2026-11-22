import assert from 'node:assert/strict'
import test from 'node:test'
import { devopsConnection } from '../src/devops.js'

const url = 'https://example.test/api/mcp/workspace'
const unrelated = 'not-an-auth-token'
const providerToken = 'synthetic-devops-authorization'

function fixture() {
  const events = []
  let key
  let saved = null
  let native = providerToken
  const options = {
    url, tokenProvider: async () => 'test-workspace-grant',
    getDeviceKey: async create => {
      if (create && !key) key = Buffer.alloc(32, 31)
      return key
    },
    async fetchImpl(target, request) {
      assert.equal(target.origin, 'https://example.test')
      assert.equal(request.redirect, 'error')
      events.push(request.method)
      if (request.method === 'GET') return Response.json({ code: 0, data: saved })
      const body = JSON.parse(request.body)
      assert.equal(body.expectedRevision, saved?.revision ?? null)
      assert.ok(body.envelope.startsWith('wao-devops-v1.'))
      assert.ok(!request.body.includes(providerToken))
      saved = { envelope: body.envelope, revision: '0'.repeat(24) }
      return Response.json({ code: 0, data: saved.revision })
    },
    async queryProvider(operation, override) {
      events.push('provider:' + (override ? 'provided' : 'native'))
      if ((override ?? native) !== providerToken) return null
      return operation === 'environments' ? [{ code: 'test', active: true }] : true
    },
    async credential(action, value) { if (action === 'read') return native; native = value },
  }
  return { options, events, setNative: value => { native = value }, tamper: () => { saved.envelope += '.corrupt' } }
}

test('the device saves only ciphertext and uses WAO before the native cache', async () => {
  const { options, events, setNative } = fixture()
  assert.deepEqual(await devopsConnection('sync', options), { synced: true, source: 'vault' })
  assert.deepEqual(await devopsConnection('sync', options), { synced: false, source: 'vault' })
  setNative(null)
  events.length = 0
  assert.deepEqual(await devopsConnection('environments', options), {
    authenticated: true, source: 'vault', environments: [{ code: 'test', active: true }],
  })
  assert.deepEqual(events, ['GET', 'provider:provided'])
  assert.deepEqual(await devopsConnection('restore', options), { authenticated: true, restored: true, source: 'vault' })
})

test('absent credentials signal auth required, but lookup errors and integrity failures do not', async () => {
  const { options, events, setNative, tamper } = fixture()
  setNative(unrelated)
  await assert.rejects(devopsConnection('status', options), /AUTH_REQUIRED/)
  setNative(providerToken)
  await devopsConnection('sync', options)
  tamper()
  await assert.rejects(devopsConnection('status', options), /无法用本机密钥验证/)
  events.length = 0
  await assert.rejects(devopsConnection('status', { ...options, fetchImpl: async () => new Response('', { status: 403 }) }), /需要.*设备授权/)
  assert.deepEqual(events, [])
})

test('authorize only validates the WAO permission and never contacts the provider', async () => {
  const { options, events } = fixture()
  assert.deepEqual(await devopsConnection('authorize', options), { authorized: true })
  assert.deepEqual(events, ['GET'])
})
