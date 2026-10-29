import type { DataDomain } from '@/stores/data'

export const DATA_DOMAIN_LABELS: Record<DataDomain, string> = {
  areas: '领域', projects: '项目', tasks: '任务', knowledge: '知识库目录', docs: '文档',
  automation: '自动化', files: '文件', images: '图床', vault: '密码库',
  schedule: '日程', suggestions: '建议', goals: '目标',
}

const pageDomains: Record<string, DataDomain[]> = {
  home: ['tasks', 'schedule'], projects: ['areas', 'projects', 'tasks'],
  todo: ['projects', 'tasks'], calendar: ['tasks', 'schedule'], focus: ['tasks'],
  knowledge: ['knowledge', 'docs'], notes: ['knowledge', 'docs'],
  files: ['files', 'images'], automation: ['automation'], vault: ['vault'],
}

export function pageDataDomains(module: string, taskOpen = false, searchOpen = false): DataDomain[] {
  if (searchOpen) return Object.keys(DATA_DOMAIN_LABELS) as DataDomain[]
  return [...new Set([...(pageDomains[module] ?? []), ...(taskOpen ? ['tasks', 'projects', 'docs'] as DataDomain[] : [])])]
}
