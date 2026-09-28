import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import ReleaseHighlightsTour from '@/components/release/ReleaseHighlightsTour.vue'
import { CURRENT_RELEASE } from '@/data/featureCatalog'

afterEach(() => {
  document.body.innerHTML = ''
  document.body.style.overflow = ''
})

describe('更新亮点介绍', () => {
  it('从欢迎页逐条浏览到完成页，并支持探索全部功能', async () => {
    const wrapper = mount(ReleaseHighlightsTour, {
      props: { open: true, edition: CURRENT_RELEASE },
      attachTo: document.body,
    })
    expect(document.body.textContent).toContain(CURRENT_RELEASE.title)
    const next = () => document.body.querySelector<HTMLButtonElement>('.next')!.click()
    next()
    await wrapper.vm.$nextTick()
    expect(document.body.textContent).toContain(CURRENT_RELEASE.highlights[0]!.title)
    for (let index = 0; index < CURRENT_RELEASE.highlights.length; index++) {
      next()
      await wrapper.vm.$nextTick()
    }
    expect(document.body.textContent).toContain('继续你的 WAO')
    document.body.querySelector<HTMLButtonElement>('.features-button')!.click()
    expect(wrapper.emitted('features')).toHaveLength(1)
    wrapper.unmount()
  })

  it('稍后再看不冒充已读，关闭按钮明确记录已读', () => {
    const wrapper = mount(ReleaseHighlightsTour, {
      props: { open: true, edition: CURRENT_RELEASE },
      attachTo: document.body,
    })
    document.body.querySelector<HTMLButtonElement>('.later')!.click()
    expect(wrapper.emitted('dismiss')?.[0]).toEqual(['later'])
    document.body.querySelector<HTMLButtonElement>('.close')!.click()
    expect(wrapper.emitted('dismiss')?.[1]).toEqual(['seen'])
    wrapper.unmount()
  })

  it('支持方向键浏览、Esc 关闭，并在打开时锁定背景滚动', async () => {
    const wrapper = mount(ReleaseHighlightsTour, {
      props: { open: true, edition: CURRENT_RELEASE },
      attachTo: document.body,
    })
    expect(document.body.style.overflow).toBe('hidden')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    await wrapper.vm.$nextTick()
    expect(document.body.textContent).toContain(CURRENT_RELEASE.highlights[0]!.title)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    const dismissals = wrapper.emitted('dismiss') ?? []
    expect(dismissals[dismissals.length - 1]).toEqual(['seen'])
    wrapper.unmount()
    expect(document.body.style.overflow).toBe('')
  })
})
