import { describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useFeatureExperience } from '@/composables/featureExperience'

describe('新功能原地体验', () => {
  it('小记留在当前页，当前任务不被替换，首页直接进入休息空间', async () => {
    const router = createRouter({ history:createMemoryHistory(), routes:[{path:'/',component:{template:'<div />'}},{path:'/projects',component:{template:'<div />'}}] })
    await router.push('/projects')
    const w = mount(defineComponent({setup() { return { experience: useFeatureExperience() } }, template:'<div />'}), {global:{plugins:[createPinia(),router]}})
    const ui = useUiStore()
    expect(await w.vm.experience('notes')).toBe(true)
    expect(router.currentRoute.value.path).toBe('/projects')
    expect(ui.modal).toBe('note')
    ui.closeModal(); ui.openModal('task')
    expect(await w.vm.experience('notes')).toBe(false)
    expect(ui.modal).toBe('task')
    ui.closeModal()
    expect(await w.vm.experience('home')).toBe(true)
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/')
    expect(await w.vm.experience('unknown-feature')).toBe(false)
    w.unmount()
  })
})
