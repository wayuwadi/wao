import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
import { readGitRelease, gitReleasePlugin } from './git-release.mjs'

test('真实 Git：无精选改动的文档提交也更新版本，未合并分支不混入，所有祖先保留', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'wao-git-release-'))
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim()
  try {
    git('init', '-q'); git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@invalid.example')
    writeFileSync(join(cwd, 'README.md'), 'base'); git('add', '.'); git('commit', '-qm', 'feat(notes): 初始功能')
    const first = readGitRelease(cwd)
    git('checkout', '-qb', 'unreleased'); git('commit', '--allow-empty', '-qm', 'feat(notes): 尚未发布')
    git('checkout', '--detach', first.history.buildSha)
    git('commit', '--allow-empty', '-qm', 'docs(notes): 只更新文档')
    const latest = readGitRelease(cwd)
    assert.notEqual(latest.edition.id, first.edition.id)
    assert.equal(latest.history.commits.length, 2)
    assert.match(latest.edition.intro, /只更新文档/)
    assert.equal(latest.history.commits.some(x => x.subject.includes('尚未发布')), false)
    assert.throws(() => readGitRelease(cwd, first.history.buildSha), /不一致/)
    const input = join(cwd, 'history.tsv')
    writeFileSync(input, git('log', '--topo-order', '--format=%H%x09%cI%x09%s'))
    assert.deepEqual(readGitRelease('/no-git-needed', latest.history.buildSha, input), latest)
    assert.throws(() => readGitRelease(cwd, first.history.buildSha, input), /缺少构建/)
    let asset
    gitReleasePlugin(latest).generateBundle.call({ emitFile: x => { asset = x } })
    // 生产不可变路径仅开放 assets/ 与 release-manifest.json。
    assert.match(`/releases/${latest.history.buildSha}/${asset.fileName}`, /^\/releases\/[0-9a-f]{40}\/(?:assets\/[^.][^\\?]*|release-manifest\.json)$/)
    assert.equal(asset.fileName, 'assets/git-history.json')
    assert.deepEqual(JSON.parse(asset.source), latest.history)
  } finally { assert.equal(resolve(dirname(cwd)), resolve(tmpdir())); rmSync(cwd, { recursive: true, force: true }) }
})
