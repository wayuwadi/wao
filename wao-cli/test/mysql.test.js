import assert from 'node:assert/strict'
import test from 'node:test'
import { mysqlConnection } from '../src/mysql.js'

const firstPassword = 'fixture a#%'
const secondPassword = 'fixture b#%'

function fixture() {
  const profile = { host: 'localhost', port: 3306, database: 'fixture', user: 'reader', sslmode: 'require' }
  const records = new Map()
  const checks = []
  let current = firstPassword
  let local = firstPassword
  const options = {
    url: 'https://example.test/api/mcp/workspace', tokenProvider: async () => 'test-workspace-grant',
    getDeviceKey: async () => Buffer.alloc(32, 27),
    getProfile: async () => profile, getEntry: async () => '/fixture/dbhub.js',
    getLocalPassword: async () => local,
    async queryProvider(entry, target, secret) {
      assert.equal(entry, '/fixture/dbhub.js')
      assert.equal(target, profile)
      checks.push(secret)
      if (current === null) throw new Error('network unavailable')
      return secret === current
    },
    async serve(entry, target, password) {
      assert.equal(target, profile)
      assert.equal(password, current)
    },
    async fetchImpl(target, request) {
      assert.match(target.pathname, /^\/api\/vault\/cli\/mysql\/[0-9a-f]{32}$/)
      assert.equal(request.redirect, 'error')
      const previous = records.get(target.pathname)
      if (request.method === 'GET') return Response.json({ code: 0, data: previous ?? null })
      assert.ok(!request.body.includes(firstPassword) && !request.body.includes(secondPassword))
      const body = JSON.parse(request.body)
      assert.equal(body.expectedRevision, previous?.revision ?? null)
      assert.match(body.envelope, /^wao-mysql-v1\./)
      const revision = '0'.repeat(24)
      records.set(target.pathname, { envelope: body.envelope, revision })
      return Response.json({ code: 0, data: revision })
    },
  }
  return { profile, records, checks, options, set: (accepted, native) => { current = accepted; local = native } }
}

test('MySQL binds ciphertext to the connection, reuses WAO first, and saves a verified replacement', async () => {
  const { options, profile, records, checks, set } = fixture()
  const call = action => mysqlConnection(action, 'mysql-fixture', options)
  assert.deepEqual(await call('status'), { authenticated: true, source: 'local', vault: 'missing' })
  assert.deepEqual(await call('sync'), { synced: true, source: 'vault' })
  assert.deepEqual(await call('sync'), { synced: false, source: 'vault' })
  set(firstPassword, null)
  assert.deepEqual(await call('status'), { authenticated: true, source: 'vault' })
  profile.port = 3307
  await assert.rejects(call('status'), /AUTH_REQUIRED/)
  profile.port = 3306
  assert.equal(records.size, 1)
  set(secondPassword, secondPassword)
  checks.length = 0
  await call('serve')
  assert.deepEqual(checks, [firstPassword, secondPassword])
  set(secondPassword, null)
  assert.deepEqual(await call('status'), { authenticated: true, source: 'vault' })
  set(null, firstPassword)
  checks.length = 0
  await assert.rejects(call('status'), /network unavailable/)
  assert.deepEqual(checks, [secondPassword])
})
