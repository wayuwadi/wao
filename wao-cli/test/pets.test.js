import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { Readable } from 'node:stream'
import { test } from 'node:test'
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { run } from '../src/cli.js'
import { createClient } from '../src/client.js'

const LOCAL = Buffer.from('504b0304', 'hex')
const NORMALIZED = Buffer.concat([LOCAL, Buffer.from('normalized server package')])
const TOKEN = 'wao_workspace_' + 'A'.repeat(43)
const CARD = { id: 'pet-1', slug: 'curly-trio', displayName: 'Curly Trio', spriteVersion: 2, sha256: createHash('sha256').update(NORMALIZED).digest('hex'), packageSize: NORMALIZED.length, listed: false }
function capture() { let text = ''; return { stream: { write: value => { text += value } }, get text() { return text } } }
async function fixture(fn, mode = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'wao-pets-'))
  const file = join(dir, 'pet.zip')
  await writeFile(file, LOCAL)
  const calls = []
  const server = createServer(async (request, response) => {
    const target = new URL(request.url, 'http://localhost')
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    calls.push({ path: target.pathname, operation: target.searchParams.get('operation'), method: request.method, auth: request.headers.authorization, contentType: request.headers['content-type'], body: Buffer.concat(chunks) })
    if (mode.redirect === target.pathname) { response.writeHead(302, { Location: '/sink' }); response.end(); return }
    response.setHeader('content-type', 'application/json')
    if (target.pathname === '/api/pets/cli-access') {
      response.statusCode = mode.permissionStatus || 200
      response.end(mode.permissionInvalidJson ? '<html>' : JSON.stringify(mode.permissionBody || { code: 0, data: true })); return
    }
    if (target.pathname === '/api/pets/mine') { response.end(JSON.stringify({ code: 0, data: [CARD] })); return }
    if (target.pathname === '/api/pets' || target.pathname === '/api/pets/pet-1/visibility') {
      const receiptFiles = await readdir(join(dir, 'receipts'))
      calls.at(-1).preparedReceipts = await Promise.all(receiptFiles.map(async file => JSON.parse(await readFile(join(dir, 'receipts', file)))))
      const listed = request.method === 'PUT' ? JSON.parse(Buffer.concat(chunks)).listed : false
      response.end(mode.writeInvalidJson ? 'invalid-json' : JSON.stringify(mode.writeBody || { code: 0, data: { ...CARD, listed } })); return
    }
    if (target.pathname === '/api/public/pets/pet-1') { response.end(JSON.stringify({ code: 0, data: { ...CARD, listed: true, ...mode.metadata } })); return }
    if (target.pathname === '/api/public/pets/pet-1/download') {
      response.setHeader('content-type', 'application/zip')
      if (mode.largeDownload) response.setHeader('content-length', 10 * 1024 * 1024 + 1)
      response.end(mode.download || NORMALIZED); return
    }
    response.statusCode = 404; response.end('{}')
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const url = `http://127.0.0.1:${server.address().port}/api/mcp/workspace`
  const out = capture(), err = capture()
  const options = { tokenProvider: async () => TOKEN, stdout: out.stream, stderr: err.stream, stdin: Readable.from([]), receiptDirectory: join(dir, 'receipts') }
  const invoke = (words, overrides = {}) => run(['pets', ...words, '--url', url], { ...options, ...overrides })
  try { await fn({ dir, file, calls, url, out, err, invoke, options }) }
  finally {
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve))
    assert.equal(dirname(resolve(dir)), resolve(tmpdir()))
    await rm(dir, { recursive: true, force: true })
  }
}

test('Pet help and argument validation require no credentials and expose no secret values', async () => {
  const out = capture()
  const options = { tokenProvider: () => { throw new Error('must not load credentials') }, stdout: out.stream }
  await run(['pets', 'upload', '--help'], options)
  assert.match(out.text, /上传只保存到我的 Pet/)
  for (const words of [ ['share'], ['share', '../../evil'], ['share', 'pet-1', '--yes', '--yes'], ['unshare', 'pet-1', '--yes'], ['list', TOKEN], ['upload', '--token', TOKEN], ['unknown', TOKEN] ]) {
    await assert.rejects(() => run(['pets', ...words], options), error => !error.message.includes(TOKEN) && !error.message.includes('must not load'))
  }
})

test('list preflights the dedicated snapshot operation and sends only credentialed owner request', async () => {
  await fixture(async ({ invoke, calls, out }) => {
    await invoke(['list', '--output', 'json'])
    assert.deepEqual(calls.map(call => [call.path, call.operation]), [['/api/pets/cli-access', 'list_my_pets'], ['/api/pets/mine', null]])
    assert.ok(calls.every(call => call.auth === 'Bearer ' + TOKEN))
    assert.equal(JSON.parse(out.text)[0].id, CARD.id)
  })
})

test('Pet rejects a deployment path prefix before credentials, HTTP or receipt mutation; root trailing slash works', async () => {
  await fixture(async ({ url, file, calls, options }) => {
    const forbiddenCredentials = async () => { throw new Error('must not load credentials') }
    for (const path of ['/prefix/api/mcp/workspace', '/api/mcp/workspace/extra', '/api/mcp/workspace//', '/other']) {
      const target = new URL(path, url).toString()
      for (const command of [['list'], ['upload', '--file', file, '--source', '本人创作'], ['share', 'pet-1', '--yes'], ['unshare', 'pet-1']]) {
        await assert.rejects(() => run(['pets', ...command, '--url', target], { ...options, tokenProvider: forbiddenCredentials }), /不支持路径前缀/)
      }
    }
    assert.equal(calls.length, 0)
    await assert.rejects(() => stat(options.receiptDirectory), error => error.code === 'ENOENT')
    await run(['pets', 'list', '--url', url + '/'], options)
    assert.deepEqual(calls.map(call => call.path), ['/api/pets/cli-access', '/api/pets/mine'])
  })
})

test('upload is private, checks permission before ZIP transmission and durably records normalized result', async () => {
  await fixture(async ({ invoke, file, calls, out, options }) => {
    await invoke(['upload', '--file', file, '--source', '  本人创作  '])
    assert.deepEqual(calls.map(call => call.method), ['GET', 'POST'])
    assert.equal(calls[0].operation, 'upload_pet')
    assert.match(calls[1].contentType, /^multipart\/form-data/)
    assert.match(calls[1].body.toString(), /name="archive"; filename="pet.zip"/)
    assert.match(calls[1].body.toString(), /本人创作/)
    assert.match(calls[1].body.toString(), /ALL-RIGHTS-RESERVED/)
    assert.equal(calls[1].preparedReceipts[0].state, 'prepared', 'receipt already durable when POST is received')
    assert.equal(calls[1].preparedReceipts[0].archiveSha256, createHash('sha256').update(LOCAL).digest('hex'))
    assert.ok(!calls[1].body.includes(Buffer.from('rightsConfirmed')))
    const result = JSON.parse(out.text)
    assert.equal(result.listed, false)
    const receipt = JSON.parse(await readFile(result.receiptFile, 'utf8'))
    assert.equal(receipt.state, 'succeeded')
    assert.equal(receipt.persistedListing, false)
    assert.notEqual(receipt.archiveSha256, receipt.result.sha256)
    assert.ok(!(await readFile(result.receiptFile, 'utf8')).includes(TOKEN))
    const before = calls.length
    await assert.rejects(() => invoke(['upload', '--file', file, '--source', '本人创作']), /回执已存在/)
    assert.equal(calls.length, before + 1, 'repeat does permission GET but no POST')
    assert.equal((await readdir(options.receiptDirectory)).length, 1)
  })
})

test('ZIP, source, license and options are validated before any credential request', async () => {
  await fixture(async ({ invoke, file, calls, dir }) => {
    const invalid = join(dir, 'invalid.zip'), wrongExtension = join(dir, 'pet.png'), empty = join(dir, 'empty.zip'), large = join(dir, 'large.zip')
    await writeFile(invalid, 'not a ZIP'); await writeFile(wrongExtension, LOCAL); await writeFile(empty, ''); await writeFile(large, Buffer.alloc(10 * 1024 * 1024 + 1))
    for (const args of [ ['--file', file], ['--file', file, '--source', ' '], ['--file', file, '--source', 'x'.repeat(501)], ['--file', file, '--source', 'ok', '--license', 'CC-BY-SA-4.0'], ['--file', invalid, '--source', 'ok'], ['--file', wrongExtension, '--source', 'ok'], ['--file', empty, '--source', 'ok'], ['--file', large, '--source', 'ok'], ['--file', file, '--source', 'ok', '--share'], ['--file', file, '--source', 'ok', '--receipt-file', '-'] ]) await assert.rejects(() => invoke(['upload', ...args]))
    assert.equal(calls.length, 0)
  })
})

test('missing scope, revoked credentials, invalid preflight JSON and business denial never transmit archive', async () => {
  for (const mode of [{ permissionStatus: 403 }, { permissionStatus: 401 }, { permissionInvalidJson: true }, { permissionBody: { code: 0, data: false } }, { permissionBody: { code: 403, message: 'denied ' + TOKEN } }]) {
    await fixture(async ({ invoke, file, calls, err, out }) => {
      await assert.rejects(() => invoke(['upload', '--file', file, '--source', '本人创作']), error => !error.message.includes(TOKEN))
      assert.equal(calls.length, 1)
      assert.ok(!out.text.includes(TOKEN) && !err.text.includes(TOKEN))
    }, mode)
  }
})

test('credentialed requests and anonymous verification reject redirects without reaching destination', async () => {
  for (const path of ['/api/pets/cli-access', '/api/pets', '/api/pets/pet-1/visibility', '/api/public/pets/pet-1/download']) {
    await fixture(async ({ invoke, file, calls }) => {
      await assert.rejects(() => invoke(path.includes('visibility') || path.includes('/public/') ? ['share', 'pet-1', '--yes'] : ['upload', '--file', file, '--source', '本人创作']))
      assert.ok(!calls.some(call => call.path === '/sink'))
    }, { redirect: path })
  }
  const options = []
  const client = createClient({ url: 'https://wao.example/api/mcp/workspace', token: TOKEN, fetchImpl: async (target, init) => {
    options.push(init)
    return new Response(JSON.stringify({ result: { tools: [] } }), { headers: { 'content-type': 'application/json' } })
  } })
  await client.listTools()
  assert.equal(options[0].redirect, 'error')
  assert.throws(() => createClient({ url: 'https://user:secret@wao.example/api/mcp/workspace', token: TOKEN }), /不得包含账号/)
})

test('POST malformed JSON and business errors leave a stable unknown receipt and do not retry', async () => {
  for (const mode of [{ writeInvalidJson: true }, { writeBody: { code: 400, message: 'bad manifest ' + TOKEN } }]) await fixture(async ({ invoke, file, calls, options }) => {
    await assert.rejects(() => invoke(['upload', '--file', file, '--source', '本人创作']), error => error.message.includes('未自动重试') && !error.message.includes(TOKEN))
    const receiptName = (await readdir(options.receiptDirectory))[0]
    const receipt = JSON.parse(await readFile(join(options.receiptDirectory, receiptName)))
    assert.equal(receipt.state, 'unknown')
    assert.equal(calls.filter(call => call.method === 'POST').length, 1)
  }, mode)
})

test('existing or unwritable custom receipt prevents upload before POST', async () => {
  await fixture(async ({ invoke, file, dir, calls }) => {
    const receipt = join(dir, 'existing.json')
    await writeFile(receipt, 'original receipt')
    await assert.rejects(() => invoke(['upload', '--file', file, '--source', '本人创作', '--receipt-file', receipt]), /回执已存在/)
    assert.equal(await readFile(receipt, 'utf8'), 'original receipt')
    await assert.rejects(() => invoke(['upload', '--file', file, '--source', '本人创作', '--receipt-file', join(dir, 'missing', 'new.json')]), /未发出写请求/)
    assert.equal(calls.filter(call => call.method === 'POST').length, 0)
  })
})

test('share requires explicit rights confirmation and anonymously verifies server-normalized package', async () => {
  await fixture(async ({ invoke, calls, out, url }) => {
    await assert.rejects(() => invoke(['share', 'pet-1']), /交互确认/)
    assert.equal(calls.length, 1)
    calls.length = 0
    await invoke(['share', 'pet-1', '--yes', '--output', 'json'])
    assert.equal(calls[0].operation, 'share_pet')
    assert.deepEqual(JSON.parse(calls[1].body), { listed: true, rightsConfirmed: true })
    assert.equal(calls[2].auth, undefined)
    assert.equal(calls[3].auth, undefined)
    const result = JSON.parse(out.text)
    assert.equal(result.publicUrl, new URL('/pets/pet-1', url).toString())
    assert.equal(result.verification.ok, true)
    const receipt = JSON.parse(await readFile(result.receiptFile))
    assert.equal(receipt.persistedListing, true)
    assert.equal(receipt.verification.ok, true)
  })
})

test('share public hash, metadata, and size failures report saved listing separately and no success output', async () => {
  for (const mode of [{ download: Buffer.alloc(NORMALIZED.length) }, { metadata: { sha256: '0'.repeat(64) } }, { largeDownload: true }]) await fixture(async ({ invoke, out, calls, options }) => {
    await assert.rejects(() => invoke(['share', 'pet-1', '--yes']), /公开状态已保存（listed=true），但匿名核验未通过/)
    assert.equal(out.text, '')
    const receipt = JSON.parse(await readFile(join(options.receiptDirectory, (await readdir(options.receiptDirectory))[0])))
    assert.equal(receipt.persistedListing, true)
    assert.equal(receipt.verification.ok, false)
    assert.equal(calls.filter(call => call.method === 'PUT').length, 1)
  }, mode)
})

test('interactive share confirmation precedes write; unshare has no confirmation and does not verify publicly', async () => {
  await fixture(async ({ invoke, calls, out }) => {
    await invoke(['share', 'pet-1'], { confirmShare: async (id, url) => { assert.equal(id, 'pet-1'); assert.match(url, /\/pets\/pet-1$/); assert.equal(calls.length, 1) } })
    calls.length = 0
    await invoke(['unshare', 'pet-1'], { confirmShare: () => { throw new Error('unexpected confirmation') }, stdout: capture().stream })
    assert.equal(calls.length, 2)
    assert.deepEqual(JSON.parse(calls[1].body), { listed: false })
    assert.match(out.text, /"ok": true/)
  })
})

test('unbounded chunked download is stopped at 10 MiB', async () => {
  let cancelled = false
  const client = createClient({ url: 'https://wao.example/api/mcp/workspace', token: TOKEN, fetchImpl: async target => {
    if (String(target).endsWith('/download')) return new Response(new ReadableStream({ start(controller) { controller.enqueue(Buffer.alloc(10 * 1024 * 1024 + 1)) }, cancel() { cancelled = true } }))
    return new Response(JSON.stringify({ code: 0, data: { ...CARD, listed: true } }))
  } })
  const result = await client.verifyPet({ ...CARD, listed: true })
  assert.equal(result.ok, false)
  assert.match(result.message, /超过 10 MiB/)
  assert.equal(cancelled, true)
})
