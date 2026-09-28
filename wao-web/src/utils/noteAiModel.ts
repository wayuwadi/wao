export const DEFAULT_NOTE_MODEL = 'gpt-5.6-sol'

export const NOTE_MODELS = ['gpt-5.6-sol', 'gpt-6-sol', 'gpt-6-luna', 'gpt-5.6-luna', 'gpt-5.6-terra'] as const

/** 未开放的旧模型偏好回退到默认模型；已验证的 GPT-6 Sol/Luna 保持原选择。 */
export function resolveNoteModel(value: string | null | undefined): string {
  const model = value?.trim() || ''
  return NOTE_MODELS.some(option => option === model) ? model : DEFAULT_NOTE_MODEL
}
