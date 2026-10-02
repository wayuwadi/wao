import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import { freshProgress, progressKey, readProgress, type RelaxProgress } from './progress'
export function useRelaxProgress(member: Ref<string>) {
  const unsaved = new Map<string, RelaxProgress>()
  const progress = ref(freshProgress()),
    status = ref<'loading' | 'saved' | 'saving' | 'error' | 'conflict'>('loading'),
    message = ref(''),
    revision = ref(0)
  let key = progressKey(member.value),
    loading = false,
    dirty = false,
    timer: ReturnType<typeof setTimeout> | undefined
  function save(force = false) {
    clearTimeout(timer)
    timer = undefined
    if (!dirty && !force) return
    if ((status.value === 'conflict' || status.value === 'error') && !force) return
    status.value = 'saving'
    try {
      localStorage.setItem(key, JSON.stringify(progress.value))
      dirty = false
      unsaved.delete(key)
      status.value = 'saved'
      message.value = ''
    } catch {
      status.value = 'error'
      message.value = '本机存储暂不可用，改动仍在本页，请重试保存。'
    }
  }
  function load(recover = true) {
    loading = true
    const cached = recover ? unsaved.get(key) : undefined
    if (cached) {
      progress.value = cached
      dirty = true
      status.value = 'error'
      message.value = '已恢复这个账号尚未保存的本页进度，请重试保存。'
      revision.value++
      loading = false
      return
    }
    try {
      progress.value = readProgress(localStorage.getItem(key))
      status.value = 'saved'
      message.value = ''
    } catch {
      progress.value = freshProgress()
      status.value = 'error'
      message.value = '无法读取原存档，已保留原存档内容。可暂时游玩，或重试读取。'
    }
    dirty = false
    revision.value++
    loading = false
  }
  watch(
    progress,
    () => {
      if (loading) return
      dirty = true
      if (status.value === 'conflict' || status.value === 'error') return
      status.value = 'saving'
      if (!timer) timer = setTimeout(() => save(), 400)
    },
    { deep: true, flush: 'sync' },
  )
  watch(member, () => {
    save()
    if (dirty) unsaved.set(key, progress.value)
    key = progressKey(member.value)
    load()
  })
  function storage(e: StorageEvent) {
    if (e.key === key || e.key === null) {
      clearTimeout(timer)
      timer = undefined
      status.value = 'conflict'
      message.value = '另一个标签页更新了进度。本页内容已保留，请选择要保留的版本。'
    }
  }
  const flush = () => save()
  onMounted(() => {
    load()
    window.addEventListener('storage', storage)
    window.addEventListener('pagehide', flush)
    document.addEventListener('visibilitychange', flush)
  })
  onBeforeUnmount(() => {
    save()
    window.removeEventListener('storage', storage)
    window.removeEventListener('pagehide', flush)
    document.removeEventListener('visibilitychange', flush)
  })
  return {
    progress,
    status,
    message,
    revision,
    save: () => save(true),
    reload: () => {
      unsaved.delete(key)
      load(false)
    },
  }
}
