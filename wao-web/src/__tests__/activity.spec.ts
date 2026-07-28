import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { activitySentence } from '@/utils/activity'

/** 活动时间线：事后要能看出动的是哪一条，而不只是「你添加了子任务」。 */
describe('data store · 活动记录', () => {
  let data: ReturnType<typeof useDataStore>
  let taskId: string

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    taskId = data.tasks.find((t) => t.status !== '已完成')!.id
    data.taskById(taskId)!.subs = []
    data.taskById(taskId)!.activity = []
  })

  const latest = () => data.taskById(taskId)!.activity[0]?.text ?? ''

  it('添加子任务时记下具体是哪一条', () => {
    data.addSub(taskId, '扫描身份证')
    expect(latest()).toBe('添加了子任务「扫描身份证」')
  })

  it('完成 / 重新打开子任务都记进时间线', () => {
    const id = data.addSub(taskId, '扫描身份证')!

    data.updateSub(taskId, id, { done: true })
    expect(latest()).toBe('完成了子任务「扫描身份证」')

    data.updateSub(taskId, id, { done: false })
    expect(latest()).toBe('重新打开了子任务「扫描身份证」')
  })

  it('从列表勾选子任务（按下标）同样记录', () => {
    data.addSub(taskId, '归档到 NAS')

    data.toggleStep(taskId, 0, 'subs')

    expect(latest()).toBe('完成了子任务「归档到 NAS」')
  })

  it('勾选步骤也记录，且区分「步骤」和「子任务」', () => {
    data.taskById(taskId)!.steps = [{ text: '先分堆', done: false }]

    data.toggleStep(taskId, 0, 'steps')

    expect(latest()).toBe('完成了步骤「先分堆」')
  })

  it('删除子任务记进时间线', () => {
    const id = data.addSub(taskId, '要删掉的')!

    data.removeSub(taskId, id)

    expect(latest()).toBe('删除了子任务「要删掉的」')
  })

  it('改描述 / 加图片不写活动——逐次按键触发，写了会把时间线刷成一行一个字', () => {
    const id = data.addSub(taskId, '带描述的')!
    const before = data.taskById(taskId)!.activity.length

    data.updateSub(taskId, id, { desc: '一' })
    data.updateSub(taskId, id, { desc: '一段' })
    data.addSubImage(taskId, id, { name: 'a.png', url: '/api/images/img1/raw' })

    expect(data.taskById(taskId)!.activity).toHaveLength(before)
  })

  it('改名不写活动，同样是防刷屏', () => {
    const id = data.addSub(taskId, '旧名字')!
    const before = data.taskById(taskId)!.activity.length

    data.updateSub(taskId, id, { text: '新名字' })

    expect(data.taskById(taskId)!.activity).toHaveLength(before)
    expect(data.subById(taskId, id)?.text).toBe('新名字')
  })

  it('完成 / 重新打开任务本身也记录——原来两个方向都是空白', () => {
    data.toggleComplete(taskId)
    expect(latest()).toBe('完成了任务')

    data.toggleComplete(taskId)
    expect(latest()).toBe('重新打开了任务')
  })

  it('正文不带主语，操作人单独记在 by 上', () => {
    // 未登录的演示态回落到 wg，与 data store 的 currentAuthor 口径一致
    const me = useSessionStore().currentUser.id || 'wg'

    data.addSub(taskId, '扫描身份证')

    const entry = data.taskById(taskId)!.activity[0]!
    expect(entry.text).not.toContain('你')
    expect(entry.by).toBe(me)
    // 时间是真实时间戳，不是写死的「刚刚」
    expect(entry.time).toMatch(/^\d{4}-\d{2}-\d{2}T/)
  })
})

/** 主语按「看的人是谁」现算：以前一律写「你」，别人打开也显示成自己干的。 */
describe('活动文案 · 主语', () => {
  const nameOf = (id: string) => ({ wg: '娃哥', wd: '娃弟' })[id] ?? id

  it('自己干的写「你」，别人干的写名字', () => {
    expect(activitySentence({ text: '创建了任务', by: 'wg' }, 'wg', nameOf)).toBe('你创建了任务')
    expect(activitySentence({ text: '创建了任务', by: 'wd' }, 'wg', nameOf)).toBe('娃弟创建了任务')
  })

  it('历史数据没有 by、正文里已经带着「你」，原样展示不去猜', () => {
    expect(activitySentence({ text: '你创建了任务', by: null }, 'wg', nameOf)).toBe('你创建了任务')
  })
})
