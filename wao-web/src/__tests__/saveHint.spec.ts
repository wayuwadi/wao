import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import { useDataStore } from '@/stores/data'

describe('SaveHint（自动保存回执）', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('没保存过也没在保存时什么都不显示', () => {
    const w = mount(SaveHint, { props: { saveKey: 'task:t1' } })
    expect(w.text()).toBe('')
  })

  it('去抖窗口内（请求还没发出）就显示「保存中…」', () => {
    const w = mount(SaveHint, { props: { saveKey: 'doc:d1', pending: true } })
    expect(w.text()).toContain('保存中')
  })

  it('正在写透时显示「保存中…」，成功后换成「自动保存成功 + 时分」', async () => {
    const data = useDataStore()
    const w = mount(SaveHint, { props: { saveKey: 'task:t9' } })

    data.markSaving('task:t9')
    await nextTick()
    expect(w.text()).toContain('保存中')

    data.markSaved('task:t9', true)
    await nextTick()
    expect(w.text()).toContain('自动保存成功')
    expect(w.text()).toMatch(/\d{1,2}:\d{2}/)
  })

  it('写透失败不谎报成功，也不抹掉上一次成功的时间', async () => {
    const data = useDataStore()
    const w = mount(SaveHint, { props: { saveKey: 'task:t8' } })

    data.markSaving('task:t8')
    data.markSaved('task:t8', true)
    await nextTick()
    const okText = w.text()

    data.markSaving('task:t8')
    data.markSaved('task:t8', false)
    await nextTick()
    expect(w.text()).toBe(okText)
  })
})
