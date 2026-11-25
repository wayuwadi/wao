import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import test from 'node:test'
import { importAgentMemories } from '../src/agent-memory-import.js'
import { restoreAgentMemory } from '../src/agent-memory-restore.js'

async function sandbox(t) {
  const directory = await mkdtemp(join(tmpdir(), 'wao-agent-memory-test-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  return directory
}

async function write(path, text) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, text)
}

const noNetwork = () => { throw new Error('此步骤不能访问网络') }
const url = 'https://example.invalid/api/mcp/workspace'
const pref = { title: '验收习惯', content: '先给结果，再附证据', kind: 'preference', state: 'active', startup: true, project: '' }

function fakeReader(items = [pref]) {
  return {
    async listTools() { return [{ name: 'search_agent_memories' }] },
    async call(name, args) {
      assert.equal(name, 'search_agent_memories')
      assert.deepEqual(args, { startup_only: true, offset: 0 })
      return { items, hasMore: false }
    },
  }
}

test('恢复预览不写盘，两个客户端幂等安装，撤销保留后来加入的个人规则', async (t) => {
  const userHome = await sandbox(t)
  const opts = { client: () => fakeReader(), url, platform: 'darwin', userHome, codexHome: '' }
  const codexRule = join(userHome, '.codex', 'AGENTS.md')
  const codexSkill = join(userHome, '.agents', 'skills', 'wao-agent-memory', 'SKILL.md')
  const agyRule = join(userHome, '.gemini', 'config', 'rules', 'wao-agent-memory.md')
  const agySkill = join(userHome, '.gemini', 'config', 'skills', 'wao-agent-memory', 'SKILL.md')
  await write(codexRule, '用户已有规则\r\n')
  assert.equal((await restoreAgentMemory([], opts)).status, 'preview')
  assert.equal(await readFile(codexRule, 'utf8'), '用户已有规则\r\n')
  await assert.rejects(readFile(agyRule), { code: 'ENOENT' })
  assert.equal((await restoreAgentMemory(['--apply'], opts)).status, 'applied')
  assert.match(await readFile(codexRule, 'utf8'), /先给结果，再附证据/)
  assert.match(await readFile(codexSkill, 'utf8'), /填 codex/)
  assert.match(await readFile(agyRule, 'utf8'), /trigger: always_on/)
  assert.match(await readFile(agySkill, 'utf8'), /填 agy/)
  assert.ok((await readFile(agySkill, 'utf8')).includes(url))
  assert.equal((await restoreAgentMemory(['--apply'], opts)).status, 'current')
  await writeFile(codexRule, '后来加入的规则\n', { flag: 'a' })
  assert.equal((await restoreAgentMemory(['--undo'], { ...opts, client: noNetwork })).status, 'undone')
  assert.equal(await readFile(codexRule, 'utf8'), '用户已有规则\r\n后来加入的规则\n')
  await assert.rejects(readFile(codexSkill), { code: 'ENOENT' })
  await assert.rejects(readFile(agyRule), { code: 'ENOENT' })
})

test('优先使用 Codex 已生效的覆盖文件并在同名非 WAO Skill 存在时维持配置', async (t) => {
  const userHome = await sandbox(t)
  const opts = { client: () => fakeReader(), url, platform: 'darwin', userHome, codexHome: '' }
  const override = join(userHome, '.codex', 'AGENTS.override.md')
  const customSkill = join(userHome, '.gemini', 'config', 'skills', 'wao-agent-memory', 'SKILL.md')
  await write(override, '用户覆盖规则')
  await write(customSkill, '自己的同名 Skill')
  await assert.rejects(restoreAgentMemory(['--apply'], opts), /同名文件/)
  assert.equal(await readFile(override, 'utf8'), '用户覆盖规则')
  assert.equal((await restoreAgentMemory(['--apply', '--target', 'codex'], opts)).status, 'applied')
  assert.match(await readFile(override, 'utf8'), /先给结果，再附证据/)
  assert.equal(await readFile(customSkill, 'utf8'), '自己的同名 Skill')
})

test('迁移导入只提交已选正文，文件或范围变化后须重新预览', async (t) => {
  const root = await sandbox(t)
  await write(join(root, 'references', 'memory-index.md'), '# 索引\n\n## 示例项目\n先运行项目校验\n旧指针：/Users/demo/.codex/memories_v2/archive.md\n')
  const notes = join(root, 'references', 'ad-hoc-notes')
  await write(join(notes, 'lesson.md'), '# 手记\n执行一套可复用步骤')
  await write(join(notes, 'other.md'), '# 隔离手记\n未勾选内容')
  const args = ['--directory', root]
  assert.equal((await importAgentMemories(args, noNetwork)).entries.length, 3)
  const selected = [...args, '--entry', 'index:3', '--entry', 'lesson.md']
  const preview = await importAgentMemories(selected, noNetwork)
  assert.deepEqual(preview.entries.map(({ content }) => content), ['先运行项目校验', '执行一套可复用步骤'])
  const submitted = []
  const connect = () => ({
    async listTools() { return [{ name: 'propose_agent_memory' }] },
    async call(tool, entry) { submitted.push([tool, entry]); return { id: entry.title, state: 'draft' } },
  })
  assert.equal((await importAgentMemories([...selected, '--apply', preview.reviewId], connect)).status, 'submitted')
  assert.deepEqual(submitted.map(([tool, entry]) => [tool, entry.title]),
    [['propose_agent_memory', '示例项目'], ['propose_agent_memory', '手记']])
  await assert.rejects(importAgentMemories([...selected, '--project', 'changed', '--apply', preview.reviewId], noNetwork), /重新预览/)
})
