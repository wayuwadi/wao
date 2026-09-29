import { beforeEach, describe, expect, it } from 'vitest'
import { retireUnusedStorage } from '@/utils/retireUnusedStorage'

describe('下线功能的浏览器草稿', () => {
  beforeEach(() => { localStorage.clear(); sessionStorage.clear() })

  it('删除下线功能草稿并保留决策和项目 AI 草稿', () => {
    localStorage.setItem('wao-weekly-report-draft-v1:member-1', 'old')
    localStorage.setItem('wao:unified-memory-draft:personal:member-1:new', 'old')
    localStorage.setItem('wao:decision-draft:member-1:new', 'keep')
    localStorage.setItem('wao-project-memory-draft:project-1', 'keep')
    sessionStorage.setItem('wao-ledger-draft:book-1:new', 'old')
    retireUnusedStorage()
    expect(localStorage.getItem('wao-weekly-report-draft-v1:member-1')).toBeNull()
    expect(localStorage.getItem('wao:unified-memory-draft:personal:member-1:new')).toBeNull()
    expect(sessionStorage.getItem('wao-ledger-draft:book-1:new')).toBeNull()
    expect(localStorage.getItem('wao:decision-draft:member-1:new')).toBe('keep')
    expect(localStorage.getItem('wao-project-memory-draft:project-1')).toBe('keep')
  })
})
