import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectMemoryPanel from '@/components/ai/ProjectMemoryPanel.vue'
import AiRunMemoryReferences from '@/components/ai/AiRunMemoryReferences.vue'
import type { ProjectMemory } from '@/types'

const memoryApi = vi.hoisted(() => ({
  fetchProjectMemories: vi.fn(),
  createProjectMemory: vi.fn(),
  updateProjectMemory: vi.fn(),
  deleteProjectMemory: vi.fn(),
}))

vi.mock('@/api/projectMemories', () => memoryApi)

const sample: ProjectMemory = {
  id: 'pm1', projectId: 'p1', category: 'verification', title: '部署验收命令',
  content: '运行线上健康检查并确认通知成功。', sourceTaskId: 't1', sourceRunId: 'ar1',
  evidenceLinks: ['https://github.com/acme/wao/pull/1'], updatedAt: '2026-08-19T16:00:00',
}

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }],
  })
}

async function mountPanel() {
  const r = router()
  await r.push('/projects/p1?tab=记忆')
  await r.isReady()
  const wrapper = mount(ProjectMemoryPanel, { props: { projectId: 'p1' }, global: { plugins: [r] } })
  await flushPromises()
  return { wrapper, router: r }
}

describe('项目工程记忆', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    memoryApi.fetchProjectMemories.mockResolvedValue([])
    memoryApi.createProjectMemory.mockResolvedValue(sample)
    memoryApi.updateProjectMemory.mockResolvedValue(sample)
    memoryApi.deleteProjectMemory.mockResolvedValue(undefined)
  })

  it('覆盖加载完成后的空数据状态', async () => {
    const { wrapper } = await mountPanel()

    expect(wrapper.text()).toContain('还没有项目工程记忆')
    expect(wrapper.text()).toContain('个人生活记忆仍严格私有')
  })

  it('保存失败保留本地草稿并允许原位重试', async () => {
    memoryApi.createProjectMemory
      .mockRejectedValueOnce(new Error('网络断开'))
      .mockResolvedValueOnce(sample)
    const { wrapper } = await mountPanel()
    await wrapper.findAll('button').find((button) => button.text().includes('新增记忆'))!.trigger('click')
    const inputs = wrapper.findAll('.editor input')
    const areas = wrapper.findAll('.editor textarea')
    await inputs[0]!.setValue('部署验收命令')
    await areas[0]!.setValue('运行线上健康检查并确认通知成功。')

    expect(wrapper.text()).toContain('草稿已保存在本机')
    await wrapper.findAll('.editor footer button').find((button) => button.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('网络断开')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('部署验收命令')
    await wrapper.findAll('.editor footer button').find((button) => button.text() === '重试保存')!.trigger('click')
    await flushPromises()
    expect(memoryApi.createProjectMemory).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已保存')
  })

  it('删除失败保留记录并提供无需二次确认的重试入口', async () => {
    memoryApi.fetchProjectMemories.mockResolvedValue([sample])
    memoryApi.deleteProjectMemory
      .mockRejectedValueOnce(new Error('服务暂不可用'))
      .mockResolvedValueOnce(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountPanel()
    const remove = wrapper.find('button[aria-label="删除记忆"]')

    await remove.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('服务暂不可用')
    expect(wrapper.text()).toContain(sample.title)

    await wrapper.find('button[aria-label="删除记忆"]').trigger('click')
    await flushPromises()
    expect(memoryApi.deleteProjectMemory).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain(sample.title)
  })

  it('AI Run 详情分别展示读取、写入并可跳回来源', async () => {
    const r = router()
    await r.push('/')
    await r.isReady()
    const wrapper = mount(AiRunMemoryReferences, {
      props: { reads: [sample], writes: [sample] }, global: { plugins: [r] },
    })

    expect(wrapper.text()).toContain('本次读取')
    expect(wrapper.text()).toContain('本次写入')
    await wrapper.findAll('button').find((button) => button.text().includes('来源 Task'))!.trigger('click')
    await flushPromises()
    expect(r.currentRoute.value.path).toBe('/projects/p1')
    expect(r.currentRoute.value.query).toMatchObject({ task: 't1', run: 'ar1' })
  })
})
