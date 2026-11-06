import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useDataStore } from './data'
import { useSessionStore } from './session'
import type { FileUploadProgress } from '@/api/board'

export interface UploadAttempt {
  id: number
  /** Keep display metadata after completion, without retaining the file's byte contents. */
  file: Pick<File, 'name' | 'size' | 'type' | 'lastModified'>
  kind: 'img' | 'files'
  state: 'waiting' | 'uploading' | 'success' | 'failed'
  reason: string
  retryable: boolean
  limit?: number
  projectId?: string
  source?: string
  progress?: number
}

/** Browser-session queue: survives page disposal, never persists file contents or crosses accounts. */
export const useUploadStore = defineStore('uploads', () => {
  const data = useDataStore(),
    session = useSessionStore()
  const uploadAttempts = ref<UploadAttempt[]>([])
  // Files are deliberately non-reactive and never persisted; only retryable entries keep bytes.
  const pendingFiles = new Map<number, File>()
  const checkingUploads = ref(false),
    uploadCheckMessage = ref('')
  const uploading = computed(
    () =>
      uploadAttempts.value.filter((item) => item.state === 'waiting' || item.state === 'uploading')
        .length,
  )
  const failedUploads = computed(() =>
    uploadAttempts.value.filter((item) => item.state === 'failed'),
  )
  const successfulUploads = computed(
    () => uploadAttempts.value.filter((item) => item.state === 'success').length,
  )
  let generation = 0,
    nextId = 0,
    running = false
  let activeUpload: AbortController | undefined
  watch(
    () => [session.account?.accountId, session.userId],
    () => {
      generation++
      // Prevent a request still awaiting node preparation from being sent under the next account.
      // A request already received by the server may still finish there; this is not a rollback.
      activeUpload?.abort()
      activeUpload = undefined
      running = false
      pendingFiles.clear()
      uploadAttempts.value = []
      checkingUploads.value = false
      uploadCheckMessage.value = ''
    },
    { flush: 'sync' },
  )

  async function pump() {
    if (running) return
    running = true
    const current = generation
    try {
      while (current === generation) {
        const item = uploadAttempts.value.find((entry) => entry.state === 'waiting')
        if (!item) break
        const file = pendingFiles.get(item.id)
        if (!file) {
          item.state = 'failed'
          item.reason = '原文件已释放，请重新选择文件。'
          item.retryable = false
          continue
        }
        item.state = 'uploading'
        item.reason = ''
        item.progress = undefined
        if (file.size === 0 || (item.kind === 'img' && !file.type.startsWith('image/'))) {
          item.state = 'failed'
          item.reason = file.size === 0
            ? `${item.kind === 'img' ? '图片' : '文件'}为空，请重新选择`
            : '请选择图片文件'
          item.retryable = false
          pendingFiles.delete(item.id)
          continue
        }
        const hardLimit = item.kind === 'img' ? data.MAX_IMAGE_BYTES : data.MAX_FILE_BYTES
        const configuredLimit = item.limit !== undefined && Number.isFinite(item.limit) && item.limit > 0
          ? item.limit * 1024 * 1024 : hardLimit
        const limitBytes = Math.min(configuredLimit, hardLimit)
        if (file.size > limitBytes) {
          item.state = 'failed'
          item.reason = `超过 ${limitBytes / 1024 / 1024}MB 上限，请压缩后重新选择文件。`
          item.retryable = false
          pendingFiles.delete(item.id)
          continue
        }
        const controller = new AbortController()
        activeUpload = controller
        try {
          const onProgress = ({ loaded, total }: FileUploadProgress) => {
            if (current !== generation || item.state !== 'uploading') return
            if (total !== undefined && total > 0 && Number.isFinite(total) && Number.isFinite(loaded)) {
              item.progress = Math.max(item.progress ?? 0, Math.min(100, Math.floor(loaded / total * 100)))
            }
          }
          const result =
            item.kind === 'img'
              ? await data.uploadImage(file, item.source || '图床', {
                requirePersisted: true, throwOnError: true, onProgress, signal: controller.signal,
                ...(item.projectId ? { projectId: item.projectId } : {}),
              })
              : await data.uploadFile(file, item.projectId, false, { throwOnError: true, onProgress, signal: controller.signal })
          if (current !== generation) return
          if (!result) throw new Error('未收到上传成功确认。请先核对列表；若未出现该文件，再重试。')
          item.state = 'success'
          item.retryable = false
          item.progress = 100
          pendingFiles.delete(item.id)
        } catch (cause) {
          if (current !== generation) return
          item.state = 'failed'
          item.reason =
            cause instanceof Error ? cause.message : '未能确认上传结果，请核对列表后重试。'
        } finally {
          if (activeUpload === controller) activeUpload = undefined
        }
      }
    } finally {
      if (current === generation) running = false
    }
  }
  function enqueue(files: File[], kind: UploadAttempt['kind'], limit?: number, context?: { projectId?: string; source?: string }) {
    for (const file of files) {
      // A repeated drop/change callback must not upload the same selected File twice.
      if (uploadAttempts.value.some(item => item.kind === kind && item.projectId === context?.projectId && pendingFiles.get(item.id) === file)) continue
      const id = ++nextId
      pendingFiles.set(id, file)
      uploadAttempts.value.push({
        id,
        file: { name: file.name, size: file.size, type: file.type, lastModified: file.lastModified },
        kind,
        projectId: context?.projectId,
        source: context?.source,
        limit,
        state: 'waiting' as const,
        reason: '',
        retryable: true,
      })
    }
    return pump()
  }
  function retryUpload(item: UploadAttempt) {
    if (!uploadAttempts.value.includes(item) || item.state !== 'failed' || !item.retryable) return
    item.state = 'waiting'
    return pump()
  }
  function clear() {
    if (uploading.value) return
    pendingFiles.clear()
    uploadAttempts.value = []
    uploadCheckMessage.value = ''
  }
  function removeUpload(item: UploadAttempt) {
    if (item.state === 'uploading') return
    pendingFiles.delete(item.id)
    uploadAttempts.value = uploadAttempts.value.filter(entry => entry.id !== item.id)
  }
  function retryFailedUploads() {
    for (const item of failedUploads.value) {
      if (item.retryable) item.state = 'waiting'
    }
    return pump()
  }
  async function checkUploadList() {
    if (checkingUploads.value) return
    const current = generation
    checkingUploads.value = true
    uploadCheckMessage.value = ''
    try {
      const results = await Promise.all([data.refreshFiles(), data.refreshImages()])
      if (current !== generation) return
      uploadCheckMessage.value = results.every(Boolean)
        ? '列表已刷新，请在文件或图床中核对后再重试。'
        : '部分列表读取失败，暂不能确认上传结果，请稍后重新核对。'
    } catch {
      if (current === generation) uploadCheckMessage.value = '列表读取失败，请重新核对。'
    } finally {
      if (current === generation) checkingUploads.value = false
    }
  }
  return {
    uploadAttempts,
    uploading,
    failedUploads,
    successfulUploads,
    checkingUploads,
    uploadCheckMessage,
    enqueue,
    retryUpload,
    retryFailedUploads,
    removeUpload,
    clear,
    checkUploadList,
  }
})
