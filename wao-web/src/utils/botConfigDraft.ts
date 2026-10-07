import type { FeishuWebhookEndpointInput } from '@/api/system'

const key = (memberId: string, id: string) => `wao-bot-config-draft-v1:${memberId}:${id}`
/** Only non-secret configuration can survive a refresh. URL and credentials stay in memory. */
export function rememberBotDraft(
  memberId: string,
  id: string,
  value: FeishuWebhookEndpointInput | null,
): boolean {
  if (!memberId) return false
  try {
    if (!value) localStorage.removeItem(key(memberId, id))
    else
      localStorage.setItem(
        key(memberId, id),
        JSON.stringify({
          name: value.name,
          description: value.description,
          enabled: value.enabled,
          systemDefault: value.systemDefault,
          captureKind: value.captureKind,
          mentionId: value.mentionId,
          mentionName: value.mentionName,
          appId: value.appId,
          chatId: value.chatId,
          revision: value.revision,
        }),
      )
    return true
  } catch {
    return false
  }
}

export function readBotDraft(memberId: string, id: string): FeishuWebhookEndpointInput | null {
  if (!memberId) return null
  try {
    const draft = JSON.parse(localStorage.getItem(key(memberId, id)) ?? 'null')
    if (
      !draft ||
      typeof draft.name !== 'string' ||
      typeof draft.description !== 'string' ||
      typeof draft.enabled !== 'boolean' ||
      typeof draft.systemDefault !== 'boolean' ||
      !['NONE', 'TASK', 'NOTE'].includes(draft.captureKind ?? 'NONE')
    )
      return null
    return {
      name: draft.name,
      description: draft.description,
      enabled: draft.enabled,
      systemDefault: draft.systemDefault,
      captureKind: draft.captureKind ?? 'NONE',
      mentionId: String(draft.mentionId ?? ''),
      mentionName: String(draft.mentionName ?? ''),
      appId: String(draft.appId ?? ''),
      chatId: String(draft.chatId ?? ''),
      revision: Number.isSafeInteger(draft.revision) ? draft.revision : undefined,
      url: '',
    }
  } catch {
    return null
  }
}
