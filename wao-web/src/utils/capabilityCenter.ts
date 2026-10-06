import type { WorkspaceCapability, WorkspaceMcpToken } from '@/api/workspaceMcp'

const actionAliases: Record<string, string> = {
  create_note: '发小记 写小记 记录小记',
  list_notes: '查小记 找小记',
  list_tasks: '查任务 找任务',
  create_task: '建任务 新任务',
  record_daily_note: '记录进展 每日进展',
}

export function matchesCapability(capability: WorkspaceCapability, query: string) {
  const text = [
    capability.title,
    capability.description,
    capability.name,
    capability.cli,
    capability.mcp,
    actionAliases[capability.name] ?? '',
  ]
    .join(' ')
    .toLowerCase()
  return query
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .every((word) => text.includes(word))
}

export function connectionState(token: WorkspaceMcpToken) {
  if (
    token.lastVerificationFailedAt &&
    (!token.lastVerifiedAt || token.lastVerificationFailedAt > token.lastVerifiedAt)
  )
    return '验证失败'
  return token.lastVerifiedAt ? '已验证' : '尚未验证'
}

export const grantPresets = [
  { id: 'read', title: '只选读取', description: '查询当前可访问内容。' },
  { id: 'notes', title: '记录小记', description: '查询、创建小记与维护标签。' },
  { id: 'projects', title: '维护项目', description: '管理项目、任务与子任务。' },
  { id: 'daily', title: '日常完整', description: '当前全部工作区操作，包含写入；不含 HTML 发布。' },
] as const

export function presetPermissions(id: string, capabilities: WorkspaceCapability[]) {
  return capabilities
    .filter((capability) => {
      if (capability.name === 'publish_html') return false
      if (id === 'read') return capability.readOnly
      if (id === 'notes')
        return [
          'list_notes',
          'list_note_tags',
          'get_document',
          'create_note',
          'update_note_tags',
        ].includes(capability.name)
      if (id === 'projects')
        return (
          ['projects', 'tasks'].includes(capability.group) &&
          ![
            'get_todo_day',
            'list_todo_days',
            'get_daily_operation',
            'schedule_task',
            'record_daily_note',
          ].includes(capability.name)
        )
      return id === 'daily'
    })
    .map((capability) => capability.name)
}
