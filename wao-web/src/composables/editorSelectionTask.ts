import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'

export interface EditorTaskSource {
  id: string
  title: string
  url?: string
  docId?: string
  projectId?: string
}

/** 通用编辑器划词收集：只准备待办草稿，确认创建沿用任务表单的持久化与重试。 */
export async function openEditorSelectionTask(text: string, source?: EditorTaskSource) {
  const selected = text.trim()
  if (!selected) return
  const session = useSessionStore()
  const ui = useUiStore()
  const userId = session.userId
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(selected))
  const selectionId = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
  // 异步准备期间切换成员时，不把上一个成员的选区放进新会话。
  if (session.userId !== userId) throw new Error('成员已切换，请重新选择文字')
  const sourceUrl = source?.url ?? ''
  ui.openModal('task', source?.projectId ?? '')
  ui.modalTaskSeed = {
    draftKey: `wao-document-task-draft:${userId}:${source?.id || 'editor'}:${selectionId}`,
    title: selected.replace(/\s+/g, ' ').slice(0, 200),
    desc: sourceUrl ? `${selected}\n\n[查看来源文档](${sourceUrl})` : selected,
    sourceTitle: source?.title || '编辑器选中文字',
    sourceUrl,
    sourceDocId: source?.docId,
  }
}
