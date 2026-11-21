import assert from 'node:assert/strict'
import { PassThrough, Readable } from 'node:stream'
import test from 'node:test'
import { codeupStatus } from '../src/codeup.js'
import { serveConnections } from '../src/connections-mcp.js'
import { runVault } from '../src/vault.js'
import { formatOutput } from '../src/cli.js'

test('Codeup only calls status on the configured binary and returns selected fields', async () => {
  const executable = process.platform === 'win32' ? 'C:\\commands\\codeup-cli.exe' : '/commands/codeup-cli'
  const result = await codeupStatus(12, { executable, execute: async (file, args) => {
    assert.equal(file, executable)
    assert.deepEqual(args, ['status', '12'])
    return { stdout: JSON.stringify({ localId: 12, status: 'MERGED', targetBranch: 'test',
      mergedRevision: 'a'.repeat(40), title: 'private-title-example', secret: 'sample-unused-secret' }) }
  } })
  assert.deepEqual(result, { localId: 12, status: 'MERGED', targetBranch: 'test', mergedRevision: 'a'.repeat(40) })
  await assert.rejects(codeupStatus(0), /正整数/)
  await assert.rejects(codeupStatus(12, { executable, execute: async () => {
    throw Object.assign(new Error('sample-unused-secret'), { stderr: '钥匙串缺少条目 codeup-cli。sample-unused-secret' })
  } }), { message: 'AUTH_REQUIRED：请在本机 Codeup CLI 配置或更新授权，然后重试。' })
})

test('local MCP requires initialization and accepts only a read-only Codeup status request', async () => {
  const requests = [
    { id: 1, method: 'tools/list' },
    { id: 2, method: 'initialize', params: { protocolVersion: '2025-06-18' } },
    { method: 'notifications/initialized' },
    { id: 3, method: 'tools/list' },
    { id: 4, method: 'tools/call', params: { name: 'codeup_pr_status', arguments: { number: 7, command: 'merge' } } },
    { id: 5, method: 'tools/call', params: { name: 'codeup_pr_status', arguments: { number: 7 } } },
  ]
  const stdin = Readable.from(requests.map((entry) => JSON.stringify({ jsonrpc: '2.0', ...entry }) + '\n'))
  const stdout = new PassThrough()
  let captured = ''
  stdout.on('data', (data) => { captured += data })
  await serveConnections({ stdin, stdout, version: 'test', query: async (number) => ({ localId: number, status: 'OPENED' }) })
  const [before, init, list, rejected, call] = captured.trim().split('\n').map((line) => JSON.parse(line))
  assert.equal(before.error.code, -32000)
  assert.equal(init.result.protocolVersion, '2025-06-18')
  assert.equal(list.result.tools.length, 1)
  assert.equal(list.result.tools[0].annotations.readOnlyHint, true)
  assert.equal(rejected.error.code, -32602)
  assert.deepEqual(call.result.structuredContent, { localId: 7, status: 'OPENED' })
})

const fakeToken = 'test-device-token'
const sampleItem = { id: 'vi-test', name: 'Sample account', hasSecret: true }
const response = (data) => Response.json({ code: 0, data })

test('vault checks permission before reading stdin and reports only metadata from save and find', async () => {
  const actions = []
  let printed = ''
  const secret = '  only-a-test-凭据  '
  const options = {
    url: 'https://example.test/api/mcp/workspace',
    stdin: Readable.from((async function* () {
      assert.deepEqual(actions, ['/api/vault/cli/save-access'])
      yield Buffer.from(secret)
    })()),
    stdout: { write(value) { printed += value } }, stderr: { write() {} }, formatOutput, output: 'json',
    tokenProvider: async () => fakeToken,
    async fetchImpl(url, request) {
      assert.equal(url.origin, 'https://example.test')
      assert.equal(request.redirect, 'error')
      assert.equal(request.headers.Authorization, `Bearer ${fakeToken}`)
      actions.push(url.pathname)
      if (url.pathname.endsWith('/save-access')) return response(true)
      if (url.pathname.endsWith('/save')) {
        assert.deepEqual(JSON.parse(request.body), { name: sampleItem.name, account: 'test-account', secret })
        return response({ item: { ...sampleItem, secret }, saved: true })
      }
      return response([{ ...sampleItem, secret }])
    },
  }
  await runVault(['save', sampleItem.name, '--account', 'test-account', '--secret-stdin'], options)
  assert.deepEqual(JSON.parse(printed), { ...sampleItem, saved: true })
  printed = ''
  await runVault(['find', sampleItem.name], options)
  assert.deepEqual(JSON.parse(printed), [sampleItem])
})

test('a denied vault grant leaves the secret input unread', async () => {
  const stdin = Readable.from((async function* () { assert.fail('credential input was read') })())
  await assert.rejects(runVault(['save', 'Sample account', '--secret-stdin'], {
    url: 'https://example.test/api/mcp/workspace', stdin, tokenProvider: async () => fakeToken,
    fetchImpl: async () => new Response('', { status: 403 }),
  }), /未获此密码本操作授权/)
})
