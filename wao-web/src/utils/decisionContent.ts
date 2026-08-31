export interface DecisionContent {
  situation: string
  choice: string
  reason: string
}

/** 把专用决策表单存成普通 Markdown，确保记忆中心和 MCP 仍能直接阅读。 */
export function formatDecisionContent(value: DecisionContent) {
  const sections = [
    value.situation.trim() ? `## 适用情境\n${value.situation.trim()}` : '',
    `## 决定\n${value.choice.trim()}`,
    value.reason.trim() ? `## 原因\n${value.reason.trim()}` : '',
  ]
  return sections.filter(Boolean).join('\n\n')
}

/** 兼容记忆中心中直接编辑的旧自由正文，不让专用页面丢失可见内容。 */
export function parseDecisionContent(content: string): DecisionContent {
  const result: DecisionContent = { situation: '', choice: '', reason: '' }
  const matches = [...content.matchAll(/^## (适用情境|决定|原因)\s*\n([\s\S]*?)(?=\n\n## |$)/gm)]
  for (const match of matches) {
    const key: keyof DecisionContent = match[1] === '适用情境'
      ? 'situation'
      : match[1] === '决定' ? 'choice' : 'reason'
    result[key] = (match[2] || '').trim()
  }
  if (!matches.length) result.choice = content.trim()
  return result
}
