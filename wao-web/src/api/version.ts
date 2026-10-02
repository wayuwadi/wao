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
