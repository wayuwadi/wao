import type { AiEngine } from '@/types'

/** 页面展示 AI 工具的统一口径：名称、图标和主色。顺序即选择器里的排列顺序。 */
export const AI_ENGINES: Array<{ id: AiEngine; label: string; icon: string; color: string; hint: string }> = [
  { id: 'codex', label: 'Codex', icon: 'ri-openai-fill', color: '#3650b4', hint: 'OpenAI Codex CLI，装在娃哥 / 娃弟的 Windows 电脑上' },
  { id: 'claude', label: 'Claude Code', icon: 'ri-claude-fill', color: '#b4622f', hint: 'Anthropic Claude Code CLI，装在 Linux 主节点上' },
]

export function engineLabel(engine: string | null | undefined) {
  return AI_ENGINES.find((item) => item.id === engine)?.label ?? engine ?? '未知工具'
}

export function engineIcon(engine: string | null | undefined) {
  return AI_ENGINES.find((item) => item.id === engine)?.icon ?? 'ri-robot-2-line'
}

export function engineColor(engine: string | null | undefined) {
  return AI_ENGINES.find((item) => item.id === engine)?.color ?? '#5b6072'
}
