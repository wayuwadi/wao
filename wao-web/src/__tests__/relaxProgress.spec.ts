import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { defineComponent, ref, nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useRelaxProgress } from '@/components/relax/useRelaxProgress'
import { freshProgress, progressKey, readProgress } from '@/components/relax/progress'
let wrapper: VueWrapper, store: ReturnType<typeof useRelaxProgress>
const member = ref('')
function setup() {
  wrapper = mount(
    defineComponent({
      setup() {
        store = useRelaxProgress(member)
        return () => null
      },
    }),
  )
}
beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  member.value = ''
})
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  vi.useRealTimers()
})
it('输入自动保存，切换账号先保存旧草稿，刷新恢复独立进度', async () => {
  setup()
  store.progress.value.journal.text = '访客写到一半'
  expect(store.status.value).toBe('saving')
  member.value = 'member'
  await nextTick()
  expect(readProgress(localStorage.getItem(progressKey(''))).journal.text).toBe('访客写到一半')
  expect(store.progress.value.journal.text).toBe('')
  store.progress.value.world = 'orbit'
  await vi.advanceTimersByTimeAsync(450)
  expect(store.status.value).toBe('saved')
  member.value = ''
  await nextTick()
  expect(store.progress.value.journal.text).toBe('访客写到一半')
})
it('其他页面更新不覆盖本地输入，选择保存本页后才覆盖', async () => {
  setup()
  store.progress.value.journal.text = '未保存的输入'
  const remote = freshProgress()
  remote.journal.text = '远端草稿'
  localStorage.setItem(progressKey(''), JSON.stringify(remote))
  window.dispatchEvent(
    new StorageEvent('storage', { key: progressKey(''), newValue: JSON.stringify(remote) }),
  )
  await vi.advanceTimersByTimeAsync(1000)
  expect(store.status.value).toBe('conflict')
  expect(store.progress.value.journal.text).toBe('未保存的输入')
  expect(readProgress(localStorage.getItem(progressKey(''))).journal.text).toBe('远端草稿')
  store.save()
  expect(readProgress(localStorage.getItem(progressKey(''))).journal.text).toBe('未保存的输入')
})
it('保存失败仍保留本页草稿，换号后再回来仍可重试', async () => {
  setup()
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('quota')
  })
  store.progress.value.journal.text = '重要草稿'
  await vi.advanceTimersByTimeAsync(500)
  expect(store.status.value).toBe('error')
  member.value = 'other'
  await nextTick()
  member.value = ''
  await nextTick()
  expect(store.progress.value.journal.text).toBe('重要草稿')
  spy.mockRestore()
  store.save()
  expect(store.status.value).toBe('saved')
  expect(readProgress(localStorage.getItem(progressKey(''))).journal.text).toBe('重要草稿')
})
it('损坏存档读取失败时不自动覆盖原文', async () => {
  localStorage.setItem(progressKey(''), '{bad')
  setup()
  expect(store.status.value).toBe('error')
  store.progress.value.journal.text = '临时编辑'
  await vi.advanceTimersByTimeAsync(500)
  window.dispatchEvent(new Event('pagehide'))
  expect(localStorage.getItem(progressKey(''))).toBe('{bad')
})
