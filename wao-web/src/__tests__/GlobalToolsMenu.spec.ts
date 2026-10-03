import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, expect, it } from 'vitest'
import GlobalToolsMenu from '@/components/nav/GlobalToolsMenu.vue'
import { useUiStore } from '@/stores/ui'

beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })
it('收纳低频入口，通知和更新状态传到入口，打开子面板后收起工具', async () => {
  const router = createRouter({ history:createMemoryHistory(), routes:[{ path:'/', component:{template:'<div />'} }] })
  await router.push('/')
  const Notification = defineComponent({ emits:['unread-change','opened'], template:'<button @click="$emit(\'opened\')">通知</button>' })
  const Update = defineComponent({ emits:['available-change','arrival-change','opened'], template:'<button @click="$emit(\'opened\')">更新记录</button>' })
  const wrapper = mount(GlobalToolsMenu, { global:{plugins:[router], stubs:{Teleport:true, NotificationCenter:Notification, VersionUpdateNotice:Update}} })
  expect(wrapper.find('.tools-dot').exists()).toBe(false)
  wrapper.findComponent(Notification).vm.$emit('unread-change', true); await flushPromises()
  expect(wrapper.find('.tools-dot').exists()).toBe(true)
  await wrapper.get('.tools-trigger').trigger('click')
  expect(wrapper.get('.tools-panel').isVisible()).toBe(true)
  await wrapper.findComponent(Notification).trigger('click')
  expect(wrapper.get('.tools-panel').isVisible()).toBe(false)
  wrapper.findComponent(Notification).vm.$emit('unread-change', false)
  wrapper.findComponent(Update).vm.$emit('available-change', true); await flushPromises()
  expect(wrapper.get('.tools-update-label').text()).toBe('可更新')
  wrapper.findComponent(Update).vm.$emit('arrival-change', true); await flushPromises()
  expect(wrapper.get('.tools-trigger').classes()).toContain('update-arriving')
  wrapper.findComponent(Update).vm.$emit('arrival-change', false); await flushPromises()
  expect(wrapper.get('.tools-trigger').classes()).not.toContain('update-arriving')
  expect(wrapper.find('.tools-dot').exists()).toBe(true)
  await wrapper.get('.tools-trigger').trigger('click')
  await wrapper.findAll('.tools-row')[1]!.trigger('click')
  expect(useUiStore().helpOpen).toBe(true)
  expect(wrapper.get('.tools-panel').isVisible()).toBe(false)
  wrapper.unmount()
})
