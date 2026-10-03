import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

/** 只读取制品 HEAD 可达的提交；不读取正文、作者、远端地址或未合并分支。 */
export function readGitRelease(cwd, expectedSha, inputFile) {
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
  const edition = {
    id: `git-release-${sha}`, publishedOn,
    title: `版本更新 · ${sha.slice(0, 8)}`,
    intro: `最新提交：${latest.subject.slice(0, 450)}。全部提交可在最近更新中查看；精选介绍保留在下方。`,
    highlights: [{ featureId: 'changelog', eyebrow: '自动更新记录', title: '当前版本的最近提交',
      summary: '以下记录随当前页面版本自动生成，包含功能、修复、文档与测试提交。提交时间不等于上线时间。',
      points: commits.slice(0, 5).map(item => `${item.sha.slice(0, 8)} · ${item.subject}`.slice(0, 300)) }],
  }
  return { edition, history: { buildSha: sha, commits } }
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
