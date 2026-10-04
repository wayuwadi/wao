import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import ReleaseGuideDialog from '@/components/release/ReleaseGuideDialog.vue'
import ReleaseFeatureDemo from '@/components/release/ReleaseFeatureDemo.vue'
import type { ReleaseHighlight } from '@/types/featureExplorer'
const item: ReleaseHighlight = {
  featureId: 'notes',
  eyebrow: '小记',
  title: '记录区更顺手',
  summary: '记录入口合并',
  points: [],
  steps: ['打开小记', '点击记录'],
  demo: { presentation: 'window', kind: 'notes-layout', alt: '记录区展开示意' },
}
afterEach(() => {
  document.body.innerHTML = ''
  document.body.style.overflow = ''
})
describe('具体功能演示', () => {
  it('一屏展示专用演示，支持操作、Esc与焦点恢复，不再包含欢迎和完成页', async () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const wrapper = mount(ReleaseGuideDialog, {
      props: { item, editionId: 'guide' },
      attachTo: document.body,
    })
    await wrapper.vm.$nextTick()
    expect(document.body.style.overflow).toBe('hidden')
    expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭功能演示')
    expect(document.body.textContent).not.toContain('开始看看')
    expect(document.body.textContent).not.toContain('继续你的 WAO')
    document.querySelector<HTMLButtonElement>('.mini-toolbar button')!.click()
    await wrapper.vm.$nextTick()
    expect(document.querySelector('aside')?.textContent).toContain('记录区')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
    expect(document.body.style.overflow).toBe('')
    expect(document.activeElement).toBe(trigger)
  })
  it('图片绑定目标制品，失败保留文字并可重试', async () => {
    const wrapper = mount(ReleaseFeatureDemo, {
      props: {
        editionId: `git-release-${'a'.repeat(40)}`,
        demo: {
          presentation: 'window',
          image: 'assets/release-guides/notes.png',
          alt: '小记实际界面',
        },
      },
    })
    expect(wrapper.get('img').attributes('src')).toBe(
      `/releases/${'a'.repeat(40)}/assets/release-guides/notes.png`,
    )
    await wrapper.get('img').trigger('error')
    expect(wrapper.text()).toContain('仍可按使用步骤操作')
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('img').attributes('src')).toContain('?retry=1')
    wrapper.unmount()
  })
})
