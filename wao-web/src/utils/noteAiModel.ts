export const DEFAULT_NOTE_MODEL = 'gpt-6-astra'

/** 精确保留已选择模型；可用性由真实目录校验，不因前端版本落后静默换模型。 */
export function resolveNoteModel(value: string | null | undefined): string {
  const model = value?.trim() || ''
  return model || DEFAULT_NOTE_MODEL
}
