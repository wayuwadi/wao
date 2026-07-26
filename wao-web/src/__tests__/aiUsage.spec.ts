import { describe, expect, it } from 'vitest'
import { formatCost, formatTokens, shortModel, usageSummary } from '@/composables/aiUsage'
import type { AiRunUsage } from '@/types'

function usage(patch: Partial<AiRunUsage> = {}): AiRunUsage {
  return {
    model: 'claude-opus-5',
    models: 'claude-opus-5',
    inputTokens: 2,
    outputTokens: 4,
    cachedInputTokens: 19_189,
    totalTokens: 19_195,
    costUsd: 0.087053,
    recorded: true,
    ...patch,
  }
}

describe('formatTokens', () => {
  it('压缩到量级，账单只需要看数量级', () => {
    expect(formatTokens(0)).toBe('0')
    expect(formatTokens(940)).toBe('940')
    expect(formatTokens(1_200)).toBe('1.2K')
    expect(formatTokens(19_195)).toBe('19K')
    expect(formatTokens(2_400_000)).toBe('2.40M')
  })
})

describe('formatCost', () => {
  it('一分钱以上保留两位小数', () => {
    expect(formatCost(0.087053)).toBe('$0.09')
    expect(formatCost(1.5)).toBe('$1.50')
    expect(formatCost(0)).toBe('$0')
  })

  it('不足一分钱改用四位小数，否则小额运行全成 $0.00', () => {
    expect(formatCost(0.0032)).toBe('$0.0032')
    expect(formatCost(0.00009)).toBe('$0.0001')
  })

  it('没有金额时返回 null，不编一个 0 出来', () => {
    expect(formatCost(null)).toBeNull()
    expect(formatCost(undefined)).toBeNull()
  })
})

describe('shortModel', () => {
  it('去掉供应商前缀和日期后缀', () => {
    expect(shortModel('claude-haiku-4-5-20251001')).toBe('claude-haiku-4-5')
    expect(shortModel('us.anthropic.claude-opus-5')).toBe('anthropic.claude-opus-5')
    expect(shortModel(null)).toBeNull()
  })
})

describe('usageSummary', () => {
  it('拼出「模型 · token · 额度」一行', () => {
    expect(usageSummary(usage())).toBe('claude-opus-5 · 19K tokens · $0.09')
  })

  it('Codex 没有花费时只显示模型和 token', () => {
    expect(usageSummary(usage({ model: 'gpt-5-codex', costUsd: null }))).toBe(
      'gpt-5-codex · 19K tokens',
    )
  })

  it('没记上账时返回 null，页面据此显示「未回传」而不是 $0.00', () => {
    expect(usageSummary(usage({ recorded: false }))).toBeNull()
    expect(usageSummary(null)).toBeNull()
  })
})
