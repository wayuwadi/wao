import axios from 'axios'
import type { ReleaseEdition, ReleaseHighlight } from '@/types/featureExplorer'

function text(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= max
}

/** 清单只能提供纯文本说明和功能 id；路由仍由当前制品的功能地图决定。 */
export function parseReleaseEdition(value: unknown): ReleaseEdition | null {
  if (!value || typeof value !== 'object') return null
  const edition = value as Record<string, unknown>
  if (
    (edition.attention !== undefined && edition.attention !== 'feature' && edition.attention !== 'maintenance') ||
    !text(edition.id, 160) ||
    !text(edition.publishedOn, 30) ||
    !text(edition.title, 160) ||
    !text(edition.intro, 1000) ||
    !Array.isArray(edition.highlights) ||
    edition.highlights.length > 20
  )
    return null
  const highlights: ReleaseHighlight[] = []
  for (const raw of edition.highlights) {
    if (!raw || typeof raw !== 'object') return null
    const item = raw as Record<string, unknown>
    if (
      !text(item.featureId, 100) ||
      !/^[a-z0-9-]+$/.test(item.featureId) ||
      !text(item.eyebrow, 80) ||
      !text(item.title, 160) ||
      !text(item.summary, 1000) ||
      !Array.isArray(item.points) ||
      item.points.length > 10 ||
      !item.points.every((point) => text(point, 300))
    )
      return null
    highlights.push({
      featureId: item.featureId as ReleaseHighlight['featureId'],
      eyebrow: item.eyebrow,
      title: item.title,
      summary: item.summary,
      points: item.points as string[],
    })
  }
  return {
    ...(edition.attention ? { attention: edition.attention as 'feature' | 'maintenance' } : {}),
    id: edition.id,
    publishedOn: edition.publishedOn,
    title: edition.title,
    intro: edition.intro,
    highlights,
  }
}

/** 读取同一版本清单中的目标版本和说明，保留版本匹配检查所需的身份。 */
export async function fetchWebRelease(): Promise<{
  version: string
  release: ReleaseEdition | null
}> {
  const { data } = await axios.get('/pluginWebUpdateNotice/web_version_by_plugin.json', {
    params: { t: Date.now() },
    timeout: 10000,
    headers: { 'Cache-Control': 'no-cache' },
  })
  if (!text(data?.version, 160)) throw new Error('版本信息无效，请稍后重试')
  if (data.release != null && !parseReleaseEdition(data.release))
    throw new Error('更新说明格式无效，请重试')
  return { version: data.version.trim(), release: parseReleaseEdition(data.release) }
}

/** 读取部署时生成的版本标记，绕过缓存，不把登录页或错误页当作版本。 */
export async function fetchWebVersion(): Promise<string> {
  const { data } = await axios.get('/pluginWebUpdateNotice/web_version_by_plugin.json', {
    params: { t: Date.now() },
    timeout: 10000,
    headers: { 'Cache-Control': 'no-cache' },
  })
  if (typeof data?.version !== 'string' || !data.version.trim())
    throw new Error('版本信息无效，请稍后重试')
  return data.version.trim()
}

export interface GitReleaseHistory {
  buildSha: string
  commits: { sha: string; committedAt: string; subject: string }[]
}

/** 从当前不可变制品读取所有可达提交，不将新部署的记录混入旧页面。 */
export async function fetchGitReleaseHistory(expectedSha: string): Promise<GitReleaseHistory> {
  const { data } = await axios.get(`${import.meta.env.BASE_URL}assets/git-history.json`, { timeout: 10000 })
  if (data?.buildSha !== expectedSha || !Array.isArray(data.commits) || !data.commits.length)
    throw new Error('提交记录与当前页面版本不一致，请刷新后重试')
  const seen = new Set<string>()
  for (const item of data.commits) {
    if (!item || !/^[0-9a-f]{40}$/.test(item.sha) || seen.has(item.sha) ||
        !text(item.subject, 10000) || !text(item.committedAt, 50) || !Number.isFinite(Date.parse(item.committedAt)))
      throw new Error('提交记录格式无效，请重试')
    seen.add(item.sha)
  }
  if (data.commits[0].sha !== expectedSha) throw new Error('提交记录缺少当前版本，请重试')
  return data as GitReleaseHistory
}
