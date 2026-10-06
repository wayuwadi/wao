import { describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useFeatureExperience } from '@/composables/featureExperience'

describe('新功能原地体验', () => {
  it('设置、阅读、AI 和连接入口按登记定位，重复入口可再次消费且不替换草稿弹层', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: ['/notes', '/capabilities', '/features'].map(path => ({path, component:{template:'<div />'}})) })
    await router.push('/notes?search=保留筛选')
    const w = mount(defineComponent({ setup() { return { experience: useFeatureExperience() } }, template:'<div />' }), { global:{plugins:[createPinia(),router]} })
    for (const [target, action] of [['notes-settings','settings'],['notes-reading','reading'],['notes-ai','ai']]) {
      expect(await w.vm.experience('notes',{target:target!,label:'体验'})).toBe(true)
      expect(router.currentRoute.value.query).toMatchObject({search:'保留筛选',releaseAction:action})
    }
    const request = router.currentRoute.value.query.releaseRequest
    await w.vm.experience('notes',{target:'notes-ai',label:'查看成果'})
    expect(router.currentRoute.value.query.releaseRequest).not.toBe(request)
    expect(await w.vm.experience('notes',{target:'workspace-connections',label:'错误关联'})).toBe(false)
    useUiStore().openModal('note')
    expect(await w.vm.experience('notes',{target:'notes-reading',label:'阅读'})).toBe(false)
    expect(useUiStore().modal).toBe('note')
    useUiStore().closeModal()
    await w.vm.experience('capabilities',{target:'workspace-connections',label:'连接'})
    expect(router.currentRoute.value.fullPath).toBe('/capabilities?area=connections')
    await w.vm.experience('changelog',{target:'release-updates',label:'完整更新'})
    expect(router.currentRoute.value.fullPath).toBe('/features#recent-updates')
    w.unmount()
  })
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
