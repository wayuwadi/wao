import { http, type ApiResponse } from './http'
import type { NoteAiDefaults } from '@/stores/preferences'
export interface NoteAiSettings extends NoteAiDefaults {
  thinking?: boolean
  thinkingEpoch?: number
  model: string
  reasoningEffort: string
  polishModel?: string
  polishReasoningEffort?: string
  titlePrompt: string
  imagePrompt: string
  polishPrompt: string
  titleEpoch: number
  imageEpoch: number
  polishEpoch: number
}
export interface NoteAiSettingsView {
  settings: NoteAiSettings
  version: string
  configured: boolean
  availability: string
  defaultPrompts?: Pick<NoteAiSettings, 'titlePrompt' | 'polishPrompt' | 'imagePrompt'>
}
export async function fetchNoteAiSettings() {
  return (await http.get<ApiResponse<NoteAiSettingsView>>('/api/docs/notes/ai-settings')).data.data
}
export async function saveNoteAiSettings(settings: NoteAiSettings, expectedVersion: string) {
  return (
    await http.put<ApiResponse<NoteAiSettingsView>>('/api/docs/notes/ai-settings', {
      settings,
      expectedVersion,
    })
  ).data.data
}
