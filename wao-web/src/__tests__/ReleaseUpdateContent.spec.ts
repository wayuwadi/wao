import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ReleaseUpdateContent from '@/components/release/ReleaseUpdateContent.vue'
import type { ReleaseEdition } from '@/types/featureExplorer'
const edition: ReleaseEdition = {
  id: 'guide',
  publishedOn: '2026-10-04',
  title: '恢复连接',
  intro: '旧通用说明',
  highlights: [
    {
      featureId: 'settings',
      eyebrow: '体验修复',
      title: '恢复连接',
      summary: '设备授权与连接指引回到设置页。',
      points: [],
      steps: ['打开设置', '选择 WAO CLI 与 MCP'],
      demo: { presentation: 'inline', kind: 'workspace-mcp', alt: '设备连接步骤' },
    },
  ],
}
describe('更新卡片开箱内容', () => {
  it('变化点与步骤同时出现，展开后保留全部说明和所有变化', async () => {
    const item = { ...edition.highlights[0]!, points: ['重要变化一', '重要变化二', '重要变化三'], steps: ['操作一', '操作二', '操作三'] }
    const wrapper = mount(ReleaseUpdateContent, { props: { edition: { ...edition, highlights: [item, item, item, { ...item, title: '第四项独立变化' }] } } })
    expect(wrapper.text()).toContain('重要变化一')
    expect(wrapper.text()).toContain('操作一')
    expect(wrapper.text()).not.toContain('重要变化三')
    await wrapper.get('.details-toggle').trigger('click')
    expect(wrapper.text()).toContain('重要变化三')
    expect(wrapper.text()).toContain('操作三')
    expect(wrapper.text()).not.toContain('第四项独立变化')
    await wrapper.get('.more-changes').trigger('click')
    expect(wrapper.text()).toContain('第四项独立变化')
    await wrapper.setProps({ busy: true })
    expect(wrapper.get('.more-changes').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.details-toggle').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('标题只出现一次，直接展示用途与步骤，演示按需展开', async () => {
    const wrapper = mount(ReleaseUpdateContent, { props: { edition, pending: true } })
    expect(wrapper.findAll('h3')).toHaveLength(1)
    expect(wrapper.text().split('恢复连接')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('旧通用说明')
    expect(wrapper.text()).toContain('选择 WAO CLI 与 MCP')
    expect(wrapper.find('.feature-demo').exists()).toBe(false)
    expect(wrapper.find('.experience').exists()).toBe(false)
    await wrapper.get('.demo-toggle').trigger('click')
    expect(wrapper.get('.feature-demo').text()).toContain('连接你的工具')
    await wrapper.setProps({ edition: { ...edition, id: 'next' } })
    expect(wrapper.find('.feature-demo').exists()).toBe(false)
    wrapper.unmount()
  })
  it('复杂演示只在点击时请求大图窗，保存中禁用体验和演示', async () => {
    const item = {
      ...edition.highlights[0]!,
      demo: { ...edition.highlights[0]!.demo!, presentation: 'window' as const },
    }
    const wrapper = mount(ReleaseUpdateContent, {
      props: { edition: { ...edition, highlights: [item] } },
    })
    await wrapper.get('.demo-toggle').trigger('click')
    expect(wrapper.emitted('demo')?.[0]).toEqual([item])
    await wrapper.setProps({ busy: true })
    expect(wrapper.get('button.demo-toggle').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button.experience').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
