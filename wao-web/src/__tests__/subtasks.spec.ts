import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'

/** 子任务读写：内嵌在任务的 subs 里，靠稳定 id 定位（历史数据回落到下标合成键）。 */
describe('data store · 子任务', () => {
  let data: ReturnType<typeof useDataStore>
  let taskId: string

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    taskId = data.tasks[0]!.id
    data.tasks[0]!.subs = []
  })

  it('新建子任务带 id、描述与图片位', () => {
    const id = data.addSub(taskId, '  扫描身份证  ')
    expect(id).toMatch(/^s\w+$/)

    const sub = data.subById(taskId, id!)
    expect(sub?.text).toBe('扫描身份证')
    expect(sub?.done).toBe(false)
    expect(sub?.desc).toBe('')
    expect(sub?.images).toEqual([])
  })

  it('标题为空时不创建', () => {
    expect(data.addSub(taskId, '   ')).toBeNull()
    expect(data.taskById(taskId)!.subs).toHaveLength(0)
  })

  it('可以改名和补描述——原来创建后就改不了了', () => {
    const id = data.addSub(taskId, '旧名字')!
    data.updateSub(taskId, id, { text: '新名字', desc: '## 做法\n先分堆' })

    const sub = data.subById(taskId, id)
    expect(sub?.text).toBe('新名字')
    expect(sub?.desc).toContain('先分堆')
  })

  it('增删子任务图片', () => {
    const id = data.addSub(taskId, '带图的子任务')!
    data.addSubImage(taskId, id, { name: 'a.png', url: '/api/images/img1/raw' })
    data.addSubImage(taskId, id, { name: 'b.png', url: '/api/images/img2/raw' })
    expect(data.subById(taskId, id)?.images).toHaveLength(2)

    data.removeSubImage(taskId, id, 0)
    expect(data.subById(taskId, id)?.images).toEqual([{ name: 'b.png', url: '/api/images/img2/raw' }])
  })

  it('删除子任务后按 id 查不到', () => {
    const id = data.addSub(taskId, '要删掉的')!
    data.removeSub(taskId, id)
    expect(data.subById(taskId, id)).toBeUndefined()
    expect(data.taskById(taskId)!.subs).toHaveLength(0)
  })

  it('历史数据没有 id 时按下标合成键定位', () => {
    data.taskById(taskId)!.subs = [{ text: '老子任务', done: false }]
    const key = data.subKey(taskId, data.taskById(taskId)!.subs[0]!, 0)

    expect(key).toBe(`${taskId}#0`)
    expect(data.subById(taskId, key)?.text).toBe('老子任务')
  })

  it('新增的子任务排在最前面——输入框就在列表上方，刚敲完的要看得见', () => {
    data.addSub(taskId, '先加的')
    data.addSub(taskId, '后加的')

    expect(data.taskById(taskId)!.subs.map((s) => s.text)).toEqual(['后加的', '先加的'])
  })

  it('删除中间项后，后面的 id 仍然指向同一条子任务', () => {
    const a = data.addSub(taskId, 'A')!
    const b = data.addSub(taskId, 'B')!
    const c = data.addSub(taskId, 'C')!
    data.removeSub(taskId, b)

    expect(data.subById(taskId, a)?.text).toBe('A')
    expect(data.subById(taskId, c)?.text).toBe('C')
  })

  it('拖拽排序只移动位置，稳定 id 与内容保持不变', () => {
    const a = data.addSub(taskId, 'A')!
    const b = data.addSub(taskId, 'B')!
    data.addSub(taskId, 'C')

    data.reorderSub(taskId, a, b)

    expect(data.taskById(taskId)!.subs.map((sub) => sub.text)).toEqual(['C', 'A', 'B'])
    expect(data.subById(taskId, a)?.text).toBe('A')
    expect(data.taskById(taskId)!.activity[0]!.text).toContain('调整了子任务')
  })
})

describe('data store · 新建任务', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('立即返回本地任务，不等后端往返', async () => {
    const data = useDataStore()
    const before = data.tasks.length

    const { task: t } = await data.addTask({
      title: '立刻出现的任务', project: 'p1', pr: 'P2', owner: 'wg', due: '2026-08-01',
      desc: '', images: [], startAt: null, endAt: null,
    })

    expect(t.title).toBe('立刻出现的任务')
    expect(t.status).toBe('待办')
    expect(data.tasks).toHaveLength(before + 1)
    expect(data.tasks[0]!.id).toBe(t.id)
  })
})
