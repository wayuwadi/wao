import { check, type DownloadEvent, type Update } from '@tauri-apps/plugin-updater'
import { relaunch } from '@tauri-apps/plugin-process'
import { updateProgress } from './updateProgress'

export type UpdatePhase = 'idle' | 'checking' | 'available' | 'downloading' | 'ready' | 'current' | 'error'

export interface UpdateState {
  phase: UpdatePhase
  version?: string
  notes?: string
  progress?: number
  message?: string
}

export async function findUpdate(): Promise<{ update: Update | null; state: UpdateState }> {
  try {
    const update = await check()
    if (!update) return { update: null, state: { phase: 'current', message: '已经是最新版本' } }
    return {
      update,
      state: {
        phase: 'available',
        version: update.version,
        notes: typeof update.body === 'string' ? update.body : undefined,
        message: `发现新版本 ${update.version}`,
      },
    }
  } catch (error) {
    return {
      update: null,
      state: { phase: 'error', message: error instanceof Error ? error.message : '检查更新失败' },
    }
  }
}

export async function installUpdate(
  update: Update,
  onState: (state: UpdateState) => void,
): Promise<void> {
  let downloaded = 0
  let total: number | undefined
  try {
    onState({ phase: 'downloading', version: update.version, message: '正在下载更新…', progress: 0 })
    await update.downloadAndInstall((event: DownloadEvent) => {
      if (event.event === 'Started') total = event.data.contentLength ?? undefined
      if (event.event === 'Progress') downloaded += event.data.chunkLength
      if (event.event === 'Finished') {
        onState({ phase: 'ready', version: update.version, message: '更新已安装，正在重启…', progress: 100 })
        return
      }
      const progress = updateProgress(downloaded, total)
      onState({ phase: 'downloading', version: update.version, message: '正在下载更新…', progress })
    })
    await relaunch()
  } catch (error) {
    onState({
      phase: 'error',
      version: update.version,
      message: error instanceof Error ? error.message : '更新安装失败，请稍后重试',
    })
  }
}
