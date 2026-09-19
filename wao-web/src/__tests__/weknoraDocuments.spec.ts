import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useWeKnoraDocuments } from '@/composables/weknoraDocuments'
import WeKnoraDocumentStatus from '@/components/weknora/WeKnoraDocumentStatus.vue'
import { getDocumentSyncStates } from '@/api/weknoraSync'
vi.mock('@/api/weknoraSync', () => ({ getDocumentSyncStates: vi.fn() }))
afterEach(() => { vi.clearAllMocks(); vi.useRealTimers() })
describe('WeKnora 文档同步状态', () => {
  it('旧文档迟到响应不能覆盖新文档，列表请求合并且卸载停止轮询', async () => {
    vi.useFakeTimers()
    const ids = ref(['a', 'b', 'a'])
    let resolveOld!: (value: never[]) => void
    vi.mocked(getDocumentSyncStates).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
    vi.mocked(getDocumentSyncStates).mockResolvedValueOnce([{ id: 'c', state: 'pending', message: '', sourceUrl: '', knowledgeUrl: '' }])
    let sync!: ReturnType<typeof useWeKnoraDocuments>
    const wrapper = mount(defineComponent({ setup() { sync = useWeKnoraDocuments(() => ids.value, () => 1); return {} }, template: '<div />' }))
    expect(getDocumentSyncStates).toHaveBeenCalledWith(['a', 'b'])
    ids.value = ['c']
    await flushPromises()
    resolveOld([])
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(sync.states.value.c?.state).toBe('pending')
    expect(getDocumentSyncStates).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(30000)
    expect(getDocumentSyncStates).toHaveBeenCalledTimes(2)
  })
  it('本机草稿不展示旧副本已处理，失败状态可重试', async () => {
    const wrapper = mount(WeKnoraDocumentStatus, { props: { dirty: true, state: { id: 'd', state: 'processed', message: 'Wiki 待核对', sourceUrl: '/source', knowledgeUrl: '/weknora/' } } })
    expect(wrapper.text()).toContain('修改保存后同步')
    expect(wrapper.text()).not.toContain('文档已处理')
    await wrapper.setProps({ dirty: false, error: '同步状态读取失败' })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    wrapper.unmount()
  })
})
