import { afterEach, beforeEach, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CompanionPortrait from '@/components/companions/CompanionPortrait.vue'
import CompanionAvatar from '@/components/companions/CompanionAvatar.vue'
import { companionEditing, companionQuiet } from '@/composables/quietCompanions'
beforeEach(() => {
  companionEditing.value = false
  companionQuiet.value = false
})
afterEach(() => {
  companionEditing.value = false
  companionQuiet.value = false
})
it('复用指定首页角色，图片加载后才通知展示计时，不启动工作区 WebGL', async () => {
  const wrapper = mount(CompanionPortrait, { props: { kind: 'note', identity: 'frog' } })
  expect(wrapper.find('canvas').exists()).toBe(false)
  expect(wrapper.get('img').attributes('src')).toBe('/companions/frog.png')
  expect(wrapper.emitted('ready')).toBeUndefined()
  await wrapper.get('img').trigger('load')
  expect(wrapper.emitted('ready')).toHaveLength(1)
  expect(wrapper.get('img').attributes('aria-hidden')).toBe('true')
  wrapper.unmount()
})
it('素材失败静默退出，切换角色后允许正常加载', async () => {
  const wrapper = mount(CompanionAvatar, { props: { identity: 'painter' } })
  await wrapper.get('img').trigger('error')
  expect(wrapper.emitted('failed')).toHaveLength(1)
  expect(wrapper.find('img').exists()).toBe(false)
  await wrapper.setProps({ identity: 'happy' })
  expect(wrapper.get('img').attributes('src')).toBe('/companions/happy.png')
  wrapper.unmount()
})
it('输入或安静模式立即停止动作，静态引导从不启动动作', async () => {
  const wrapper = mount(CompanionAvatar, { props: { identity: 'bookworm', animate: true } })
  expect(wrapper.get('img').classes()).toContain('greeting')
  companionEditing.value = true
  await wrapper.vm.$nextTick()
  expect(wrapper.get('img').classes()).not.toContain('greeting')
  companionEditing.value = false
  companionQuiet.value = true
  await wrapper.vm.$nextTick()
  expect(wrapper.get('img').classes()).not.toContain('greeting')
  companionQuiet.value = false
  await wrapper.setProps({ animate: false })
  expect(wrapper.get('img').classes()).not.toContain('greeting')
  wrapper.unmount()
})
