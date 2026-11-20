import { fetchDrawingLayout, startDrawingLayout } from '@/api/drawings'
import type { describeDrawingForAi } from './drawingTidy'

export type DrawingAiGuidance = {
  direction: 'right' | 'down'
  groups: { title: string; ids: string[] }[]
  main: string[]
  summary: string
}

type Graph = ReturnType<typeof describeDrawingForAi>

export function parseDrawingAiGuidance(result: string, graph: Graph): DrawingAiGuidance {
  const invalid = () => new Error('AI 未返回有效的排版建议，请重新预览。')
  let response: Partial<DrawingAiGuidance>
  try {
    response = JSON.parse(result.trim().replace(/^```(?:json)?\s*([\s\S]+?)\s*```$/i, '$1')) as Partial<DrawingAiGuidance>
  } catch {
    throw invalid()
  }
  if (!response || (response.direction !== 'right' && response.direction !== 'down') ||
      !Array.isArray(response.groups) || !Array.isArray(response.main)) throw invalid()
  const available = new Set(graph.nodes.map(({ id }) => id))
  const links = new Set(graph.links.map(({ id }) => id))
  const groups = response.groups.slice(0, 12).flatMap((group) => {
    if (!group || typeof group.title !== 'string' || !group.title.trim() || !Array.isArray(group.ids)) return []
    const ids = group.ids.filter((id): id is string => typeof id === 'string' && available.delete(id))
    return ids.length ? [{ title: group.title.replace(/\s+/g, ' ').trim().slice(0, 42), ids }] : []
  })
  return {
    direction: response.direction, groups,
    main: [...new Set(response.main.filter((id): id is string => typeof id === 'string' && links.has(id)))],
    summary: typeof response.summary === 'string' ? response.summary.trim().slice(0, 120) : '',
  }
}

export async function requestDrawingAiGuidance(graph: Graph, signal: AbortSignal) {
  const data = JSON.stringify(graph)
  if (data.length > 65_000 || graph.links.length > 230) throw new Error('画板内容较多，请分批框选后整理。')
  const requestId = crypto.randomUUID().replace(/-/g, '')
  let job = await startDrawingLayout(data, requestId, signal)
  while (job.status === 'queued' || job.status === 'running') {
    await new Promise((resolve) => setTimeout(resolve, 2000))
    signal.throwIfAborted()
    job = await fetchDrawingLayout(job.id, signal)
  }
  signal.throwIfAborted()
  if (job.status !== 'succeeded' || !job.result) throw new Error(job.message || 'AI 未返回排版建议，请重试。')
  return parseDrawingAiGuidance(job.result, graph)
}
