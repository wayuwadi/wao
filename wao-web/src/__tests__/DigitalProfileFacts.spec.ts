import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import DigitalProfileFacts from '@/components/settings/DigitalProfileFacts.vue'

const api = vi.hoisted(() => ({ load: vi.fn(), save: vi.fn() }))
let session: { userId: string }
vi.mock('@/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('@/api/digitalMembers', () => ({ fetchDigitalLibrary: api.load, saveDigitalProfile: api.save }))
const options = { global: { stubs: { RouterLink: true } } }
beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
  session = reactive({ userId: 'one' })
  api.load.mockResolvedValue({ profile: { gender: 'unknown', revision: 2 } })
})
describe('个人事实草稿保护', () => {
  it('旧草稿保持自己的CAS版本，显式rebase才写入账号事实', async () => {
    localStorage.setItem('wao-digital-profile-draft-v1:one', JSON.stringify({ gender: 'male', expectedRevision: 1 }))
    api.save.mockResolvedValue({ gender: 'male', revision: 3 })
    const wrapper = mount(DigitalProfileFacts, options)
    await flushPromises()
    expect(wrapper.text()).toContain('账号事实已更新')
    expect(wrapper.get('select').element).toHaveProperty('value', 'male')
    await wrapper.get('.primary').trigger('click')
    expect(api.save).not.toHaveBeenCalled()
    await wrapper.get('.error button').trigger('click')
    await wrapper.get('.primary').trigger('click')
    await flushPromises()
    expect(api.save).toHaveBeenCalledWith('male', 2)
    expect(localStorage.getItem('wao-digital-profile-draft-v1:one')).toBeNull()
    wrapper.unmount()
  })
  it('切换账号后迟到保存不覆盖新账号表单或草稿', async () => {
    let finish!: (value: unknown) => void
    api.save.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(DigitalProfileFacts, options)
    await flushPromises()
    await wrapper.get('select').setValue('male')
    await wrapper.get('.primary').trigger('click')
    api.load.mockResolvedValue({ profile: { gender: 'female', revision: 8 } })
    session.userId = 'two'
    await flushPromises()
    finish({ gender: 'male', revision: 3 })
    await flushPromises()
    expect(wrapper.get('select').element).toHaveProperty('value', 'female')
    expect(localStorage.getItem('wao-digital-profile-draft-v1:one')).toContain('"gender":"male"')
    expect(localStorage.getItem('wao-digital-profile-draft-v1:two')).toBeNull()
    wrapper.unmount()
  })
})
