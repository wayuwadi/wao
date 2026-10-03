import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { describe, it, expect } from 'vitest'
import ReleaseHistory from '@/components/release/ReleaseHistory.vue'
import { CURRENT_RELEASE, RELEASE_HISTORY } from '@/data/featureCatalog'
import { MODULES } from '@/config/nav'

describe('最近更新档案', () => {
  it('当前说明必须归档，历史批次唯一且按日期倒序，体验入口来自真实目录', () => {
    expect(RELEASE_HISTORY.find(item => item.id === CURRENT_RELEASE.id)).toEqual(CURRENT_RELEASE)
    expect(RELEASE_HISTORY[0]?.id).toBe(CURRENT_RELEASE.id)
    expect(new Set(RELEASE_HISTORY.map(item => item.id)).size).toBe(RELEASE_HISTORY.length)
    for (const [index, edition] of RELEASE_HISTORY.entries()) {
      expect(edition.publishedOn).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      if (index) expect(edition.publishedOn <= RELEASE_HISTORY[index - 1]!.publishedOn).toBe(true)
      for (const highlight of edition.highlights) expect(MODULES[highlight.featureId]?.path).toMatch(/^\//)
    }
  })

  it('展开更早批次保留已有记录，历史详情能进入对应功能', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/features', component: { template: '<div />' } },
      ...Object.values(MODULES).map(item => ({ path: item.path, component: { template: '<div />' } })),
    ] })
    await router.push('/features')
    const wrapper = mount(ReleaseHistory, { global: { plugins: [router] } })
    expect(wrapper.findAll('.release-edition')).toHaveLength(Math.min(5, RELEASE_HISTORY.length))
    while (wrapper.find('.older-updates').exists()) await wrapper.get('.older-updates').trigger('click')
    expect(wrapper.findAll('.release-edition')).toHaveLength(RELEASE_HISTORY.length)
    expect(wrapper.text()).toContain('已展示全部已收录更新')
    const historical = wrapper.findAll('.release-edition')[1]!
    expect(historical.text()).toContain(RELEASE_HISTORY[1]!.highlights[0]!.summary)
    await historical.get('a').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(MODULES[RELEASE_HISTORY[1]!.highlights[0]!.featureId].path)
    wrapper.unmount()
  })
})
