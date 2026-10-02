export const DEFAULT_NOTE_MODEL = 'gpt-6-astra'

export const NOTE_MODELS = ['gpt-6-astra', 'gpt-5.6-sol', 'gpt-6-sol', 'gpt-6-luna', 'gpt-5.6-luna', 'gpt-5.6-terra'] as const

/** 保留已选择模型；空白和未知旧值使用已验证的 GPT-6 Astra。 */
export function resolveNoteModel(value: string | null | undefined): string {
  const model = value?.trim() || ''
  return NOTE_MODELS.some(option => option === model) ? model : DEFAULT_NOTE_MODEL
}
