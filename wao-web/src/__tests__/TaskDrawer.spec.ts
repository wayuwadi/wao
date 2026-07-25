import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'

const router = createRouter({ history: createWebHistory(), routes: [{ path: '/:p(.*)*', component: { template: '<div/>' } }] })

function mountDrawer() {
  return mount(TaskDrawer, { global: { plugins: [router] } })
}

describe('TaskDrawer', () => {
  let data: ReturnType<typeof useDataStore>
  let ui: ReturnType<typeof useUiStore>
  let taskId: string

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    ui = useUiStore()
    taskId = data.tasks[0]!.id
    data.tasks[0]!.subs = []
  })

  it('没有打开的任务时不渲染抽屉', () => {
    expect(mountDrawer().find('.drawer').exists()).toBe(false)
  })

  it('打开任务后渲染标题与描述编辑器', async () => {
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.find('.drawer').exists()).toBe(true)
    expect(w.find('.title-input').attributes('value')).toBe(data.taskById(taskId)!.title)
    // 描述不再是固定 rows 的 textarea，而是 Markdown 编辑器
    expect(w.find('.md').exists()).toBe(true)
  })

  it('默认展示任务属性栏，点击子任务后切成子任务详情', async () => {
    const subId = data.addSub(taskId, '扫描身份证')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    // 只看右侧栏——「自定义属性」这几个字也出现在种子数据的评论里，全局断言会误判
    expect(w.find('.props').text()).toContain('自定义属性')
    expect(w.find('.sub-back').exists()).toBe(false)

    await w.find('.sub-row').trigger('click')

    expect(w.find('.sub-back').exists()).toBe(true)
    expect(w.find('.sub-title-input').attributes('value')).toBe('扫描身份证')
    expect(w.find('.props').text()).not.toContain('自定义属性')
    expect(data.subById(taskId, subId)).toBeDefined()
  })

  it('子任务详情里改名会写回 store', async () => {
    const subId = data.addSub(taskId, '旧名字')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.sub-row').trigger('click')

    const input = w.find('.sub-title-input')
    ;(input.element as HTMLInputElement).value = '新名字'
    await input.trigger('input')

    expect(data.subById(taskId, subId)?.text).toBe('新名字')
  })

  it('点子任务的勾选框只切完成态，不展开详情', async () => {
    const subId = data.addSub(taskId, '扫描身份证')!
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.sub-row .cbox').trigger('click')

    expect(data.subById(taskId, subId)?.done).toBe(true)
    expect(w.find('.sub-back').exists()).toBe(false)
  })

  it('分享菜单同时给出分享页与 Markdown 两种链接', async () => {
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    await w.find('.share-wrap .icon-btn').trigger('click')

    const menu = w.find('.share-menu')
    expect(menu.exists()).toBe(true)
    expect(menu.text()).toContain('本任务 + 全部子任务')
    expect(menu.text()).toContain('复制分享页链接')
    expect(menu.text()).toContain('复制 Markdown 地址')
  })

  it('展开子任务时，分享范围收敛到该子任务', async () => {
    data.addSub(taskId, '扫描身份证')
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.sub-row').trigger('click')

    await w.find('.share-wrap .icon-btn').trigger('click')

    expect(w.find('.share-menu').text()).toContain('当前子任务')
  })
})
