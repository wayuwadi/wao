import type { AiRunUsage } from '@/types'

/** token 数量的紧凑写法：账单只需要看量级，逐位精确反而更难读。 */
export function formatTokens(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '0'
  if (value < 1_000) return String(value)
  if (value < 1_000_000) return `${(value / 1_000).toFixed(value < 10_000 ? 1 : 0)}K`
  return `${(value / 1_000_000).toFixed(2)}M`
}

/** 折算额度；不足一分钱的也要显示出来，否则一堆小额运行会全部显示成 $0.00。 */
export function formatCost(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) return null
  if (value === 0) return '$0'
  return value < 0.01 ? `$${value.toFixed(4)}` : `$${value.toFixed(2)}`
}

/** 模型名去掉供应商前缀和日期后缀，页面上只留可读的那一段。 */
export function shortModel(model: string | null | undefined) {
  if (!model) return null
  return model.replace(/^(anthropic|openai|us|eu)\./, '').replace(/-\d{8}$/, '')
}

/** 把后台固化的 Codex 推理强度转成用户可读标签；未知新值仍原样展示，避免误标。 */
export function reasoningEffortLabel(effort: string | null | undefined) {
  if (!effort) return null
  return ({ low: '低', medium: '中', high: '高' } as Record<string, string>)[effort] ?? effort
}

/** 一行摘要：`Claude Opus 5 · 19.2K tokens · $0.087`；没记上账时返回 null。 */
export function usageSummary(usage: AiRunUsage | null | undefined) {
  if (!usage?.recorded) return null
  const parts: string[] = []
  const model = shortModel(usage.model)
  if (model) parts.push(model)
  if (usage.totalTokens > 0) parts.push(`${formatTokens(usage.totalTokens)} tokens`)
  const cost = formatCost(usage.costUsd)
  if (cost) parts.push(cost)
  return parts.length ? parts.join(' · ') : null
}
