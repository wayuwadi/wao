import { beforeEach, describe, it, expect } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTabsStore } from '@/stores/tabs'
import type { Tab } from '@/types'

const proj = (id: string): Tab => ({
  key: `proj:${id}`,
  label: `项目 ${id}`,
  icon: 'ri-stack-line',
  path: `/projects/${id}`,
  closable: true,
})

describe('tabs store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('seeds a pinned home tab', () => {
    const tabs = useTabsStore()
    expect(tabs.tabs[0]?.key).toBe('home')
    expect(tabs.tabs[0]?.closable).toBe(false)
    expect(tabs.activeKey).toBe('home')
  })

  it('opens, dedupes and activates tabs', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p1'))
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'proj:p1', 'proj:p2'])
    expect(tabs.activeKey).toBe('proj:p1')
  })

  it('activates a neighbour when closing the active tab', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.activate('proj:p1')
    const next = tabs.close('proj:p1')
    expect(next?.key).toBe('proj:p2')
    expect(tabs.activeKey).toBe('proj:p2')
  })

  it('never closes the pinned home tab', () => {
    const tabs = useTabsStore()
    expect(tabs.close('home')).toBeNull()
    tabs.open(proj('p1'))
    tabs.closeAll()
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home'])
    expect(tabs.activeKey).toBe('home')
  })

  it('closeOthers keeps home and the target', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))
    tabs.closeOthers('proj:p2')
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'proj:p2'])
  })
})
