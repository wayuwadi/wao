export const DEFAULT_NOTE_MODEL = 'gpt-5.6-sol'

export const NOTE_MODELS = ['gpt-5.6-sol', 'gpt-5.6-luna', 'gpt-5.6-terra'] as const

/** 旧 GPT-6 偏好在当前 ChatGPT 登录态不可执行，读取时回退到已验证的 Sol。 */
export function resolveNoteModel(value: string | null | undefined): string {
  const model = value?.trim() || ''
  return NOTE_MODELS.some(option => option === model) ? model : DEFAULT_NOTE_MODEL
}
