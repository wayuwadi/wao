import { describe, expect, it, vi } from 'vitest'
import { reactive, watch } from 'vue'
import { mergeServerList } from '@/utils/mergeServerList'

describe('服务端快照合并', () => {
  it('无变化保留嵌套引用，有变化只更新对应字段并维持原顺序', () => {
    const local = reactive([
      { id: 'a', title: '本地', subs: [{ id: 's', done: false }] },
      { id: 'b', title: '另一条', subs: [] },
    ])
    const first = local[0]!
    const subs = first.subs
    const changed = vi.fn()
    const stop = watch(() => first.subs, changed, { flush: 'sync' })
    mergeServerList(local, [
      { id: 'b', title: '另一条', subs: [] },
      { id: 'a', title: '远端新标题', subs: [{ id: 's', done: false }] },
    ])
    expect(local.map(item => item.id)).toEqual(['a', 'b'])
    expect(local[0]).toBe(first)
    expect(first.title).toBe('远端新标题')
    expect(first.subs).toBe(subs)
    expect(changed).not.toHaveBeenCalled()
    mergeServerList(local, [{ id: 'a', title: '远端新标题', subs: [{ id: 's', done: true }] }])
    expect(changed).toHaveBeenCalledOnce()
    expect(local).toHaveLength(1)
    expect(first.subs[0]!.done).toBe(true)
    stop()
  })

  it('远端更新和删除不覆盖草稿，不删除尚未确认的新建项', () => {
    const local = [
      { id: 'draft', title: '未保存输入' },
      { id: 'pending', title: '刚新建' },
      { id: 'deleted', title: '远端已删除' },
      { id: 'offline', title: '离线草稿' },
    ]
    mergeServerList(local, [
      { id: 'draft', title: '旧内容' },
      { id: 'new', title: '远端新建' },
    ], new Set(['draft', 'offline']), new Set(['pending']))
    expect(local).toEqual([
      { id: 'draft', title: '未保存输入' },
      { id: 'pending', title: '刚新建' },
      { id: 'offline', title: '离线草稿' },
      { id: 'new', title: '远端新建' },
    ])
  })
})
