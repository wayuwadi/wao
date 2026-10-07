import { execFileSync } from 'node:child_process'
import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

/** 只读取制品 HEAD 可达的提交；不读取正文、作者、远端地址或未合并分支。 */
export function readGitRelease(cwd, expectedSha, inputFile, guide, options = {}) {
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 }).trim()
  const sha = inputFile ? expectedSha : git('rev-parse', 'HEAD')
  if (!/^[0-9a-f]{40}$/.test(sha) || (expectedSha && expectedSha !== sha))
    throw new Error('更新记录 SHA 与构建 HEAD 不一致')
  if (!inputFile && git('rev-parse', '--is-shallow-repository') !== 'false')
    throw new Error('更新记录需要完整 Git 历史，请先补全 shallow checkout')
  const raw = inputFile ? readFileSync(inputFile, 'utf8').trim() : git('log', '--topo-order', '--format=%H%x09%cI%x09%s', sha)
  const commits = raw.split('\n').map(line => {
    const [commitSha, committedAt, ...subject] = line.split('\t')
    return { sha: commitSha, committedAt, subject: subject.join('\t') }
  })
  const seen = new Set()
  for (const item of commits) {
    if (!/^[0-9a-f]{40}$/.test(item.sha) || seen.has(item.sha) || !item.subject.trim() || !Number.isFinite(Date.parse(item.committedAt)))
      throw new Error('Git 更新记录不完整或格式无效')
    seen.add(item.sha)
  }
  if (commits[0]?.sha !== sha) throw new Error('更新记录缺少构建 HEAD')
  const latest = commits[0]
  const publishedOn = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(latest.committedAt))
  const guides = [...(options.guides || []), ...(guide ? [guide] : [])]
  validateReleaseGuides(cwd, guides, options.actions || [])
  const baseSha = options.baseSha?.trim()
  let rangeVerified = false
  let selected = [latest]
  if (/^[0-9a-f]{40}$/.test(baseSha || '') && seen.has(baseSha)) {
    let chain
    if (inputFile && options.rangeFile && existsSync(options.rangeFile)) {
      chain = readFileSync(options.rangeFile, 'utf8').trim().split('\n').map(line => {
        const [commitSha, parents = ''] = line.trim().split('\t')
        return { sha: commitSha, parents: parents.split(' ').filter(Boolean) }
      })
      if (chain[0]?.sha !== sha || chain.at(-1)?.sha !== baseSha || new Set(chain.map(item => item.sha)).size !== chain.length ||
        chain.some((item, index) => !seen.has(item.sha) || item.parents.some(parent => !/^[0-9a-f]{40}$/.test(parent)) ||
          (index < chain.length - 1 && item.parents[0] !== chain[index + 1].sha)))
        throw new Error('发布区间首父链无效或与构建 SHA 不一致')
    } else if (!inputFile) {
      // base 必须位于 HEAD 首父链；仅可达的侧分支提交不能作为批次边界。
      const firstParent = git('rev-list', '--first-parent', sha).split('\n')
      const boundary = firstParent.indexOf(baseSha)
      if (boundary >= 0) chain = firstParent.slice(0, boundary + 1).map(commitSha => ({ sha: commitSha }))
    }
    if (chain) {
      rangeVerified = true
      const bySha = new Map(commits.map(item => [item.sha, item]))
      selected = chain.slice(0, -1).map(item => bySha.get(item.sha))
    }
  }
  const edition = batchEdition(sha, publishedOn, selected, guides, rangeVerified, baseSha)
  return { edition, history: { buildSha: sha, commits } }
}

/** 每个独立 JSON 是随改动评审的说明；文件名排序只用于稳定构建。 */
export function loadReleaseChanges(cwd) {
  const directory = join(cwd, 'wao-web/src/data/releaseChanges')
  return existsSync(directory) ? readdirSync(directory).filter(file => file.endsWith('.json')).sort()
    .map(file => {
      const guide = JSON.parse(readFileSync(join(directory, file), 'utf8'))
      if (!text(guide.sourceSubject, 1000)) throw new Error('独立更新说明缺少精确提交标题')
      return guide
    }) : []
}

const actionTargets = new Set(['notes-compose', 'notes-settings', 'notes-reading', 'notes-ai', 'workspace-connections', 'release-updates', 'settings-about', 'design-reference'])
const actionDestinations = {
  'notes-compose': ['notes', '/notes'], 'notes-settings': ['notes', '/notes'],
  'notes-reading': ['notes', '/notes'], 'notes-ai': ['notes', '/notes'],
  'workspace-connections': ['capabilities', '/capabilities'], 'release-updates': ['changelog', '/features'],
  'settings-about': ['settings', '/settings'],
  'design-reference': ['settings', '/design'],
}
const actionQueries = {
  'notes-compose': {}, 'notes-settings': { releaseAction: 'settings' },
  'notes-reading': { releaseAction: 'reading' }, 'notes-ai': { releaseAction: 'ai' },
  'workspace-connections': { area: 'connections' }, 'release-updates': {},
  'settings-about': { tab: 'about' },
  'design-reference': {},
}
const demoKinds = new Set(['update-card', 'workspace-mcp', 'notes-layout', 'notes-reading'])
const text = (value, limit = 2000) => typeof value === 'string' && !!value.trim() && value.length <= limit

/** 无任意 URL、HTML 或猜测入口；说明错误在制品生成前失败。 */
export function validateReleaseGuides(cwd, guides, registry) {
  const actions = Array.isArray(registry) ? registry : registry.actions
  if (!Array.isArray(actions)) throw new Error('功能入口登记表无效')
  const actionIds = new Set()
  for (const action of actions) {
    if (!actionTargets.has(action.id) || actionIds.has(action.id) || !text(action.featureId, 80) || !text(action.label, 80) ||
      !/^\/(?!\/)[a-z0-9/-]*$/.test(action.path || '') ||
      actionDestinations[action.id]?.[0] !== action.featureId || actionDestinations[action.id]?.[1] !== action.path ||
      (action.hash !== undefined && (action.id !== 'release-updates' || action.hash !== '#recent-updates')) ||
      (action.query !== undefined && (!action.query || typeof action.query !== 'object' || Array.isArray(action.query) ||
        Object.entries(action.query).some(([key, value]) => !/^[a-zA-Z][a-zA-Z0-9_-]*$/.test(key) || typeof value !== 'string' || value.length > 160))))
      throw new Error('功能入口登记无效、重复或已失效')
    actionIds.add(action.id)
    const expectedQuery = actionQueries[action.id]
    if (Object.keys(action.query || {}).length !== Object.keys(expectedQuery).length ||
      Object.entries(expectedQuery).some(([key, value]) => action.query?.[key] !== value))
      throw new Error('功能入口登记参数与实际操作不匹配')
  }
  const byId = new Map(actions.map(action => [action.id, action]))
  const identities = new Map()
  const subjects = new Map()
  for (const guide of guides) {
    if (!text(guide.id, 160) || !text(guide.title, 160) || !text(guide.intro) || !/^\d{4}-\d{2}-\d{2}$/.test(guide.publishedOn || '') ||
      (guide.sourceSubject !== undefined && !text(guide.sourceSubject, 1000)) ||
      (guide.prominence !== undefined && !['major', 'minor'].includes(guide.prominence)) ||
      (guide.attention !== undefined && !['feature', 'maintenance'].includes(guide.attention)) ||
      !Array.isArray(guide.highlights) || !guide.highlights.length)
      throw new Error('更新说明元数据无效')
    const serialized = JSON.stringify(guide)
    if ((identities.has(guide.id) && identities.get(guide.id) !== serialized) ||
      (guide.sourceSubject && subjects.has(normalizeSubject(guide.sourceSubject)) && subjects.get(normalizeSubject(guide.sourceSubject)) !== serialized))
      throw new Error('更新说明身份或提交标题重复且内容不一致')
    identities.set(guide.id, serialized)
    if (guide.sourceSubject) subjects.set(normalizeSubject(guide.sourceSubject), serialized)
    for (const item of guide.highlights) {
      if (!text(item.featureId, 80) || !text(item.eyebrow, 80) || !text(item.title, 160) || !text(item.summary, 1000) ||
        !Array.isArray(item.points) || item.points.length > 10 || item.points.some(point => !text(point, 300)) ||
        (item.steps !== undefined && (!Array.isArray(item.steps) || item.steps.length > 10 || item.steps.some(step => !text(step, 300)))))
        throw new Error('更新变化或使用步骤无效')
      if (item.action !== undefined && (!item.action || !actionTargets.has(item.action.target) || !text(item.action.label, 80) ||
        byId.get(item.action.target)?.featureId !== item.featureId || Object.keys(item.action).some(key => !['target', 'label'].includes(key))))
        throw new Error('更新体验入口未知、失效或功能不匹配')
      if (item.demo) {
        if (!['inline', 'window'].includes(item.demo.presentation) || !text(item.demo.alt, 300) ||
          (item.demo.kind && !demoKinds.has(item.demo.kind)) || (!!item.demo.kind === !!item.demo.image))
          throw new Error('功能演示元数据无效')
        if (item.demo.image && (!/^assets\/release-guides\/[a-z0-9-]+\.(png|jpg|webp)$/.test(item.demo.image) ||
          !existsSync(join(cwd, 'wao-web/public', item.demo.image))))
          throw new Error('功能演示截图缺失或路径无效')
      }
    }
  }
}

function batchEdition(sha, publishedOn, commits, guides, rangeVerified, baseSha) {
  const highlights = []
  const ids = new Set()
  const subjects = new Set()
  let prominence = 'minor'
  for (const commit of commits) {
    const subject = normalizeSubject(commit.subject)
    const match = /^(feat|fix|perf)(?:\(([^)]+)\))?!?:\s*(.+)$/.exec(subject)
    if (!match || subjects.has(subject)) continue
    subjects.add(subject)
    const guide = guides.find(item => item.sourceSubject && normalizeSubject(item.sourceSubject) === subject)
    const identity = guide?.id || `git-change-${commit.sha}`
    if (ids.has(identity)) continue
    ids.add(identity)
    if (guide?.prominence === 'major') prominence = 'major'
    const items = productEdition(commit.sha, publishedOn, commit.subject, guide).highlights
    highlights.push(...items.map((item, index) => ({ ...item, id: `${identity}:${index}`, sourceSubject: subject.slice(0, 1000) })))
  }
  // 同功能集中阅读，但保留稳定的区间顺序和每个具体操作。
  highlights.sort((left, right) => left.featureId.localeCompare(right.featureId, 'en'))
  const attention = highlights.length ? 'feature' : 'maintenance'
  return {
    id: `git-release-${sha}`, publishedOn, attention, prominence, rangeVerified,
    ...(rangeVerified ? { baseSha } : {}),
    title: highlights.length > 1 ? (rangeVerified ? '本次发布的全部变化' : '当前构建的变化') : highlights[0]?.title || '日常维护更新',
    intro: !rangeVerified ? '发布区间尚未确认，仅展示当前构建提交的变化，完整记录保留在「最近更新」。'
      : !commits.length ? '当前构建与上次发布相同，本次没有新增变化。'
        : highlights.length ? '以下汇总从上次发布到当前构建的全部功能变化；维护记录保留在「最近更新」。'
          : '本次为维护更新。你可以按自己的节奏保存并刷新，完整记录保留在「最近更新」。',
    highlights,
  }
}

// 只映射有真实入口的功能；未知 scope 不猜测功能归属。
const destinations = {
  notes: ['notes', '小记'], projects: ['projects', '项目'], todo: ['todo', '待办'],
  calendar: ['calendar', '日历'], knowledge: ['knowledge', '知识库'],
  home: ['home', '首页'], tianren: ['tianren', '天人一卦'], breathe: ['breathe', '休息一下'],
  changelog: ['changelog', '功能探索'],
  automation: ['automation', '自动化'], 'text-polish': ['text-polish', '润色'],
  'reference-images': ['reference-images', '参考图库'], settings: ['settings', '设置'],
}

const normalizeSubject = subject => subject.trim().replace(/\s*\(#\d+\)$/, '')

/** 本次只介绍构建 HEAD，不把过去的提交重新宣称为本次新增。完整历史另行保留。 */
export function productEdition(sha, publishedOn, subject, guide) {
  const match = /^(\w+)(?:\(([^)]+)\))?!?:\s*(.+)$/.exec(subject)
  const title = (match?.[3] || subject).replace(/\s*\(#\d+\)$/, '').slice(0, 160)
  const userFacing = !!match && ['feat', 'fix', 'perf'].includes(match[1])
  const destination = userFacing ? destinations[match[2]] : undefined
  const maintenance = !userFacing
  // 精确绑定本次标题；旧精选不能仅因 scope 相同就冒充本次变化。
  if (userFacing && guide?.sourceSubject && normalizeSubject(guide.sourceSubject) === normalizeSubject(subject)) {
    const { sourceSubject: _sourceSubject, ...edition } = guide
    return { ...edition, id: `git-release-${sha}`, publishedOn, attention: 'feature' }
  }
  return {
    id: `git-release-${sha}`, publishedOn,
    title: maintenance ? '日常维护更新' : title,
    attention: maintenance ? 'maintenance' : 'feature',
    intro: maintenance
      ? '本次为维护更新。你可以按自己的节奏保存并刷新，完整记录保留在「最近更新」。'
      : '本次改动尚未附带使用说明，完整提交记录可在「最近更新」查看。',
    highlights: [{
      featureId: destination?.[0] || 'changelog',
      eyebrow: maintenance ? '日常维护' : match?.[1] === 'fix' ? '体验修复' : match?.[1] === 'perf' ? '性能优化' : '功能改进',
      title,
      summary: maintenance ? '本次为维护更新，可按自己的节奏保存并刷新。' : '这项变化尚未附带功能介绍和使用步骤。',
      points: [],
    }],
  }
}

/** 同一份数据供页面、版本检测清单和提交档案使用，发布不可拆开替换。 */
export function gitReleasePlugin(data) {
  const source = JSON.stringify(data.history)
  return {
    name: 'wao-git-release',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/assets/git-history.json') return next()
        res.setHeader('Content-Type', 'application/json; charset=utf-8')
        res.setHeader('Cache-Control', 'no-store')
        res.end(source)
      })
    },
    generateBundle() { this.emitFile({ type: 'asset', fileName: 'assets/git-history.json', source }) },
  }
}
