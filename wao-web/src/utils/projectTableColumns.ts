export const PROJECT_TABLE_COLUMNS = [
  { key: 'number', label: 'ID', width: 64 }, { key: 'title', label: '标题', width: 0 },
  { key: 'group', label: '分组', width: 112 }, { key: 'priority', label: '优先级', width: 64 },
  { key: 'status', label: '状态', width: 92 }, { key: 'owner', label: '负责人', width: 88 },
  { key: 'creator', label: '创建者', width: 88 }, { key: 'created', label: '创建日期', width: 96 },
  { key: 'start', label: '计划开始', width: 124 }, { key: 'due', label: '截止日期', width: 124 },
] as const
export type ProjectTableColumn = typeof PROJECT_TABLE_COLUMNS[number]['key']
export const DEFAULT_PROJECT_TABLE_COLUMNS: ProjectTableColumn[] = ['title', 'priority', 'status', 'owner', 'start', 'due']
export function readProjectTableColumns(raw: string | null): ProjectTableColumn[] {
  try {
    const value: unknown = JSON.parse(raw ?? 'null')
    if (!Array.isArray(value) || !value.length || value.some(key => !PROJECT_TABLE_COLUMNS.some(column => column.key === key))) return [...DEFAULT_PROJECT_TABLE_COLUMNS]
    return PROJECT_TABLE_COLUMNS.filter(column => column.key === 'title' || value.includes(column.key)).map(column => column.key)
  } catch { return [...DEFAULT_PROJECT_TABLE_COLUMNS] }
}
