import { execFileSync } from 'node:child_process'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

/** 只读取制品 HEAD 可达的提交；不读取正文、作者、远端地址或未合并分支。 */
export function readGitRelease(cwd, expectedSha, inputFile, guide) {
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
  const edition = productEdition(sha, publishedOn, latest.subject, guide)
  for (const item of edition.highlights) {
    if (item.demo?.image && (!/^assets\/release-guides\/[a-z0-9-]+\.(png|jpg|webp)$/.test(item.demo.image) ||
      !existsSync(join(cwd, 'wao-web/public', item.demo.image))))
      throw new Error('功能演示截图缺失或路径无效')
  }
  return { edition, history: { buildSha: sha, commits } }
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
