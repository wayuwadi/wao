import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LedgerView from '@/views/LedgerView.vue'
import type { LedgerBook, LedgerBookDetail, LedgerEntry } from '@/api/ledger'

const listBooks = vi.fn()
const getBook = vi.fn()
const createEntry = vi.fn()

vi.mock('@/api/ledger', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/ledger')>()
  return { ...actual, listLedgerBooks: (...args: unknown[]) => listBooks(...args),
    getLedgerBook: (...args: unknown[]) => getBook(...args),
    createLedgerEntry: (...args: unknown[]) => createEntry(...args) }
})
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

const book: LedgerBook = {
  id: 'book-1', title: '兄弟明账', ownerMemberId: 'wd', role: 'OWNER', updatedAt: '2026-09-25T09:00:00',
}
const entry: LedgerEntry = {
  id: 'entry-1', bookId: book.id, title: '旧款', counterparty: '兄弟', category: 'OLD',
  direction: 'RECEIVE', status: 'CONFIRMED', amount: 1000, settledAmount: 200,
  remainingAmount: 800, occurredOn: '2026-09-19', dueOn: '2026-10-15',
  evidence: '双方确认', disputeNote: null, nextAction: '月底核对', createdBy: 'wd',
  updatedBy: 'wd', version: 2, updatedAt: '2026-09-25T09:00:00', settlements: [], revisions: [],
}
const detail: LedgerBookDetail = {
  book, members: [{ memberId: 'wg', role: 'EDITOR' }], entries: [entry],
  summary: { receivable: 800, payable: 0, net: 800, disputedCount: 0,
    toVerifyCount: 0, undatedConfirmedCount: 0 },
}

async function mountLedger() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: LedgerView }] })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(LedgerView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('账务本浏览器页面', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    listBooks.mockReset()
    getBook.mockReset()
    createEntry.mockReset()
  })

  it('没有账本时展示私人创建入口，不出现演示金额', async () => {
    listBooks.mockResolvedValue([])
    const wrapper = await mountLedger()
    expect(wrapper.text()).toContain('还没有账本')
    expect(wrapper.text()).toContain('默认只有你能看到')
    expect(wrapper.text()).not.toContain('¥800')
  })

  it('共享账本显示真实汇总，并按月列出已确认到期金额', async () => {
    listBooks.mockResolvedValue([book])
    getBook.mockResolvedValue(detail)
    const wrapper = await mountLedger()
    expect(wrapper.text()).toContain('¥800.00')
    expect(wrapper.text()).toContain('旧款')
    await wrapper.findAll('.switches button')[1]!.trigger('click')
    expect(wrapper.text()).toContain('2026-10')
    expect(wrapper.text()).toContain('到期应收')
  })

  it('归类筛选后详情只显示筛选范围内的账目', async () => {
    listBooks.mockResolvedValue([book])
    getBook.mockResolvedValue({ ...detail, entries: [entry,
      { ...entry, id: 'entry-2', title: '争议旧款', category: 'BAD' }] })
    const wrapper = await mountLedger()
    await wrapper.findAll('.filters button')[2]!.trigger('click')
    expect(wrapper.find('.entry-detail h2').text()).toBe('争议旧款')
    expect(wrapper.findAll('.entry-row')).toHaveLength(1)
  })

  it('保存失败时保留输入和会话草稿以便重试', async () => {
    listBooks.mockResolvedValue([book])
    getBook.mockResolvedValue(detail)
    createEntry.mockRejectedValue(new Error('服务暂不可用'))
    const wrapper = await mountLedger()
    await wrapper.find('.top-actions .primary').trigger('click')
    const inputs = wrapper.findAll('.editor-dialog input')
    await inputs[0]!.setValue('待核对借款')
    await inputs[1]!.setValue('兄弟')
    await inputs[2]!.setValue('200')
    await wrapper.find('.editor-dialog .dialog-actions .primary').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('服务暂不可用')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('待核对借款')
    expect(sessionStorage.getItem('wao-ledger-draft:book-1:new')).toContain('待核对借款')
  })

  it('切换编辑对象时把原草稿保存在原账目名下', async () => {
    listBooks.mockResolvedValue([book])
    getBook.mockResolvedValue(detail)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    try {
      const wrapper = await mountLedger()
      await wrapper.find('.top-actions .primary').trigger('click')
      await wrapper.find('.editor-dialog input').setValue('未保存的新账')
      await wrapper.find('.detail-head .ghost').trigger('click')
      expect(sessionStorage.getItem('wao-ledger-draft:book-1:new')).toContain('未保存的新账')
      expect(sessionStorage.getItem('wao-ledger-draft:book-1:entry-1')).toBeNull()
      expect(wrapper.find('.editor-dialog input').element).toHaveProperty('value', '旧款')
    } finally {
      confirm.mockRestore()
    }
  })

  it('服务端已保存而刷新失败时明确提示已保存', async () => {
    listBooks.mockResolvedValue([book])
    getBook.mockResolvedValueOnce(detail).mockRejectedValueOnce(new Error('刷新失败'))
    createEntry.mockResolvedValue({ ...entry, id: 'entry-2' })
    const wrapper = await mountLedger()
    await wrapper.find('.top-actions .primary').trigger('click')
    const inputs = wrapper.findAll('.editor-dialog input')
    await inputs[0]!.setValue('新账')
    await inputs[1]!.setValue('兄弟')
    await inputs[2]!.setValue('200')
    await wrapper.find('.editor-dialog .dialog-actions .primary').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('账目已保存，但列表刷新失败')
    expect(wrapper.find('.editor-dialog').exists()).toBe(false)
  })
})
