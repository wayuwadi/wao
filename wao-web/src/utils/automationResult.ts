/** Legacy records have free-form messages. Only explicit outcomes may imply success. */
export function automationResult(result: string, aiRunId?: string | null) {
  const text = result.trim()
  const neutral = { color: 'var(--wao-text-weak)', bg: 'var(--wao-bg-sub)' }
  const pending = { color: 'var(--wao-primary-text)', bg: 'var(--wao-primary-tint)' }
  if (/^部分成功/.test(text)) return { ...neutral, label: '部分成功' }
  if (/^失败(?:$|[ ·：:])/.test(text))
    return { color: 'var(--wao-danger-strong)', bg: 'var(--wao-danger-bg)', label: '失败' }
  if (aiRunId || /^(已创建 AI Run|成功 · 已创建 AI Run|已提交)/.test(text))
    return { ...pending, label: '已提交执行' }
  if (/^已入队/.test(text)) return { ...pending, label: '已入队' }
  if (/^(跳过|已跳过)/.test(text)) return { ...neutral, label: '已跳过' }
  if (/^(取消|已取消)/.test(text)) return { ...neutral, label: '已取消' }
  if (/^成功(?:$|[ ·：:])/.test(text))
    return { color: 'var(--wao-success)', bg: 'var(--wao-success-bg)', label: '成功' }
  return { ...neutral, label: '结果待确认' }
}
