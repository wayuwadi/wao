import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDeco } from '@/composables/deco'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

describe('任务展示装饰', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    seedDataStore()
  })

  it('任务标题使用主题文字 token，不被浅色主题的黑色内联值锁死', () => {
    document.documentElement.dataset.theme = 'dark'
    const data = useDataStore()
    const { decoTask } = useDeco()
    const task = data.tasks[0]!

    expect(decoTask({ ...task, status: '待办' }).titleColor).toBe('var(--wao-text)')
    expect(decoTask({ ...task, status: '已完成' }).titleColor).toBe('var(--wao-text-weak)')
  })
})
