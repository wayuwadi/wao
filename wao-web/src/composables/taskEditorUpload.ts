import { useDataStore } from '@/stores/data'
import type { FileItem } from '@/types'

/** 复用任务附件上传策略；编辑器自行展示失败和重试状态。 */
export function useTaskEditorUpload() {
  const data = useDataStore()
  return async (file: File, done: (item: FileItem | null) => void) => {
    try { done(await data.uploadFile(file)) }
    catch { done(null) }
  }
}
