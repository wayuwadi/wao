import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import NoteComposer from '@/components/notes/NoteComposer.vue'

const reads = vi.hoisted(() => ({
  areas: vi.fn(),
  projects: vi.fn(),
  tasks: vi.fn(),
  other: vi.fn(),
}))

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  return {
    ...actual,
    fetchAreas: () => reads.areas(),
    fetchProjects: () => reads.projects(),
    fetchTasks: () => reads.tasks(),
    fetchKbs: () => reads.other(),
    fetchKbGroups: () => reads.other(),
    fetchFiles: () => reads.other(),
    fetchVault: () => reads.other(),
    fetchSchedule: () => reads.other(),
    fetchSuggestions: () => reads.other(),
    fetchGoals: () => reads.other(),
  }
})

const Editor = defineComponent({
  props: ['modelValue', 'readonly'],
  emits: ['update:modelValue'],
  template:
    '<textarea :value="modelValue" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})

describe('部分领域读取失败时的独立写路径', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useSessionStore().accept({
      member: { id: 'wg', name: '娃哥', initial: '哥', color: '#4358d6', role: '成员' },
    } as never)
    reads.areas.mockReset().mockResolvedValue([])
    reads.projects.mockReset().mockRejectedValue(new Error('timeout of 10000ms exceeded'))
    reads.tasks.mockReset().mockResolvedValue([])
    reads.other.mockReset().mockResolvedValue([])
  })

  it('项目读取超时仍允许小记走自己的真实保存接口', async () => {
    const data = useDataStore()
    await data.hydrate()

    expect(data.apiLive).toBe(true)
    expect(data.loadErrors.projects).toBe('timeout of 10000ms exceeded')

    const addNote = vi.spyOn(data, 'addNote').mockResolvedValue('note-1')
    const wrapper = mount(NoteComposer, {
      global: { stubs: { MarkdownEditor: Editor } },
    })
    await wrapper.find('textarea').setValue('部分数据失败也不能阻塞小记')
    await wrapper.find('.save-note').trigger('click')
    await flushPromises()

    expect(addNote).toHaveBeenCalledOnce()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('全部启动读取都失败时仍禁止假保存', async () => {
    const unavailable = new Error('服务不可达')
    reads.areas.mockRejectedValue(unavailable)
    reads.projects.mockRejectedValue(unavailable)
    reads.tasks.mockRejectedValue(unavailable)
    reads.other.mockRejectedValue(unavailable)

    const data = useDataStore()
    await data.hydrate()

    expect(data.apiLive).toBe(false)
  })
})
