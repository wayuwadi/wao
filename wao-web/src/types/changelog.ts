export type ChangelogCategory = 'feature' | 'improvement' | 'fix' | 'ai' | 'workflow' | 'system'

export interface ChangelogEntry {
  id: string
  version: string
  /** 按产品发布日分组，格式 YYYY-MM-DD，不受浏览器时区影响。 */
  publishedOn: string
  title: string
  summary: string
  categories: ChangelogCategory[]
  highlights: string[]
  details: string
  tags: string[]
  source: {
    kind: 'manual' | 'commit' | 'pr'
    aiGenerated: boolean
    /** 后续接入时由服务端提供真实来源；示例不伪造提交号。 */
    references?: { label: string; url: string }[]
  }
}

/** 页面只消费发布内容；采集、生成、审核由后续服务实现。 */
export interface ChangelogSource {
  list(): Promise<ChangelogEntry[]>
}

export const CHANGELOG_CATEGORIES: { id: ChangelogCategory; label: string; icon: string }[] = [
  { id: 'feature', label: '新功能', icon: 'ri-rocket-line' },
  { id: 'improvement', label: '优化', icon: 'ri-magic-line' },
  { id: 'fix', label: '修复', icon: 'ri-bug-line' },
  { id: 'ai', label: 'AI能力', icon: 'ri-sparkling-2-line' },
  { id: 'workflow', label: '工作流', icon: 'ri-git-merge-line' },
  { id: 'system', label: '系统', icon: 'ri-settings-3-line' },
]
