import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, rmSync, mkdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
import { readGitRelease, gitReleasePlugin, productEdition, loadReleaseChanges, validateReleaseGuides } from './git-release.mjs'
import currentGuide from '../src/data/currentRelease.json' with { type: 'json' }

test('评审用途和演示只绑定本次提交，保留自动 SHA 身份，旧精选不串版',()=>{
  const sha='b'.repeat(40)
  const edition=productEdition(sha,'2026-10-04',`${currentGuide.sourceSubject} (#9999)`,currentGuide)
  assert.equal(edition.id,`git-release-${sha}`)
  assert.deepEqual(edition.highlights,currentGuide.highlights)
  assert.equal('sourceSubject' in edition,false)
  const other=productEdition(sha,'2026-10-04','feat(changelog): 另外一次变化',currentGuide)
  assert.equal(other.highlights[0].title,'另外一次变化')
  assert.match(other.highlights[0].summary,/尚未附带/)
  assert.equal(other.highlights[0].demo,undefined)
})

test('随制品发布的演示截图缺失时阻止构建，不产生失效引导图',()=>{
  const cwd=mkdtempSync(join(tmpdir(),'wao-release-image-'))
  try {
    const sha='c'.repeat(40), input=join(cwd,'history.tsv')
    writeFileSync(input,`${sha}\t2026-10-04T08:00:00+08:00\t${currentGuide.sourceSubject}`)
    const guide={...currentGuide,highlights:[{...currentGuide.highlights[0],demo:{presentation:'window',image:'assets/release-guides/missing.png',alt:'截图'}}]}
    assert.throws(()=>readGitRelease(cwd,sha,input,guide),/截图缺失/)
  } finally {
    assert.equal(resolve(dirname(cwd)),resolve(tmpdir()))
    rmSync(cwd,{recursive:true,force:true})
  }
})

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
    assert.equal(latest.edition.attention, 'maintenance')
    assert.deepEqual(latest.edition.highlights, [])
    assert.equal(latest.edition.rangeVerified, false)
    assert.equal(first.edition.highlights[0].featureId, 'notes')
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

 test('用户说明只引用本次真实标题，未知功能不猜入口，维护不宣称新增', () => {
  const feature = productEdition('a'.repeat(40), '2026-10-03', 'feat(notes): 快速小记支持浮层 (#123)')
  assert.equal(feature.title, '快速小记支持浮层')
  assert.equal(feature.highlights[0].featureId, 'notes')
  assert.equal(feature.attention, 'feature')
  assert.equal(productEdition('a'.repeat(40), '2026-10-03', 'fix(unknown): 修正问题').highlights[0].featureId, 'changelog')
  for (const type of ['docs', 'test', 'ci', 'chore', 'refactor'])
    assert.equal(productEdition('a'.repeat(40), '2026-10-03', `${type}(notes): 更新说明`).attention, 'maintenance')
 })

function fixture(run) {
  const cwd = mkdtempSync(join(tmpdir(), 'wao-release-batch-'))
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  const commit = subject => { git('commit', '--allow-empty', '-qm', subject); return git('rev-parse', 'HEAD') }
  try {
    git('init', '-qb', 'main')
    git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@invalid.example')
    const base = commit('feat(notes): 上次已经发布')
    run({ cwd, git, commit, base })
  } finally {
    assert.equal(resolve(dirname(cwd)), resolve(tmpdir()))
    rmSync(cwd, { recursive: true, force: true })
  }
}

function guide(subject, id = 'change-fixture', featureId = 'notes', extra = {}) {
  return { id, sourceSubject: subject, publishedOn: '2026-10-06', title: '真实功能说明', intro: '已评审的用途。',
    highlights: [{ featureId, eyebrow: '使用说明', title: id, summary: '明确说明用途。', points: [], steps: ['从已存在的入口使用。'] }], ...extra }
}

test('真实发布区间汇总多项功能和修复，维护仅归档，精确绑定且同功能不丢操作', () => fixture(({ cwd, commit, base }) => {
  const first = 'feat(notes): 新的记录方法'
  const second = 'fix(notes): 修复阅读操作'
  commit(first + ' (#777)'); commit(second); commit('perf(todo): 更快展示')
  commit('docs(notes): 同批规格'); commit('test(notes): 同批测试'); commit('ci: 同批构建')
  const guides = [guide(first, 'compose', 'notes', { prominence: 'major' }), guide(second, 'reading'),
    guide('feat(notes): 上次已经发布', 'previous'), guide(first + '扩展', 'imprecise')]
  const result = readGitRelease(cwd, undefined, undefined, undefined, { baseSha: base, guides })
  assert.equal(result.edition.rangeVerified, true)
  assert.equal(result.edition.baseSha, base)
  assert.equal(result.edition.prominence, 'major')
  assert.equal(result.edition.attention, 'feature')
  assert.equal(result.edition.highlights.length, 3)
  assert.deepEqual(result.edition.highlights.map(item => item.featureId), ['notes', 'notes', 'todo'])
  assert.deepEqual(result.edition.highlights.filter(item => item.featureId === 'notes').map(item => item.title), ['reading', 'compose'])
  assert.match(result.edition.highlights[2].summary, /尚未附带/)
  assert.equal(result.edition.highlights[2].steps, undefined)
  assert.equal(result.edition.highlights.some(item => ['previous', 'imprecise'].includes(item.title)), false)
  assert.equal(result.history.commits.length, 7)
}))

test('不根据 feat 标题猜用途或重大更新，无说明的真实标题保留且默认 minor', () => fixture(({ cwd, commit, base }) => {
  commit('feat(notes): 尚未写使用说明')
  const result = readGitRelease(cwd, undefined, undefined, undefined, { baseSha: base })
  assert.equal(result.edition.prominence, 'minor')
  assert.equal(result.edition.highlights[0].title, '尚未写使用说明')
  assert.equal(result.edition.highlights[0].action, undefined)
  assert.deepEqual(result.edition.highlights[0].points, [])
}))

test('合并侧分支只能从首父链介绍变化，侧分支 base 不冒充发布边界', () => fixture(({ cwd, git, commit, base }) => {
  git('checkout', '-qb', 'side')
  const side = commit('feat(notes): 侧分支内部草稿')
  git('checkout', 'main'); commit('feat(notes): 主线变化')
  git('merge', '--no-ff', '-qm', 'feat(notes): 合并后的交付变化', 'side')
  const verified = readGitRelease(cwd, undefined, undefined, undefined, { baseSha: base })
  assert.equal(verified.edition.rangeVerified, true)
  assert.equal(verified.edition.highlights.length, 2)
  assert.equal(verified.edition.highlights.some(item => item.title.includes('内部草稿')), false)
  assert.equal(verified.history.commits.some(item => item.sha === side), true)
  const unverified = readGitRelease(cwd, undefined, undefined, undefined, { baseSha: side })
  assert.equal(unverified.edition.rangeVerified, false)
  assert.equal(unverified.edition.highlights.length, 1)
}))

test('边界缺失、不是祖先、格式无效时仅 HEAD 并明确未确认，base 等于 HEAD 无新增', () => fixture(({ cwd, git, commit, base }) => {
  git('checkout', '-qb', 'unreleased'); const unrelated = commit('feat(notes): 未合并')
  git('checkout', 'main'); const head = commit('fix(notes): 当前修复')
  for (const baseSha of [undefined, 'a'.repeat(40), unrelated, 'HEAD^']) {
    const result = readGitRelease(cwd, undefined, undefined, undefined, { baseSha })
    assert.equal(result.edition.rangeVerified, false)
    assert.equal(result.edition.baseSha, undefined)
    assert.match(result.edition.intro, /尚未确认/)
    assert.deepEqual(result.edition.highlights.map(item => item.title), ['当前修复'])
  }
  const same = readGitRelease(cwd, undefined, undefined, undefined, { baseSha: head })
  assert.equal(same.edition.rangeVerified, true)
  assert.equal(same.edition.baseSha, head)
  assert.deepEqual(same.edition.highlights, [])
  assert.equal(same.edition.attention, 'maintenance')
  assert.match(same.edition.intro, /没有新增/)
  assert.notEqual(base, head)
}))

test('无 Git 容器需要宿主可信首父区间链，校验 base/HEAD/连线和完整历史', () => fixture(({ cwd, git, commit, base }) => {
  commit('feat(notes): 第一项'); commit('fix(notes): 第二项')
  const head = git('rev-parse', 'HEAD')
  const input = join(cwd, 'history.tsv'), rangeFile = join(cwd, 'range.tsv')
  writeFileSync(input, git('log', '--topo-order', '--format=%H%x09%cI%x09%s'))
  const range = git('log', '--first-parent', '--format=%H%x09%P', `${base}..${head}`) + '\n' + git('show', '-s', '--format=%H%x09%P', base)
  writeFileSync(rangeFile, range)
  const local = readGitRelease(cwd, head, undefined, undefined, { baseSha: base })
  const exported = readGitRelease('/no-git-needed', head, input, undefined, { baseSha: base, rangeFile })
  assert.deepEqual(exported, local)
  const noChain = readGitRelease('/no-git-needed', head, input, undefined, { baseSha: base })
  assert.equal(noChain.edition.rangeVerified, false)
  assert.equal(noChain.edition.highlights.length, 1)
  for (const malformed of [range.split('\n').reverse().join('\n'), range.replace(base, 'f'.repeat(40)),
    range.split('\n').filter((_, index) => index !== 1).join('\n'), range + '\n' + range.split('\n')[0]]) {
    writeFileSync(rangeFile, malformed)
    assert.throws(() => readGitRelease('/no-git-needed', head, input, undefined, { baseSha: base, rangeFile }), /首父链/)
  }
  writeFileSync(rangeFile, range)
  const baseAbsent = readGitRelease('/no-git-needed', head, input, undefined, { baseSha: 'f'.repeat(40), rangeFile })
  assert.equal(baseAbsent.edition.rangeVerified, false)
  writeFileSync(rangeFile, git('show', '-s', '--format=%H%x09%P', head))
  assert.equal(readGitRelease('/no-git-needed', head, input, undefined, { baseSha: head, rangeFile }).edition.highlights.length, 0)
}))

test('重复提交标题或重复指南只保留同一条，不按功能去重，不截断大量内容', () => fixture(({ cwd, commit, base }) => {
  const subject = 'feat(notes): 重复合并记录'
  commit(subject); commit(subject + ' (#999)'); commit('fix(notes): 另一个操作')
  const many = guide(subject, 'many')
  many.highlights = Array.from({ length: 130 }, (_, index) => ({ ...many.highlights[0], title: `独立操作 ${index}` }))
  const result = readGitRelease(cwd, undefined, undefined, undefined, { baseSha: base, guides: [many, many] })
  assert.equal(result.edition.highlights.length, 131)
  assert.equal(new Set(result.edition.highlights.map(item => item.id)).size, 131)
  assert.equal(result.edition.highlights.some(item => item.title === '独立操作 129'), true)
}))

test('入口严格登记和功能匹配，未知/失效入口与不安全路由拒绝构建', () => fixture(({ cwd, commit, base }) => {
  const subject = 'feat(notes): 精确入口'
  commit(subject)
  const valid = guide(subject)
  valid.highlights[0].action = { target: 'notes-reading', label: '打开伴读' }
  const actions = [{ id: 'notes-reading', featureId: 'notes', path: '/notes', query: { releaseAction: 'reading' }, label: '打开伴读' }]
  const build = (guides = [valid], registry = actions) => readGitRelease(cwd, undefined, undefined, undefined, { baseSha: base, guides, actions: registry })
  assert.equal(build().edition.highlights[0].action.target, 'notes-reading')
  assert.throws(() => build([valid], []), /入口/)
  assert.throws(() => build([valid], [{ ...actions[0], featureId: 'todo' }]), /入口/)
  assert.throws(() => build([valid], [{ ...actions[0], path: 'https://invalid.example' }]), /入口/)
  assert.throws(() => build([valid], [{ ...actions[0], path: '/todo' }]), /入口/)
  assert.throws(() => build([valid], [{ ...actions[0], query: { releaseAction: 'settings' } }]), /参数/)
  for (const action of [{ target: 'unknown', label: '打开' }, { target: 'notes-reading', label: '过'.repeat(81) },
    { target: 'notes-reading', label: '打开', path: '/todo' }, null]) {
    const invalid = structuredClone(valid); invalid.highlights[0].action = action
    assert.throws(() => build([invalid]), /入口/)
  }
}))

test('演示白名单与制品安全资源检查包含全部指南，未匹配的坏说明也阻止生成', () => fixture(({ cwd }) => {
  const valid = guide('feat(notes): 阅读示意')
  valid.highlights[0].demo = { presentation: 'inline', kind: 'notes-reading', alt: '操作示意' }
  validateReleaseGuides(cwd, [valid], [])
  for (const kind of ['<script>', 'unknown']) {
    const invalid = structuredClone(valid); invalid.highlights[0].demo.kind = kind
    assert.throws(() => validateReleaseGuides(cwd, [invalid], []), /演示/)
  }
  for (const image of ['assets/release-guides/missing.png', '../outside.png', 'https://invalid.example/a.png']) {
    const invalid = structuredClone(valid); delete invalid.highlights[0].demo.kind; invalid.highlights[0].demo.image = image
    assert.throws(() => readGitRelease(cwd, undefined, undefined, undefined, { guides: [invalid] }), /截图缺失/)
  }
  const image = 'assets/release-guides/reading.png'
  mkdirSync(join(cwd, 'wao-web/public/assets/release-guides'), { recursive: true })
  writeFileSync(join(cwd, 'wao-web/public', image), 'fixture pixels')
  delete valid.highlights[0].demo.kind
  valid.highlights[0].demo.image = image
  validateReleaseGuides(cwd, [valid], [])
}))

test('独立说明全量加载保持旧历史正文，缺绑定或重复身份拒绝', () => {
  const cwd = resolve(import.meta.dirname, '../..')
  const migrated = loadReleaseChanges(cwd)
  const history = JSON.parse(readFileSync(join(cwd, 'wao-web/src/data/releaseHistory.json'), 'utf8')).filter(item => item.sourceSubject)
  assert.ok(migrated.length >= 48)
  for (const old of history.filter(item => migrated.some(guide => guide.id === item.id))) {
    const current = migrated.find(item => item.id === old.id)
    assert.ok(current)
    if (['release-notes-reading-first-2026-10-06', 'release-notes-refine-display-2026-10-06'].includes(old.id)) {
      assert.equal(current.sourceSubject, old.sourceSubject)
      assert.ok(current.highlights.every(item => item.action))
    } else assert.deepEqual(current, old)
  }
  fixture(({ cwd }) => {
    const guideDir = join(cwd, 'wao-web/src/data/releaseChanges')
    mkdirSync(guideDir, { recursive: true })
    writeFileSync(join(guideDir, 'invalid.json'), JSON.stringify({ id: 'unbound' }))
    assert.throws(() => loadReleaseChanges(cwd), /缺少精确/)
    const first = guide('feat(notes): 同一身份')
    assert.throws(() => validateReleaseGuides(cwd, [first, { ...first, intro: '不同内容' }], []), /重复/)
  })
})
