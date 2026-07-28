import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUiStore } from '@/stores/ui'

/**
 * 任务视图偏好：项目页的列表 / 看板切换要被记住。
 *
 * 用户日常看的是看板，但每次打开都从列表开始，等于每次都要多点一次。
 */
describe('ui store · 任务视图记忆', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('没有任何记录时默认用看板，而不是列表', () => {
    expect(useUiStore().taskViewOf('p1')).toBe('board')
  })

  it('切换后写进本地存储，下次进来还是这个视图', () => {
    useUiStore().setTaskView('p1', 'list')
    expect(localStorage.getItem('wao-task-view-v1')).toBe('{"p1":"list"}')

    // 模拟重开页面：重新建一个 store 实例
    setActivePinia(createPinia())
    expect(useUiStore().taskViewOf('p1')).toBe('list')
  })

  it('本地存的是垃圾值时回落到看板，不至于把页面卡在空视图', () => {
    localStorage.setItem('wao-task-view-v1', 'gantt')
    setActivePinia(createPinia())

    expect(useUiStore().taskViewOf('p1')).toBe('board')
  })

  it('各项目分别记住自己的视图，不会彼此带着切换', () => {
    const ui = useUiStore()
    ui.setTaskView('p1', 'group')
    ui.setTaskView('p2', 'list')

    expect(ui.taskViewOf('p1')).toBe('group')
    expect(ui.taskViewOf('p2')).toBe('list')
  })
})
