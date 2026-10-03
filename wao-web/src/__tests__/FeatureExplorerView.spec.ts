import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import FeatureExplorerView from '@/views/FeatureExplorerView.vue'
import { FEATURE_CATALOG } from '@/data/featureCatalog'

function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/features', component: FeatureExplorerView },
      { path: '/notes', component: { template: '<div>notes</div>' } },
    ],
  })
  return { router, wrapper: mount(FeatureExplorerView, { global: { plugins: [router] } }) }
}

describe('功能探索', () => {
  it('展示当前版本精选与真实功能目录，不再出现示例预览', () => {
    const { wrapper } = render()
    expect(wrapper.findAll('.release-edition').length).toBeGreaterThan(1)
    expect(wrapper.findAll('.catalog-card')).toHaveLength(Math.min(12, FEATURE_CATALOG.length))
    expect(wrapper.text()).toContain('最近更新')
    expect(wrapper.text()).toContain('全部功能')
    expect(wrapper.text()).not.toContain('示例预览')
    wrapper.unmount()
  })

  it('按目标筛选并展开完整目录', async () => {
    const { wrapper } = render()
    const planning = wrapper
      .findAll('.filter-bar button')
      .find((item) => item.text() === '计划与行动')!
    await planning.trigger('click')
    const expected = FEATURE_CATALOG.filter((item) => item.category === 'planning')
    expect(wrapper.findAll('.catalog-card')).toHaveLength(expected.length)
    expect(wrapper.find('.section-heading p').text()).toBeTruthy()
    await wrapper.findAll('.filter-bar button')[0]!.trigger('click')
    if (wrapper.find('.more-wrap button').exists()) await wrapper.get('.more-wrap button').trigger('click')
    expect(wrapper.findAll('.catalog-card')).toHaveLength(FEATURE_CATALOG.length)
    wrapper.unmount()
  })

  it('功能详情说明用户结果并能进入真实站内路由', async () => {
    const { router, wrapper } = render()
    const note = wrapper.findAll('.catalog-card').find((item) => item.text().includes('小记'))!
    await note.trigger('click')
    expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain(
      '草稿、保存和失败重试',
    )
    ;(document.body.querySelector('.detail-actions .primary') as HTMLButtonElement).click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/notes')
    wrapper.unmount()
  })
})
