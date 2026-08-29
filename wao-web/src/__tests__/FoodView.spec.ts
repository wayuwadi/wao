import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FoodView from '@/views/FoodView.vue'
import { fetchFoodWorkspace, saveFoodWorkspace, type FoodWorkspace } from '@/api/food'

vi.mock('@/api/food', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/food')>()
  return {
    ...original,
    fetchFoodWorkspace: vi.fn<(typeof import('@/api/food'))['fetchFoodWorkspace']>(),
    saveFoodWorkspace: vi.fn<(typeof import('@/api/food'))['saveFoodWorkspace']>(),
  }
})

const workspace = (): FoodWorkspace => ({
  templates: [],
  mealLogs: [],
  preparationTasks: [],
  profile: { preferences: '', avoidances: '', stomachObservations: '', efficiencyGoal: '' },
  principles: '饮食有节，按自己的日常节律安排三餐；吃后如实记录。',
  updatedAt: null,
})

describe('FoodView', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    vi.mocked(fetchFoodWorkspace).mockReset().mockResolvedValue(workspace())
    vi.mocked(saveFoodWorkspace)
      .mockReset()
      .mockImplementation(async (value) => ({
        ...structuredClone(value),
        updatedAt: '2026-08-29T20:00:00',
      }))
  })

  it('首次使用展示真实空状态和文化医疗边界，不填充演示餐单', async () => {
    const wrapper = mount(FoodView)
    await flushPromises()

    expect(wrapper.text()).toContain('还没有安排')
    expect(wrapper.text()).toContain('模板不算已完成')
    expect(wrapper.text()).toContain('不判断体质')
    expect(wrapper.findAll('.log-card')).toHaveLength(0)
  })

  it('记录真实一餐并在保存失败时保留完整本机草稿', async () => {
    vi.mocked(saveFoodWorkspace).mockRejectedValueOnce(new Error('网络已断开'))
    const wrapper = mount(FoodView)
    await flushPromises()
    await wrapper.get('.content-section .primary').trigger('click')
    await wrapper.get('.editor-modal input[placeholder^="米饭"]').setValue('小米粥、鸡蛋')
    await wrapper.get('.editor-modal textarea[placeholder^="分量"]').setValue('饭后感觉舒服')
    await wrapper.get('.editor-modal').trigger('submit')
    await vi.advanceTimersByTimeAsync(750)
    await flushPromises()

    expect(saveFoodWorkspace).toHaveBeenCalled()
    expect(wrapper.text()).toContain('网络已断开')
    expect(wrapper.text()).toContain('小米粥、鸡蛋')
    expect(localStorage.getItem('wao-food-workspace-draft-v1')).toContain('小米粥')

    vi.mocked(saveFoodWorkspace).mockImplementation(async (value) => ({
      ...structuredClone(value),
      updatedAt: '2026-08-29T20:01:00',
    }))
    await wrapper.get('.save-strip button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('刷新或换设备仍可读取')
    expect(localStorage.getItem('wao-food-workspace-draft-v1')).toBeNull()
  })

  it('恢复未同步草稿并只按真实记录汇总食物感受', async () => {
    const draft = workspace()
    draft.mealLogs.push(
      {
        id: 'fl1',
        date: '2026-08-28',
        dayType: 'WORKDAY',
        mealType: 'BREAKFAST',
        eatenAt: '08:00',
        foods: ['小米粥'],
        prepMinutes: 15,
        comfort: 'COMFORTABLE',
        qualityScore: 4,
        observation: '舒服',
      },
      {
        id: 'fl2',
        date: '2026-08-29',
        dayType: 'RESTDAY',
        mealType: 'BREAKFAST',
        eatenAt: '09:00',
        foods: ['小米粥'],
        prepMinutes: 20,
        comfort: 'UNCOMFORTABLE',
        qualityScore: 3,
        observation: '分量偏多',
      },
    )
    localStorage.setItem('wao-food-workspace-draft-v1', JSON.stringify(draft))

    const wrapper = mount(FoodView)
    await flushPromises()
    expect(wrapper.text()).toContain('已恢复上次未同步的本机草稿')
    await wrapper.findAll('.section-tabs button')[3]!.trigger('click')
    expect(wrapper.text()).toContain('小米粥')
    expect(wrapper.text()).toContain('共 2 次')
    expect(wrapper.text()).toContain('舒服 1 · 不舒服 1')
    expect(wrapper.text()).toContain('不代表医学上的“适合体质”')
  })

  it('逾期备餐事项只声称本页提醒', async () => {
    const data = workspace()
    data.preparationTasks.push({
      id: 'fp1',
      type: 'PREP',
      title: '提前浸泡杂粮',
      dueDate: '2020-01-01',
      status: 'OPEN',
      note: '',
    })
    vi.mocked(fetchFoodWorkspace).mockResolvedValue(data)
    const wrapper = mount(FoodView)
    await flushPromises()
    await wrapper.findAll('.section-tabs button')[4]!.trigger('click')

    expect(wrapper.text()).toContain('1 项已逾期')
    expect(wrapper.text()).toContain('仅在本页提醒')
    expect(wrapper.text()).not.toContain('已推送')
  })
})
