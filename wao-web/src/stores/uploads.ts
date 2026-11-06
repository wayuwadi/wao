import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useDataStore } from './data'
import { useSessionStore } from './session'

export interface UploadAttempt {
  id: number
  file: File
  kind: 'img' | 'files'
  state: 'waiting' | 'uploading' | 'success' | 'failed'
  reason: string
  retryable: boolean
  limit?: number
}

/** Browser-session queue: survives page disposal, never persists file contents or crosses accounts. */
export const useUploadStore = defineStore('uploads', () => {
  const data = useDataStore(),
    session = useSessionStore()
  const uploadAttempts = ref<UploadAttempt[]>([])
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
  watch(
    () => [session.account?.accountId, session.userId],
    () => {
      generation++
      running = false
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
        item.state = 'uploading'
        item.reason = ''
        if (item.limit !== undefined && item.file.size > item.limit * 1024 * 1024) {
          item.state = 'failed'
          item.reason = `超过 ${item.limit}MB 上限，请压缩后重新选择文件。`
          item.retryable = false
          continue
        }
        try {
          const result =
            item.kind === 'img'
              ? await data.uploadImage(item.file, '图床', { requirePersisted: true })
              : await data.uploadFile(item.file)
          if (current !== generation) return
          if (!result) throw new Error('未收到上传成功确认。请先核对列表；若未出现该文件，再重试。')
          item.state = 'success'
        } catch (cause) {
          if (current !== generation) return
          item.state = 'failed'
          item.reason =
            cause instanceof Error ? cause.message : '未能确认上传结果，请核对列表后重试。'
        }
      }
    } finally {
      if (current === generation) running = false
    }
  }
  function enqueue(files: File[], kind: UploadAttempt['kind'], limit?: number) {
    uploadAttempts.value.push(
      ...files.map((file) => ({
        id: ++nextId,
        file,
        kind,
        limit,
        state: 'waiting' as const,
        reason: '',
        retryable: true,
      })),
    )
    return pump()
  }
  function retryUpload(item: UploadAttempt) {
    if (!uploadAttempts.value.includes(item) || item.state !== 'failed' || !item.retryable) return
    item.state = 'waiting'
    return pump()
  }
  function clear() {
    if (!uploading.value) uploadAttempts.value = []
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
    clear,
    checkUploadList,
  }
})
