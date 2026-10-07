import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import DesignReferenceView from '@/views/DesignReferenceView.vue'
import WaoAboutPanel from '@/components/settings/WaoAboutPanel.vue'
import WaoField from '@/components/ui/WaoField.vue'
import WaoButton from '@/components/ui/WaoButton.vue'

vi.mock('@/composables/theme', () => ({ useTheme: () => ({ effective: ref('light') }) }))
afterEach(() => {
  vi.useRealTimers()
  document.body.innerHTML = ''
})
function category(wrapper: ReturnType<typeof mount>, name: string) {
  return wrapper
    .findAll('.reference-sidebar button')
    .find((button) => button.text() === name)!
    .trigger('click')
}

describe('设计参考的本地交互与复用边界', () => {
  it('主题仅改变参考页，不覆盖全站主题和存储偏好', async () => {
    document.documentElement.dataset.theme = 'light'
    const saved = vi.spyOn(Storage.prototype, 'setItem')
    const wrapper = mount(DesignReferenceView)
    await wrapper.find('select[aria-label="预览主题"]').setValue('dark')
    expect(wrapper.attributes('data-wao-theme')).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(saved).not.toHaveBeenCalled()
    wrapper.unmount()
    saved.mockRestore()
  })
  it('保存状态演示取消过期结果，切换状态不被旧计时器覆盖', async () => {
    vi.useFakeTimers()
    const wrapper = mount(DesignReferenceView)
    await category(wrapper, '状态与反馈')
    await wrapper
      .findAll('.state-selectors button')
      .find((button) => button.text() === '失败重试')!
      .trigger('click')
    await wrapper.find('.state-actions button').trigger('click')
    expect(wrapper.find('.state-copy').text()).toContain('正在演示保存')
    await wrapper
      .findAll('.state-selectors button')
      .find((button) => button.text() === '版本冲突')!
      .trigger('click')
    await vi.advanceTimersByTimeAsync(800)
    expect(wrapper.find('.state-copy').text()).toContain('账号里的内容有了变化')
    await wrapper.find('.state-actions button').trigger('click')
    expect(wrapper.find('.state-copy').text()).toContain('还没写完')
    await wrapper.find('.state-actions button').trigger('click')
    await vi.advanceTimersByTimeAsync(800)
    expect(wrapper.find('.state-copy').text()).toContain('这一点，留下来了')
    wrapper.unmount()
  })
  it('示例提示词切换项目、关闭重开仍保留，保存不调用业务服务', async () => {
    vi.useFakeTimers()
    const wrapper = mount(DesignReferenceView, { attachTo: document.body })
    await category(wrapper, '弹窗与浮层')
    await wrapper
      .findAll('.card-grid button')
      .find((button) => button.text() === '查看复杂设置')!
      .trigger('click')
    await nextTick()
    const input = document.querySelector<HTMLTextAreaElement>('#design-prompt')!
    input.value = '留住我的演示草稿'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    const buttons = () => [
      ...document.querySelectorAll<HTMLButtonElement>('.settings-dialog-shell button'),
    ]
    buttons()
      .find((button) => button.textContent?.trim() === '自动处理')!
      .click()
    await nextTick()
    buttons()
      .find((button) => button.textContent?.trim() === '表达偏好')!
      .click()
    await nextTick()
    expect(document.querySelector<HTMLTextAreaElement>('#design-prompt')!.value).toBe(
      '留住我的演示草稿',
    )
    buttons()
      .find((button) => button.getAttribute('aria-label') === '关闭表达设置示例')!
      .click()
    await nextTick()
    await wrapper
      .findAll('.card-grid button')
      .find((button) => button.text() === '查看复杂设置')!
      .trigger('click')
    await nextTick()
    expect(document.querySelector<HTMLTextAreaElement>('#design-prompt')!.value).toBe(
      '留住我的演示草稿',
    )
    buttons()
      .find((button) => button.textContent?.trim() === '演示保存')!
      .click()
    await vi.advanceTimersByTimeAsync(800)
    expect(document.querySelector('.settings-dialog-shell')!.textContent).toContain(
      '演示已保存 · 未写入账号',
    )
    wrapper.unmount()
    await flushPromises()
  })
  it('关于页提供设计参考入口，保留原设置页', () => {
    const wrapper = mount(WaoAboutPanel, { global: { stubs: { CompanionAvatar: true } } })
    const link = wrapper.find('a[href="/design"]')
    expect(link.text()).toContain('设计参考')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    wrapper.unmount()
  })
})

describe('公共字段和按钮的可操作性', () => {
  it('两个字段拥有独立标签和错误关联', () => {
    const wrapper = mount(
      defineComponent({
        components: { WaoField },
        template: `<div><WaoField label="标题" hint="说明" error="不能为空"><template #default="field"><input :id="field.id" :aria-describedby="field.describedBy" :aria-invalid="field.invalid" /></template></WaoField><WaoField label="描述"><template #default="field"><input :id="field.id" /></template></WaoField></div>`,
      }),
    )
    const labels = wrapper.findAll('label'),
      firstId = labels[0]!.attributes('for')
    expect(firstId).not.toBe(labels[1]!.attributes('for'))
    expect(wrapper.find(`#${firstId}-error`).attributes('role')).toBe('alert')
    expect(wrapper.find('input').attributes('aria-describedby')).toContain(`${firstId}-error`)
    expect(wrapper.find('input').attributes('aria-invalid')).toBe('true')
    wrapper.unmount()
  })
  it('加载中的按钮阻止重复点击并保留操作名称', async () => {
    const click = vi.fn()
    const wrapper = mount(WaoButton, {
      props: { loading: true, onClick: click },
      slots: { default: '保存' },
    })
    await wrapper.trigger('click')
    expect(click).not.toHaveBeenCalled()
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.text()).toBe('保存')
    wrapper.unmount()
  })
})
