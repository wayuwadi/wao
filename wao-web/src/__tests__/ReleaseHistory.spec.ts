import { createPinia, setActivePinia } from 'pinia'
vi.mock('ant-design-vue', () => ({
  Modal: { props: ['open'], template: '<div v-if="open" role="dialog"><slot /></div>' },
}))
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { beforeEach, describe, it, expect, vi } from 'vitest'
import ReleaseHistory from '@/components/release/ReleaseHistory.vue'
import { CURRENT_RELEASE, RELEASE_HISTORY } from '@/data/featureCatalog'
import { MODULES } from '@/config/nav'
import { useUiStore } from '@/stores/ui'

describe('最近更新档案', () => {
  it('当前说明归档，历史批次唯一且按日期倒序，入口来自真实目录', () => {
    expect(RELEASE_HISTORY[0]).toEqual(CURRENT_RELEASE)
    expect(new Set(RELEASE_HISTORY.map((item) => item.id)).size).toBe(RELEASE_HISTORY.length)
    for (const [index, edition] of RELEASE_HISTORY.entries()) {
      expect(edition.publishedOn).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      if (index) expect(edition.publishedOn <= RELEASE_HISTORY[index - 1]!.publishedOn).toBe(true)
      for (const highlight of edition.highlights)
        expect(MODULES[highlight.featureId]?.path).toMatch(/^\//)
    }
  })
  it('翻页替换批次，切换记录保留页码，详情可进入实际功能', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: Object.values(MODULES).map((item) => ({
        path: item.path,
        component: { template: '<div />' },
      })),
    })
    await router.push('/features')
    const wrapper = mount(ReleaseHistory, {
      global: {
        plugins: [router],
        stubs: {
          GitCommitHistory: true,
          Modal: { props: ['open'], template: '<div v-if="open" role="dialog"><slot /></div>' },
        },
      },
    })
    expect(wrapper.findAll('.release-edition')).toHaveLength(3)
    const first = wrapper.get('.release-edition strong').text()
    await wrapper.get('[aria-label="下一页"]').trigger('click')
    expect(wrapper.findAll('.release-edition')).toHaveLength(3)
    expect(wrapper.get('.release-edition strong').text()).not.toBe(first)
    const second = wrapper.get('.release-edition strong').text()
    await wrapper.findAll('.history-switch button')[1]!.trigger('click')
    await wrapper.findAll('.history-switch button')[0]!.trigger('click')
    expect(wrapper.get('.release-edition strong').text()).toBe(second)
    await wrapper.get('[aria-label="上一页"]').trigger('click')
    await wrapper.get('.release-edition').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain(RELEASE_HISTORY[1]!.intro)
    // 新批次可能只有说明和演示；导航验证选择确实具有站内入口的历史批次。
    const navigableIndex = RELEASE_HISTORY.slice(1, 4).findIndex(edition => edition.highlights.some(item => item.featureId !== 'changelog'))
    await wrapper.findAll('.release-edition')[navigableIndex]!.trigger('click')
    await wrapper.findAll('.edition-content article button').find(button => button.text().startsWith('去体验'))!.trigger('click')
    await flushPromises()
    const featureId = RELEASE_HISTORY[navigableIndex + 1]!.highlights.find(item => item.featureId !== 'changelog')!.featureId
    if (featureId === 'notes') {
      expect(useUiStore().modal).toBe('note')
      expect(router.currentRoute.value.path).toBe('/features')
    } else expect(router.currentRoute.value.path).toBe(MODULES[featureId].path)
    wrapper.unmount()
  })
})

beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()) })
