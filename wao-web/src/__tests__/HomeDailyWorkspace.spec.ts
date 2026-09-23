import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import HomeDailyWorkspace from '@/components/home/HomeDailyWorkspace.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'
import type { Doc } from '@/types'

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-23T04:00:00Z'))
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})
function setup() {
  const data = seedDataStore()
  useSessionStore().$patch({ account: { member: { id: 'wg', name: '娃哥' } } } as never)
  vi.spyOn(data, 'ensureDocsLoaded').mockResolvedValue(true)
  vi.spyOn(data, 'ensureDocLoaded').mockResolvedValue(false)
  data.tasks = []
  data.docs = {}
  return data
}
const doc = (id: string, patch: Partial<Doc> = {}): Doc => ({
  id,
  title: id,
  author: 'wg',
  kbId: 'kb-notes',
  created: '2026-09-23T10:00:00+08:00',
  updated: '2026-09-23T10:00:00+08:00',
  links: [],
  blocks: [],
  backlinks: [],
  content: { version: 1, document: null, markdown: '**中文** A 👨‍👩‍👧‍👦' },
  ...patch,
})
async function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/')
  return { wrapper: mount(HomeDailyWorkspace, { global: { plugins: [router] } }), router }
}
it('今日待办只包含本人负责或参与的今天及逾期未完成事项，并打开真实任务', async () => {
  const data = seedDataStore()
  const template = { ...data.tasks[0]! }
  setup()
  data.tasks = [
    {
      ...template,
      id: 'mine',
      title: '本人今日任务',
      owner: 'wg',
      parts: [],
      status: '待办',
      due: '2026-09-23',
    },
    {
      ...template,
      id: 'part',
      title: '参与逾期任务',
      owner: 'other',
      parts: ['wg'],
      status: '进行中',
      due: '2026-09-22',
    },
    {
      ...template,
      id: 'other',
      title: '他人任务',
      owner: 'other',
      parts: [],
      status: '待办',
      due: '2026-09-23',
    },
    {
      ...template,
      id: 'future',
      title: '未来任务',
      owner: 'wg',
      status: '待办',
      due: '2026-09-24',
    },
    {
      ...template,
      id: 'done',
      title: '完成任务',
      owner: 'wg',
      status: '已完成',
      due: '2026-09-20',
    },
  ]
  const open = vi.spyOn(useUiStore(), 'openTask')
  const { wrapper } = await render()
  expect(wrapper.findAll('.task-row').map((row) => row.text())).toHaveLength(2)
  expect(wrapper.findAll('.task-row')[0]!.text()).toContain('参与逾期任务')
  await wrapper.findAll('.task-row')[0]!.trigger('click')
  expect(open).toHaveBeenCalledWith('part')
  wrapper.unmount()
})
it('最近创作过滤其他作者及自动归档，按更新时间排序并统计正文可见字数', async () => {
  const data = setup()
  data.kbs = [{ ...data.kbs[0]!, id: 'kb-notes' }]
  data.docs = {
    older: doc('older'),
    newer: doc('newer', { updated: '2026-09-23T11:00:00+08:00' }),
    other: doc('other', { author: 'other' }),
    auto: doc('auto', { auto: true }),
  }
  const { wrapper, router } = await render()
  expect(wrapper.findAll('.writing-row')).toHaveLength(2)
  expect(wrapper.findAll('.writing-row')[0]!.text()).toContain('newer')
  expect(wrapper.findAll('.writing-row')[0]!.text()).toContain('4 字')
  await wrapper.findAll('.writing-row')[0]!.trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/kb/kb-notes?doc=newer')
  wrapper.unmount()
})
it('未知正文不显示零字，读取失败可重试，正文返回后显示真实字数', async () => {
  const data = setup()
  data.kbs = [{ ...data.kbs[0]!, id: 'kb-notes' }]
  data.docs = { unloaded: doc('unloaded', { bodyLoaded: false }) }
  const { wrapper } = await render()
  expect(data.ensureDocLoaded).toHaveBeenCalledWith('unloaded')
  expect(wrapper.get('.writing-row').text()).toContain('正在读取字数')
  expect(wrapper.get('.writing-row').text()).not.toContain('0 字')
  data.docLoadErrors.unloaded = '网络断开'
  await flushPromises()
  await wrapper.get('.retry-body').trigger('click')
  expect(data.ensureDocLoaded).toHaveBeenCalledTimes(2)
  data.docs.unloaded!.bodyLoaded = true
  await flushPromises()
  expect(wrapper.get('.writing-row').text()).toContain('4 字')
  wrapper.unmount()
})
it('读取错误不显示为空数据，项目排除完成与搁置且不伪造更新时间', async () => {
  const data = setup()
  const template = data.projects[0]!
  data.projects = [
    { ...template, id: 'active', name: '继续推进', status: '进行中' },
    { ...template, id: 'done', status: '已完成' },
    { ...template, id: 'paused', status: '已搁置' },
  ]
  data.loadErrors.tasks = '失败'
  const { wrapper } = await render()
  expect(wrapper.text()).toContain('待办读取失败')
  expect(wrapper.text()).not.toContain('今天没有到期')
  expect(wrapper.findAll('.project-link')).toHaveLength(1)
  expect(wrapper.get('.project-link').text()).not.toContain('刚刚')
  wrapper.unmount()
})
