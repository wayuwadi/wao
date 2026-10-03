import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TodoView from '@/views/TodoView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import todoViewSource from '@/views/TodoView.vue?raw'

describe('TodoView', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
    data.taskLoadState = 'loaded'
    useSessionStore().accept({
      accountId: 'a1',
      member: { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' },
      identities: [],
      createdAt: '2026-08-11T09:00:00+08:00',
    })
  })

  it('三种视图保留主完成但子项未收尾的任务，全部收尾才隐藏且可重新打开', async () => {
    const source = data.tasks[0]!
    data.tasks = [
      { ...source, id: 'unfinished', title: '仍待下级验收', owner: 'wg', pr: 'P2', status: '已完成', subs: [{ id: 'p', text: '父', done: true }, { parentId: 'p', text: '子', done: false, status: '待验证' }] },
      { ...source, id: 'closed', title: '已经全部收尾', owner: 'wg', status: '已完成', subs: [] },
      { ...source, id: 'verify', title: '主任务待验证', owner: 'wg', status: '待验证', subs: [] },
      { ...source, id: 'outside', title: '他人的未收尾', owner: 'wd', parts: [], status: '待验证', subs: [] },
    ]
    const wrapper = mount(TodoView)
    for (const index of [0, 1, 2]) {
      await wrapper.findAll('.seg-btn')[index]!.trigger('click'); await flushPromises()
      const rows = wrapper.findAll('.trow, .board-card, .quadrant-task')
      expect(rows.some(row => row.text().includes('仍待下级验收'))).toBe(true)
      expect(wrapper.text()).toContain('仍有 1 项子项未收尾')
      expect(rows.some(row => row.text().includes('已经全部收尾'))).toBe(false)
      expect(rows.some(row => row.text().includes('主任务待验证'))).toBe(true)
      expect(rows.some(row => row.text().includes('他人的未收尾'))).toBe(false)
    }
    expect(data.taskById('unfinished')!.status).toBe('已完成')
    await wrapper.findAll('.seg-btn')[0]!.trigger('click')
    await wrapper.findAll('.trow').find(row => row.text().includes('仍待下级验收'))!.get('.check').trigger('click')
    expect(data.taskById('unfinished')!.status).not.toBe('已完成')
    expect(data.taskById('unfinished')!.subs[1]!.done).toBe(false)
    wrapper.unmount()
  })

  it('五种日期分组保持筛选与完成折叠，今天执行但以后截止不混组', async () => {
    data.today = '2026-10-03'
    const base = data.tasks[0]!
    data.tasks = [
      { ...base, id: 'late', title: '逾期等待', due: '2026-10-02', waiting: '等待同事', status: '待办', owner: 'wg', parts: [] },
      { ...base, id: 'today', title: '今日到期', due: '2026-10-03', status: '待办', owner: 'wg', parts: [] },
      { ...base, id: 'week', title: '周日到期', due: '2026-10-04', status: '待验证', owner: 'wg', parts: [] },
      { ...base, id: 'later', title: '未来截止今天执行', due: '2026-10-05', start: '2026-10-03', status: '待办', owner: 'wg', parts: [] },
      { ...base, id: 'none', title: '无截止有安排', due: '', start: '2026-10-03', status: '待办', owner: 'other', parts: ['wg'] },
      { ...base, id: 'done', title: '已收尾', due: '', status: '已完成', subs: [], owner: 'wg', parts: [] },
    ] as typeof data.tasks
    const w = mount(TodoView)
    expect(w.findAll('.tgroup-label').map(el => el.text())).toEqual(['逾期', '今天到期', '本周到期', '以后到期', '无截止日期', '已完成'])
    expect(w.findAll('.tgroup').find(el => el.text().includes('以后到期'))!.text()).toContain('今天执行')
    expect(w.findAll('.trow')).toHaveLength(5)
    await w.findAll('.chip').find(el => el.text() === '等待他人')!.trigger('click')
    expect(w.findAll('.tgroup-label').map(el => el.text())).toEqual(['逾期'])
    await w.get('select.project-filter').setValue('unassigned')
    expect(w.text()).toContain('这个筛选下没有任务')
    await w.get('select.project-filter').setValue('all')
    await w.findAll('.chip').find(el => el.text() === '我参与')!.trigger('click')
    expect(w.findAll('.tgroup-label').map(el => el.text())).toEqual(['无截止日期'])
    for (const index of [1, 2]) {
      await w.findAll('.seg-btn')[index]!.trigger('click'); await flushPromises()
      expect(w.text()).toContain('今天执行')
    }
    w.unmount()
  })

  it('真实任务未返回时只显示加载状态，不展示启动 fixtures', () => {
    data.taskLoadState = 'loading'
    const fixtureTitle = data.tasks[0]!.title

    const wrapper = mount(TodoView)

    expect(wrapper.get('[role="status"]').text()).toContain('正在加载中')
    expect(wrapper.text()).not.toContain(fixtureTitle)
    expect(wrapper.find('.trow').exists()).toBe(false)
    wrapper.unmount()
  })

  it('真实任务读取失败时显示错误，并可主动重新加载', async () => {
    data.taskLoadState = 'error'
    data.taskLoadError = '任务接口超时'
    const reload = vi.spyOn(data, 'hydrate').mockResolvedValue()

    const wrapper = mount(TodoView)
    expect(wrapper.get('[role="alert"]').text()).toContain('任务接口超时')
    expect(wrapper.find('.trow').exists()).toBe(false)

    await wrapper.get('[role="alert"] button').trigger('click')
    expect(reload).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('提供列表、看板和四象限入口，历史 PDCA 数据不再形成用户可见视图', () => {
    data.tasks[0]!.pdcaCycles = [{
      id: 'pc-history', plan: '旧计划', check: '旧复盘', act: '旧行动', outcome: '达成',
      startedAt: '2026-08-11T09:00:00+08:00', reviewedAt: '2026-08-11T20:00:00+08:00',
    }]
    const wrapper = mount(TodoView)

    expect(wrapper.findAll('.seg-btn').map((button) => button.text())).toEqual(['列表', '看板', '四象限'])
    expect(wrapper.find('.pdca-view').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('PDCA')
    wrapper.unmount()
  })

  it('列表与看板都能对命中的任务打开同一组右键操作', async () => {
    const wrapper = mount(TodoView)
    const row = wrapper.get('.trow')
    const title = row.get('.trow-title').text()
    await row.trigger('contextmenu', { clientX: 90, clientY: 90 })
    await flushPromises()
    expect(document.body.querySelector('[role="menu"]')?.getAttribute('aria-label')).toBe(`任务：${title}的操作`)
    expect(document.body.querySelector('[role="menu"]')?.textContent).toContain('复制任务编号')
    await wrapper.findAll('.seg-btn')[1]!.trigger('click')
    const board = wrapper.findAll('.board-card').find(card => card.get('.bc-title').text() === title)!
    await board.trigger('contextmenu')
    await flushPromises()
    expect(document.body.querySelector('[role="menu"]')?.getAttribute('aria-label')).toBe(`任务：${title}的操作`)
    wrapper.unmount()
  })

  it('看板卡片中的长链接会在列内断行且保留完整内容', async () => {
    const longUrl = `https://open.work.weixin.qq.com/help2/${'unbroken-path-'.repeat(30)}`
    const task = data.tasks.find((item) => item.owner === 'wg')!
    task.title = `参考：${longUrl}`
    const wrapper = mount(TodoView)

    await wrapper.findAll('.seg-btn')[1]!.trigger('click')
    const card = wrapper.findAll('.board-card').find((item) => item.text().includes(longUrl))!
    const title = card.get('.bc-title')

    expect(title.text()).toBe(`参考：${longUrl}`)
    expect(todoViewSource).toMatch(/\.board-card\s*{[^}]*max-width:\s*100%/s)
    expect(todoViewSource).toMatch(/\.bc-title\s*{[^}]*overflow-wrap:\s*anywhere/s)
    wrapper.unmount()
  })
})
