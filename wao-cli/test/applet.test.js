import assert from 'node:assert/strict'
import test from 'node:test'
import { appletConnection } from '../src/applet.js'

const profile = { origin: 'https://passport.example.test', clientType: 'test-user-applet', appId: 'wx0123456789abcdef' }
const originalSession = 'synthetic.applet.session'
const secondSession = 'synthetic.another.session'
const loginCode = 'synthetic_code'
const alias = 'test-miniapp'

function fixture() {
  const records = new Map()
  const requests = []
  let validSession = originalSession
  let passportUser = '12345'
  let online = true
  const options = {
    url: 'https://wao.example.test/api/mcp/workspace', tokenProvider: async () => 'test-workspace-grant',
    getProfile: async () => profile,
    getDeviceKey: async () => Buffer.alloc(32, 17),
    async fetchImpl(url, request) {
      assert.equal(url.origin, 'https://wao.example.test')
      assert.match(url.pathname, /^\/api\/vault\/cli\/applet\/[0-9a-f]{32}$/)
      assert.equal(request.redirect, 'error')
      const record = records.get(url.pathname)
      if (request.method === 'GET') return Response.json({ code: 0, data: record ?? null })
      assert.ok(![originalSession, secondSession, loginCode, passportUser].some(value => request.body.includes(value)))
      const body = JSON.parse(request.body)
      assert.equal(body.expectedRevision, record?.revision ?? null)
      assert.match(body.envelope, /^wao-applet-v1\./)
      const revision = '0'.repeat(24)
      records.set(url.pathname, { envelope: body.envelope, revision })
      return Response.json({ code: 0, data: revision })
    },
    async providerFetch(url, request) {
      requests.push([url.pathname, request.method])
      assert.equal(url.origin, profile.origin)
      assert.equal(request.redirect, 'error')
      assert.equal(request.headers.appId, profile.appId)
      assert.equal(request.headers['plat-form'], profile.clientType)
      assert.equal(request.headers['Xs-Client-Type'], profile.clientType)
      if (!online) throw new Error('test network timeout')
      if (url.pathname === '/passport/applet/login') {
        assert.equal(request.headers.Authorization, undefined)
        assert.deepEqual(JSON.parse(request.body), { code: loginCode, appId: profile.appId })
        return Response.json({ code: 200, data: { token: validSession, userId: passportUser } })
      }
      assert.equal(url.pathname, '/passport/user')
      return request.headers.Authorization === validSession
        ? Response.json({ code: 200, data: { userId: passportUser } })
        : Response.json({ code: 401 })
    },
    readInput: async () => { throw new Error('input should not be read') },
  }
  return { options, requests, records, setSession: (session, id = passportUser) => { validSession = session; passportUser = id },
    disconnect: () => { online = false } }
}

test('a verified WAO session is used without consuming another WeChat code', async () => {
  const { options, requests, records } = fixture()
  assert.deepEqual(await appletConnection('status', alias, options), { authenticated: false, reason: 'missing', profile: alias })
  const result = await appletConnection('login', alias, { ...options, readInput: async () => loginCode })
  assert.deepEqual(result, { authenticated: true, saved: true, source: 'vault', profile: alias })
  assert.equal(records.size, 1)
  assert.deepEqual(requests, [['/passport/applet/login', 'POST'], ['/passport/user', 'GET']])
  requests.length = 0
  assert.deepEqual(await appletConnection('login', alias, options), { authenticated: true, source: 'vault', profile: alias })
  assert.deepEqual(requests, [['/passport/user', 'GET']])
  assert.deepEqual(await appletConnection('status', alias, { ...options, getProfile: async () => ({ ...profile, appId: 'wx0123456789abcdee' }) }),
    { authenticated: false, reason: 'missing', profile: alias })
})

test('an expired session may be replaced for the same account and network errors stop before input', async () => {
  const { options, setSession, disconnect, requests } = fixture()
  const attempt = (session) => appletConnection('import', alias, { ...options, readInput: async () => session })
  assert.equal((await attempt(originalSession)).saved, true)
  setSession(secondSession, '9876')
  assert.equal((await appletConnection('status', alias, options)).reason, 'expired')
  await assert.rejects(attempt(secondSession), /账号与本机绑定不一致/)
  setSession(secondSession, '12345')
  assert.equal((await attempt(secondSession)).saved, true)
  disconnect()
  requests.length = 0
  await assert.rejects(appletConnection('login', alias, options), /Passport 请求失败/)
  assert.deepEqual(requests, [['/passport/user', 'GET']])
})
