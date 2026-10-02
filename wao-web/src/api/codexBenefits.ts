import { fetchPreference, savePreference } from '@/api/favorites'

export interface CodexBenefits {
  resetCards: number | null
  proExpiresOn: string | null
}

/** 首页摘要只展示已保存值，编辑草稿仍由权益面板持有。 */
export interface CodexBenefitsSnapshot {
  saved: CodexBenefits
  loading: boolean
  loaded: boolean
  error: string
  daysLeft: number | null
  expiryLabel: string
}

const KEY = 'codex-benefits-v1'
export const emptyCodexBenefits = (): CodexBenefits => ({ resetCards: null, proExpiresOn: null })

export function validBenefitDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

export async function fetchCodexBenefits(): Promise<CodexBenefits> {
  const raw = await fetchPreference(KEY)
  if (!raw) return emptyCodexBenefits()
  const value = JSON.parse(raw) as CodexBenefits
  if (!value || (value.resetCards !== null && (!Number.isInteger(value.resetCards) || value.resetCards < 0 || value.resetCards > 9999))
    || (value.proExpiresOn !== null && (typeof value.proExpiresOn !== 'string' || !validBenefitDate(value.proExpiresOn)))) {
    throw new Error('权益记录格式异常，请重试或重新配置')
  }
  return value
}

export async function saveCodexBenefits(value: CodexBenefits): Promise<void> {
  await savePreference(KEY, JSON.stringify(value))
}

/** 按本地日历日计算，避免时区和夏令时把明天显示成今天。 */
export function benefitDaysLeft(expiresOn: string, now: Date): number {
  return Math.round((Date.parse(`${expiresOn}T00:00:00Z`)
    - Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())) / 86_400_000)
}
