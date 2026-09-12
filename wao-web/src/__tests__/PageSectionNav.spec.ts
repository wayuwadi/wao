import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PageSectionNav from '@/components/navigation/PageSectionNav.vue'

function section(id: string, label: string, top: number) {
  const element = document.createElement('section')
  element.id = id
  element.dataset.pageAnchor = label
  element.getBoundingClientRect = vi.fn<() => DOMRect>(
    () =>
      ({
        top,
        bottom: top + 100,
        left: 0,
        right: 100,
        width: 100,
        height: 100,
        x: 0,
        y: top,
        toJSON: () => ({}),
      }) as DOMRect,
  )
  return element
}

async function settleFrame() {
  await flushPromises()
  await new Promise((resolve) => requestAnimationFrame(() => resolve(undefined)))
}

describe('页面分区锚点导航', () => {
  const originalMatchMedia = window.matchMedia
  const originalScrollIntoView = HTMLElement.prototype.scrollIntoView

  beforeEach(() => {
    window.matchMedia = vi.fn<(query: string) => MediaQueryList>(
      () => ({ matches: false }) as MediaQueryList,
    )
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn<() => void>(),
    })
  })

  afterEach(() => {
    window.matchMedia = originalMatchMedia
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: originalScrollIntoView,
    })
  })

  it('只在至少三个明确分区时展示，并点击平滑定位', async () => {
    const root = document.createElement('div')
    const scrollContainer = document.createElement('div')
    scrollContainer.getBoundingClientRect = vi.fn<() => DOMRect>(() => ({ top: 90 }) as DOMRect)
    const sections = [
      section('overview', 'AI 控制中心', 100),
      section('goal', '自主目标', 500),
      section('coach', '成长教练', 900),
    ]
    sections.forEach((item) => root.append(item))

    const wrapper = mount(PageSectionNav, { props: { root, scrollContainer } })
    await settleFrame()

    expect(wrapper.find('nav').exists()).toBe(false)
    expect(wrapper.get('.page-section-floating').element.getAttribute('style')).toBeNull()
    await wrapper.get('.page-section-toggle').trigger('click')
    expect(wrapper.get('nav').attributes('aria-label')).toBe('本页内容')
    expect(wrapper.findAll('nav button').map((button) => button.text())).toEqual([
      'AI 控制中心',
      '自主目标',
      '成长教练',
    ])
    expect(wrapper.findAll('nav button')[0]!.attributes('aria-current')).toBe('location')

    await wrapper.findAll('nav button')[2]!.trigger('click')
    expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'start',
    })
    expect(wrapper.find('nav').exists()).toBe(false)

    root.removeChild(sections[2]!)
    await settleFrame()
    expect(wrapper.find('nav').exists()).toBe(false)
    wrapper.unmount()
  })

  it('正文滚动时高亮最后一个经过顶部的分区', async () => {
    const root = document.createElement('div')
    const scrollContainer = document.createElement('div')
    scrollContainer.getBoundingClientRect = vi.fn<() => DOMRect>(() => ({ top: 90 }) as DOMRect)
    const first = section('first', '第一段', -400)
    const second = section('second', '第二段', 110)
    const third = section('third', '第三段', 700)
    root.append(first, second, third)

    const wrapper = mount(PageSectionNav, { props: { root, scrollContainer } })
    await settleFrame()

    await wrapper.get('.page-section-toggle').trigger('click')
    expect(wrapper.findAll('nav button')[1]!.attributes('aria-current')).toBe('location')
    wrapper.unmount()
  })

  it('滚动到底部时高亮无法贴到顶部的最后一个分区', async () => {
    const root = document.createElement('div')
    const scrollContainer = document.createElement('div')
    Object.defineProperties(scrollContainer, {
      scrollTop: { configurable: true, value: 500 },
      clientHeight: { configurable: true, value: 500 },
      scrollHeight: { configurable: true, value: 1_000 },
    })
    root.append(
      section('first', '第一段', -400),
      section('second', '第二段', 100),
      section('last', '最后一段', 460),
    )

    const wrapper = mount(PageSectionNav, { props: { root, scrollContainer } })
    await settleFrame()

    await wrapper.get('.page-section-toggle').trigger('click')
    expect(wrapper.findAll('nav button')[2]!.attributes('aria-current')).toBe('location')
    wrapper.unmount()
  })
})
