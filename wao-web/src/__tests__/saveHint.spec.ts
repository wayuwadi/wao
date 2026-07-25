import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SaveHint from '@/components/ui/SaveHint.vue'

const AT = '2026-07-25T14:32:00+08:00'

describe('SaveHint（自动保存回执）', () => {
  it('没保存过也没在保存时什么都不显示', () => {
    const w = mount(SaveHint, { props: { savedAt: null } })
    expect(w.text()).toBe('')
  })

  it('写透中显示「保存中…」，此时不显示旧的成功时间', () => {
    const w = mount(SaveHint, { props: { saving: true, savedAt: AT } })
    expect(w.text()).toContain('保存中')
    expect(w.text()).not.toContain('自动保存成功')
  })

  it('成功后显示「自动保存成功 + 时分」', () => {
    const w = mount(SaveHint, { props: { savedAt: AT } })
    expect(w.text()).toContain('自动保存成功')
    expect(w.text()).toMatch(/\d{1,2}:\d{2}/)
  })

  it('失败时不谎报成功，明说内容留在本机', () => {
    const w = mount(SaveHint, { props: { failed: true, savedAt: AT } })
    expect(w.text()).toContain('未保存')
    expect(w.text()).not.toContain('自动保存成功')
  })
})
