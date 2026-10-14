import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createServer } from 'node:http'
import { localOrigin, matchesFault, writeFault, readFault, clearFault, faultMiddleware, SAVE_FAILURE } from './faults.mjs'

test('任务故障只命中指定任务的保存，不阻断版本读取或其他任务', () => {
  const fault = { taskId: 't_acceptance' }
  for (const url of ['/api/tasks/t_acceptance', '/api/tasks/t_acceptance/operations/op_123']) {
    assert.equal(matchesFault({ method: 'PUT', url }, fault), true)
    assert.equal(matchesFault({ method: 'GET', url }, fault), false)
  }
  for (const url of ['/api/tasks/t_acceptance_other', '/api/tasks/t_acceptance/subtasks', '/api/tasks/t_acceptance/operations/x/y']) {
    assert.equal(matchesFault({ method: 'PUT', url }, fault), false)
  }
})

test('删除失败验收只拦截指定任务，不拦截其他任务或子资源删除', () => {
  const fault = { taskId: 't_acceptance' }
  assert.equal(matchesFault({ method: 'DELETE', url: '/api/tasks/t_acceptance' }, fault), true)
  for (const url of ['/api/tasks/t_other', '/api/tasks/t_acceptance/subtasks', '/api/tasks/t_acceptance/operations/op_123']) {
    assert.equal(matchesFault({ method: 'DELETE', url }, fault), false)
  }
  assert.equal(matchesFault({ method: 'GET', url: '/api/tasks/t_acceptance' }, fault), false)
})

test('项目保存故障严格限于指定 ID 的 PATCH，不影响任务或其他项目', () => {
  const fault = { projectId: 'p_acceptance' }
  assert.equal(matchesFault({ method: 'PATCH', url: '/api/projects/p_acceptance' }, fault), true)
  for (const url of ['/api/projects/p_acceptance_other', '/api/projects', '/api/docs/p_acceptance', '/api/tasks/p_acceptance']) {
    assert.equal(matchesFault({ method: 'PATCH', url }, fault), false)
  }
  assert.equal(matchesFault({ method: 'GET', url: '/api/projects/p_acceptance' }, fault), false)
})

test('只接受本地无凭据源地址，拒绝生产、用户信息及路径', () => {
  assert.equal(localOrigin('http://127.0.0.1:18080', 'API'), 'http://127.0.0.1:18080')
  assert.equal(localOrigin('http://[::1]:8080', 'API'), 'http://[::1]:8080')
  for (const url of ['https://wao.wayuwa.cn', 'http://example.com', 'http://localhost@evil.test',
    'http://u:p@localhost:8080', 'http://localhost:8080/api', 'http://localhost:8080?x=1']) {
    assert.throws(() => localOrigin(url, 'API'))
  }
})

test('故障只匹配单篇文档 PATCH，不影响读取、协同、删除与其他文档', () => {
  const fault = { docId: 'd_test' }
  assert.equal(matchesFault({ method: 'PATCH', url: '/api/docs/d_test?x=1' }, fault), true)
  for (const method of ['GET', 'POST', 'PUT', 'DELETE', 'HEAD']) {
    assert.equal(matchesFault({ method, url: '/api/docs/d_test' }, fault), false)
  }
  for (const url of ['/api/docs/d_test_2', '/api/docs/d_test/presence', '/api/docs', '/api/tasks/d_test']) {
    assert.equal(matchesFault({ method: 'PATCH', url }, fault), false)
  }
})

test('损坏、缺失与过期配置不注入故障；拒绝通配符与无限延迟', () => {
  const dir = mkdtempSync(join(tmpdir(), 'wao-fault-test-'))
  const file = join(dir, 'fault.json')
  try {
    assert.equal(readFault(file), null)
    writeFileSync(file, '{')
    assert.equal(readFault(file), null)
    writeFault(file, { docId: 'd_test', mode: 'fail', expiresAt: 100 })
    assert.equal(readFault(file, 99).docId, 'd_test')
    assert.equal(readFault(file, 100), null)
    assert.throws(() => writeFault(file, { docId: '*', mode: 'fail', expiresAt: 100 }))
    assert.throws(() => writeFault(file, { docId: 'd_test', mode: 'delay', delayMs: 5001, expiresAt: 100 }))
    clearFault(file)
    assert.equal(readFault(file), null)
  } finally { rmSync(dir, { recursive: true, force: true }) }
})

test('真实 HTTP 请求得到明确失败；关闭开关后恢复透传，延迟请求只转发一次', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'wao-fault-http-'))
  const file = join(dir, 'fault.json')
  const middleware = faultMiddleware(file)
  let forwarded = 0
  const server = createServer((req, res) => middleware(req, res, () => { forwarded++; res.end('passed') }))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  try {
    writeFault(file, { docId: 'd_test', mode: 'fail', expiresAt: Date.now() + 60_000 })
    const failure = await fetch(`${base}/api/docs/d_test`, { method: 'PATCH' })
    assert.equal(failure.status, 503)
    assert.equal((await failure.json()).message, SAVE_FAILURE)
    assert.equal(forwarded, 0)
    assert.equal(await (await fetch(`${base}/api/docs/d_other`, { method: 'PATCH' })).text(), 'passed')
    assert.equal(await (await fetch(`${base}/api/docs/d_test`)).text(), 'passed')
    writeFault(file, { docId: 'd_test', mode: 'delay', delayMs: 50, expiresAt: Date.now() + 60_000 })
    const started = performance.now()
    assert.equal(await (await fetch(`${base}/api/docs/d_test`, { method: 'PATCH' })).text(), 'passed')
    assert.ok(performance.now() - started >= 40)
    assert.equal(forwarded, 3)
    clearFault(file)
    assert.equal(await (await fetch(`${base}/api/docs/d_test`, { method: 'PATCH' })).text(), 'passed')
    assert.equal(forwarded, 4)
  } finally {
    server.closeAllConnections()
    await new Promise(resolve => server.close(resolve))
    rmSync(dir, { recursive: true, force: true })
  }
})
