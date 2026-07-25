import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SETTLE_MS } from '@/composables/completion'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'

const router = createRouter({ history: createWebHistory(), routes: [{ path: '/:p(.*)*', component: { template: '<div/>' } }] })

function mountDrawer() {
  return mount(TaskDrawer, { global: { plugins: [router] }, attachTo: document.body })
}

/**
 * 等异步组件（描述编辑器）解析完成。
 *
 * 动态 import 的解析要跨若干个微 / 宏任务，固定睡一个时长要么慢要么偶发失败，
 * 所以这里轮询到出现为止，超时才判失败。
 */
async function waitFor(w: ReturnType<typeof mountDrawer>, selector: string, tries = 50) {
  for (let i = 0; i < tries; i++) {
    await flushPromises()
    if (w.find(selector).exists()) return true
    await new Promise((r) => setTimeout(r, 2))
  }
  return false
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

    // 编辑器是异步组件（ProseMirror 不进首屏包），要等它解析完才出现在 DOM 里
    expect(w.find('textarea.desc').exists()).toBe(false)
    expect(await waitFor(w, '.md-body')).toBe(true)
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

  it('已完成的子任务沉到底部并默认收起，点击才展开', async () => {
    data.addSub(taskId, '未完成的')
    const doneId = data.addSub(taskId, '已完成的')!
    data.updateSub(taskId, doneId, { done: true })
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    // 默认只看得到未完成的那条，已完成的藏在折叠条后面
    expect(w.findAll('.sub-row')).toHaveLength(1)
    expect(w.find('.sub-row').text()).toContain('未完成的')

    const toggle = w.find('.sub-done-toggle')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toContain('1')

    await toggle.trigger('click')

    const rows = w.findAll('.sub-row')
    expect(rows).toHaveLength(2)
    // 已完成的排在最底部
    expect(rows[1]!.text()).toContain('已完成的')
  })

  it('没有已完成子任务时不显示折叠条', async () => {
    data.addSub(taskId, '只有这一条')
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()

    expect(w.find('.sub-done-toggle').exists()).toBe(false)
  })

  it('刚勾完成的子任务先原地停留，不会立刻沉底消失', async () => {
    vi.useFakeTimers()
    try {
      data.addSub(taskId, '马上要勾的')
      ui.openTask(taskId)
      const w = mountDrawer()
      await w.vm.$nextTick()

      await w.find('.sub-row .cbox').trigger('click')

      // 仍在原位，只是淡出
      expect(w.findAll('.sub-row')).toHaveLength(1)
      expect(w.find('.sub-row').classes()).toContain('settling')

      vi.advanceTimersByTime(SETTLE_MS)
      await w.vm.$nextTick()

      expect(w.findAll('.sub-row')).toHaveLength(0)
      expect(w.find('.sub-done-toggle').exists()).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('子任务标题用可撑高的 textarea，长标题不会被截断', async () => {
    data.addSub(taskId, '一个特别长的子任务标题'.repeat(6))
    ui.openTask(taskId)
    const w = mountDrawer()
    await w.vm.$nextTick()
    await w.find('.sub-row').trigger('click')

    const title = w.find('.sub-title-input')
    expect(title.element.tagName).toBe('TEXTAREA')
    expect((title.element as HTMLTextAreaElement).value).toBe('一个特别长的子任务标题'.repeat(6))
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
