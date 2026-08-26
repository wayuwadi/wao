import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import SkillConnectionsView from '@/views/SkillConnectionsView.vue'

describe('应用 Skill 接入入口', () => {
  it('无真实提供方时展示诚实空状态，并区分 WAO Skill 市场', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/skill-connections', component: SkillConnectionsView },
        { path: '/wao/skills', component: { template: '<div />' } },
      ],
    })
    await router.push('/skill-connections')
    await router.isReady()

    const wrapper = mount(SkillConnectionsView, { global: { plugins: [router] } })

    expect(wrapper.get('h1').text()).toBe('应用 Skill 接入')
    expect(wrapper.text()).toContain('还没有已接入的应用 Skill')
    expect(wrapper.text()).toContain('不会用演示数据或明文 API Key 假装连接成功')
    expect(wrapper.get('.market-link').attributes('href')).toBe('/wao/skills')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.findAll('button')).toHaveLength(0)
  })
})
