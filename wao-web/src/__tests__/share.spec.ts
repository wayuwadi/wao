import { taskShareTarget } from '@/utils/share'
import { describe, expect, it } from 'vitest'
import { shareMarkdownUrl, shareUrl } from '@/utils/share'

describe('分享链接', () => {
  it('主任务链接指向分享页', () => {
    expect(shareUrl('t1')).toBe(`${window.location.origin}/s/t/t1`)
  })

  it('子任务链接带上子任务标识', () => {
    expect(shareUrl('t1', 's9')).toBe(`${window.location.origin}/s/t/t1/s9`)
  })

  it('下标合成键里的 # 会被转义，否则会被当成 URL fragment 丢掉', () => {
    expect(shareUrl('t1', 't1#0')).toBe(`${window.location.origin}/s/t/t1/t1%230`)
  })

  it('Markdown 地址走后端，AI 一次 GET 就能读到全文', () => {
    expect(shareMarkdownUrl('t1')).toBe(`${window.location.origin}/api/share/tasks/t1.md`)
    expect(shareMarkdownUrl('t1', 's9')).toBe(`${window.location.origin}/api/share/tasks/t1/subs/s9.md`)
  })

  it('只分享未完成子任务时带上 openOnly', () => {
    expect(shareUrl('t1', null, true)).toBe(`${window.location.origin}/s/t/t1?openOnly=true`)
    expect(shareMarkdownUrl('t1', null, true)).toBe(`${window.location.origin}/api/share/tasks/t1.md?openOnly=true`)
  })

  it('子任务链接上 openOnly 无意义，不带这个参数', () => {
    expect(shareUrl('t1', 's9', true)).toBe(`${window.location.origin}/s/t/t1/s9`)
    expect(shareMarkdownUrl('t1', 's9', true)).toBe(`${window.location.origin}/api/share/tasks/t1/subs/s9.md`)
  })
})

describe('聊天任务链接解析', () => {
  it('支持分享页和 Markdown 子任务链接，拒绝外站仿冒路径', () => {
    expect(taskShareTarget('/s/t/t1/s2')).toEqual({ taskId: 't1', subId: 's2' })
    expect(taskShareTarget('/api/share/tasks/t1.md?openOnly=true')).toEqual({ taskId: 't1' })
    expect(taskShareTarget('/api/share/tasks/t1/subs/s2.md')).toEqual({ taskId: 't1', subId: 's2' })
    expect(taskShareTarget('https://evil.test/s/t/t1')).toBeNull()
  })
})
