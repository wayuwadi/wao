import type { ModuleId } from '@/types'

export type FeatureCategory = 'ai' | 'knowledge' | 'planning' | 'life' | 'system'

export interface FeatureCatalogEntry {
  id: ModuleId
  category: FeatureCategory
  summary: string
  description: string
  tone: 'violet' | 'mint' | 'amber' | 'blue' | 'rose' | 'slate'
  recommended?: boolean
}

export interface ReleaseHighlight {
  featureId: ModuleId
  eyebrow: string
  title: string
  summary: string
  points: string[]
}

export interface ReleaseEdition {
  id: string
  publishedOn: string
  title: string
  intro: string
  highlights: ReleaseHighlight[]
}

export const FEATURE_CATEGORIES: { id: FeatureCategory; label: string }[] = [
  { id: 'ai', label: 'AI 与协作' },
  { id: 'knowledge', label: '记录与知识' },
  { id: 'planning', label: '计划与行动' },
  { id: 'life', label: '生活与设备' },
  { id: 'system', label: '系统与安全' },
]
