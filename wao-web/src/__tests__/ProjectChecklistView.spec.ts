import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { savePreference } from '@/api/favorites'
import { useUiStore } from '@/stores/ui'
import { projectWorkItems } from '@/utils/projectWorkItems'
import ProjectChecklistView from '@/components/task/ProjectChecklistView.vue'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('@/api/favorites', async original => ({ ...await original<object>(), fetchPreference: vi.fn().mockResolvedValue(''), savePreference: vi.fn().mockResolvedValue(undefined) }))
enableAutoUnmount(afterEach)
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()); seedDataStore() })
function setup(error = false, realEditor = false) {
  const data = useDataStore()
  data.tasks = [{ ...data.tasks[0]!, id: 'parent', title: '父任务', desc: '父正文', status: '待办', subs: [
    { id: 'child', text: '子任务', desc: '子正文', done: false },
  ] }]
  if (error) {
    vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    vi.spyOn(data, 'taskSaveError').mockReturnValue('服务器已有新版本')
  }
  const projection = projectWorkItems(data.tasks)
  const wrapper = mount(ProjectChecklistView, {
    props: { items: projection.items, contextItems: projection.items, itemRefs: projection.refs, showDone: false, collapsed: [], query: '' },
    global: { stubs: { RouterLink: { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }, TaskDocuments: true, CellMenu: { template: '<div><slot /></div>' }, MarkdownEditor: realEditor ? false : { name: 'MarkdownEditor', props: ['modelValue', 'bare'], template: '<div>{{ modelValue }}</div>' } } },
  })
  return { data, wrapper }
}
describe('项目清单详情', () => {
  async function clickBodyButton(label: string) {
    const button = [...document.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === label)
    expect(button).toBeTruthy()
    button!.click()
    await flushPromises()
  }

  it('右键删除目标任务，取消不写入，失败可重试且不打开完整详情', async () => {
    const { data, wrapper } = setup()
    const remove = vi.spyOn(data, 'deleteTask').mockResolvedValueOnce(false).mockResolvedValueOnce(true)
    const open = vi.spyOn(useUiStore(), 'openTask')
    await wrapper.get('[data-task-id="parent"]').trigger('contextmenu')
    await clickBodyButton('删除任务')
    expect(remove).not.toHaveBeenCalled()
    await clickBodyButton('取消')
    expect(remove).not.toHaveBeenCalled()
    await wrapper.get('[data-task-id="parent"] .task-select').trigger('keydown', { key: 'F10', shiftKey: true })
    await clickBodyButton('删除任务')
    await clickBodyButton('确认删除')
    expect(document.body.textContent).toContain('删除失败，任务已保留')
    await clickBodyButton('重试删除')
    expect(remove).toHaveBeenNthCalledWith(1, 'parent')
    expect(remove).toHaveBeenNthCalledWith(2, 'parent')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(open).not.toHaveBeenCalled()
  })

  it('详情已完成子任务可右键删除，保留其他子项，保存失败后按原始 ID 重试', async () => {
    const { data, wrapper } = setup()
    data.tasks[0]!.subs[0]!.done = true
    data.tasks[0]!.subs.push({ id: 'nested', parentId: 'child', text: '保留的下级', done: false })
    const sync = async () => {
      const p = projectWorkItems(data.tasks)
      await wrapper.setProps({ items: p.items, contextItems: p.items, itemRefs: p.refs })
    }
    await sync()
    const remove = vi.spyOn(data, 'removeSub')
    const state = vi.spyOn(data, 'taskSaveState').mockReturnValue('saved')
    const flush = vi.spyOn(data, 'flushTask').mockImplementationOnce(async () => { state.mockReturnValue('error') }).mockImplementationOnce(async () => { state.mockReturnValue('saved') })
    await wrapper.get('.detail-subtask input').trigger('contextmenu')
    await clickBodyButton('删除子任务')
    await clickBodyButton('确认删除')
    expect(remove).toHaveBeenCalledWith('parent', 'child')
    expect(data.tasks[0]!.subs.map(sub => sub.id)).toEqual(['nested'])
    expect(data.tasks[0]!.subs[0]!.parentId).toBeNull()
    expect(document.body.textContent).toContain('子任务删除尚未保存')
    await sync()
    await clickBodyButton('重试删除')
    expect(remove).toHaveBeenCalledTimes(1)
    expect(flush).toHaveBeenNthCalledWith(2, 'parent')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })

  it('删除处理中不重复提交，切换成员清除确认框', async () => {
    const { data, wrapper } = setup()
    let finish!: (value: boolean) => void
    const remove = vi.spyOn(data, 'deleteTask').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await wrapper.get('[data-task-id="parent"]').trigger('contextmenu')
    await clickBodyButton('删除任务')
    await clickBodyButton('确认删除')
    const confirm = [...document.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === '确认删除')!
    expect(confirm.disabled).toBe(true)
    confirm.click()
    expect(remove).toHaveBeenCalledTimes(1)
    useSessionStore().accept({ accountId: 'test', member: { id: 'another-member', name: '乙', initial: '乙', role: '', color: '' }, identities: [], createdAt: '' })
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    finish(true)
    await flushPromises()
  })

  it('删除前取消该任务尚未提交的完成计时器', async () => {
    vi.useFakeTimers()
    const { data, wrapper } = setup()
    const status = vi.spyOn(data, 'patchTask')
    vi.spyOn(data, 'deleteTask').mockResolvedValue(true)
    try {
      await wrapper.get('[data-task-id="parent"] input[type=checkbox]').setValue(true)
      await wrapper.get('[data-task-id="parent"]').trigger('contextmenu')
      await clickBodyButton('删除任务')
      await clickBodyButton('确认删除')
      await vi.advanceTimersByTimeAsync(3500)
      expect(status).not.toHaveBeenCalled()
    } finally { wrapper.unmount(); vi.useRealTimers() }
  })

  it('完成保存及失败时只保留本次完成的行，重试成功后不再随正文保存出现', async () => {
    vi.useFakeTimers()
    const { data, wrapper } = setup()
    data.tasks[0]!.subs.push({ id: 'old', text: '早已完成', done: true })
    let phase: 'saved' | 'saving' | 'error' = 'saved'
    vi.spyOn(data, 'taskSaveState').mockImplementation(() => phase)
    const sync = async () => {
      const p = projectWorkItems(data.tasks)
      await wrapper.setProps({ items: p.items, contextItems: p.items, itemRefs: p.refs })
    }
    let finish!: () => void
    vi.spyOn(data, 'flushTask').mockResolvedValueOnce().mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    vi.spyOn(data, 'setSubState').mockImplementation(() => {
      data.tasks[0]!.subs[0]!.done = true
      phase = 'saving'
    })
    try {
      await sync()
      await wrapper.get('[aria-label="完成 子任务"]').setValue(true)
      await vi.advanceTimersByTimeAsync(3000); await sync()
      expect(wrapper.find('[data-task-id="sub:parent:child"]').exists()).toBe(true)
      expect(wrapper.find('[data-task-id="sub:parent:old"]').exists()).toBe(false)
      phase = 'error'; finish(); await flushPromises(); await sync()
      expect(wrapper.find('[data-task-id="sub:parent:child"]').exists()).toBe(true)
      expect(wrapper.find('[data-task-id="sub:parent:old"]').exists()).toBe(false)
      phase = 'saved'; await sync(); await flushPromises()
      expect(wrapper.find('[data-task-id="sub:parent:child"]').exists()).toBe(false)
      phase = 'saving'; await sync()
      expect(wrapper.find('[data-task-id="sub:parent:child"]').exists()).toBe(false)
    } finally { wrapper.unmount(); vi.useRealTimers() }
  })

  it('编辑父子任务正文时，共享保存状态不显示已完成的子孙任务', async () => {
    const { data, wrapper } = setup()
    data.tasks[0]!.subs.push(
      { id: 'done', text: '已完成子任务', done: true },
      { id: 'grandchild', parentId: 'child', text: '已完成孙任务', done: true },
    )
    const state = vi.spyOn(data, 'taskSaveState').mockReturnValue('saved')
    const sync = async () => {
      const p = projectWorkItems(data.tasks)
      await wrapper.setProps({ items: p.items, contextItems: p.items, itemRefs: p.refs })
    }
    await sync(); await flushPromises()
    for (const id of ['parent', 'sub:parent:child']) {
      await wrapper.get(`[data-task-id="${id}"] .task-select`).trigger('click')
      wrapper.findComponent({ name: 'MarkdownEditor' }).vm.$emit('update:modelValue', '修改正文')
      for (const phase of ['saving', 'error', 'saving', 'saved'] as const) {
        state.mockReturnValue(phase)
        await sync()
        expect(wrapper.find('[data-task-id="sub:parent:done"]').exists()).toBe(false)
        expect(wrapper.find('[data-task-id="sub:parent:grandchild"]').exists()).toBe(false)
      }
    }
    await wrapper.setProps({ showDone: true })
    expect(wrapper.find('[data-task-id="sub:parent:done"]').exists()).toBe(true)
    expect(wrapper.find('[data-task-id="sub:parent:grandchild"]').exists()).toBe(true)
  })

  it('任务复选框右键打开功能菜单，不切换完成状态', async () => {
    const { wrapper, data } = setup()
    const checkbox = wrapper.get('[data-task-id="parent"] input[type=checkbox]')
    const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
    checkbox.element.dispatchEvent(event)
    await flushPromises()
    expect(event.defaultPrevented).toBe(true)
    expect(document.body.textContent).toContain('添加子任务')
    expect(data.tasks[0]!.status).toBe('待办')
  })

  it('专注入口使用独立关联参数，避免触发全局任务抽屉', async () => {
    const { wrapper } = setup()
    await wrapper.get('[data-task-id="parent"] .task-select').trigger('click')
    const link = wrapper.findComponent({ name: 'RouterLink' })
    expect(link.props('to')).toEqual({ path: '/focus', query: { focusTask: 'parent', focusSub: undefined } })
    await wrapper.get('[data-task-id="sub:parent:child"] .task-select').trigger('click')
    expect(link.props('to')).toEqual({ path: '/focus', query: { focusTask: 'parent', focusSub: 'child' } })
  })
  it('右键父任务添加子任务，键盘及详情子项可继续添加下级并展开父级', async () => {
    const { data, wrapper } = setup()
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue()
    vi.spyOn(data, 'taskSaveState').mockReturnValue('saved')
    const add = vi.spyOn(data, 'addSub')
    await wrapper.setProps({ collapsed: ['parent'] })
    await wrapper.get('[data-task-id="parent"]').trigger('contextmenu', { clientX: 100, clientY: 100 })
    await flushPromises()
    const chooseAdd = async () => {
      const button = Array.from(document.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')).find(b => b.textContent === '添加子任务')!
      expect(button).toBeTruthy()
      button.click(); await flushPromises()
    }
    await chooseAdd()
    await wrapper.get('[aria-label="子任务名称"]').setValue('新子任务')
    await wrapper.get('.child-composer').trigger('submit')
    await flushPromises()
    expect(add).toHaveBeenCalledWith('parent', '新子任务', undefined)
    expect(flush).toHaveBeenCalledWith('parent')
    expect(wrapper.emitted('toggle')).toContainEqual(['parent'])
    expect(data.tasks[0]!.subs.some(sub => sub.text === '新子任务')).toBe(true)
    await wrapper.get('.detail-subtask .child-title').trigger('keydown', { key:'F10', shiftKey:true })
    await chooseAdd()
    await wrapper.get('[aria-label="子任务名称"]').setValue('再下一层')
    await wrapper.get('.child-composer').trigger('submit'); await flushPromises()
    expect(add).toHaveBeenLastCalledWith('parent', '再下一层', 'child')
    expect(data.tasks[0]!.subs.find(sub => sub.text === '再下一层')?.parentId).toBe('child')
  })

  it('添加输入草稿按父级恢复，空白不提交，失败保留新增项且不重复创建', async () => {
    const { data, wrapper } = setup()
    const add = vi.spyOn(data, 'addSub')
    vi.spyOn(data, 'flushTask').mockRejectedValue(new Error('离线'))
    await wrapper.get('.add-child-button').trigger('click'); await flushPromises()
    await wrapper.get('[aria-label="子任务名称"]').setValue('未完成输入')
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    await wrapper.get('.add-child-button').trigger('click'); await flushPromises()
    expect((wrapper.get('[aria-label="子任务名称"]').element as HTMLInputElement).value).toBe('')
    await wrapper.findAll('.task-select')[0]!.trigger('click')
    await wrapper.get('.add-child-button').trigger('click'); await flushPromises()
    expect((wrapper.get('[aria-label="子任务名称"]').element as HTMLInputElement).value).toBe('未完成输入')
    await wrapper.get('[aria-label="子任务名称"]').setValue('  ')
    await wrapper.get('.child-composer').trigger('submit')
    expect(add).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="子任务名称"]').setValue('保留的子任务')
    await wrapper.get('.child-composer').trigger('submit'); await flushPromises()
    await wrapper.get('.child-composer').trigger('submit'); await flushPromises()
    expect(add).toHaveBeenCalledTimes(1)
    expect(data.tasks[0]!.subs.some(sub => sub.text === '保留的子任务')).toBe(true)
  })

  it('真实清单编辑器支持斜线、标题层级及随编辑更新的目录跳转', async () => {
    const { data, wrapper } = setup(false, true)
    await vi.dynamicImportSettled(); await flushPromises()
    const component = wrapper.findComponent((await import('@/components/ui/MarkdownEditor.vue')).default)
    const editor = (component.vm as unknown as { editor: import('@tiptap/core').Editor }).editor
    vi.spyOn(editor.view, 'coordsAtPos').mockReturnValue({ top: 100, bottom: 120, left: 100, right: 100 })
    editor.commands.setContent('<p></p>')
    editor.commands.insertContent('/h1')
    await flushPromises()
    expect(wrapper.find('.slash-menu').exists() || document.querySelector('.slash-menu') !== null).toBe(true)
    await wrapper.get('.tiptap').trigger('keydown', { key: 'Enter' })
    editor.commands.insertContent('一级验收')
    await flushPromises()
    expect(wrapper.get('.tiptap h1').text()).toBe('一级验收')
    expect(data.tasks[0]!.desc).toContain('# 一级验收')
    editor.commands.setContent('<h1>重复标题</h1><h2>重复标题</h2>')
    await flushPromises()
    await wrapper.get('[aria-label="任务描述目录"]').trigger('mouseenter')
    const links = wrapper.findAll('nav[aria-label="任务描述目录"] button')
    expect(links.map(link => link.text())).toEqual(['重复标题', '重复标题'])
    const target = wrapper.get('.tiptap h2').element as HTMLElement
    target.scrollIntoView = vi.fn()
    await links[1]!.trigger('click')
    expect(target.scrollIntoView).toHaveBeenCalled()
    expect(wrapper.find('nav[aria-label="任务描述目录"]').exists()).toBe(false)
    editor.commands.setContent('<p>没有标题</p>')
    await flushPromises()
    expect(wrapper.find('[aria-label="任务描述目录"]').exists()).toBe(false)
  }, 30000)

  it('右侧标题实时同步列表，子事项编辑正确落点，空标题不覆盖内容', async () => {
    const { data, wrapper } = setup()
    vi.spyOn(data, 'flushTask').mockResolvedValue()
    const patch = vi.spyOn(data, 'patchTask').mockImplementation((id, values) => Object.assign(data.taskById(id)!, values))
    const update = vi.spyOn(data, 'updateSub').mockImplementation((id, child, values) => Object.assign(data.subById(id, child)!, values))
    await wrapper.get('[aria-label="任务标题"]').setValue('新的父标题')
    const sync = async () => { const p = projectWorkItems(data.tasks); await wrapper.setProps({ items:p.items, contextItems:p.items, itemRefs:p.refs }) }
    await sync()
    expect(wrapper.findAll('.task-select')[0]!.text()).toContain('新的父标题')
    expect(patch).toHaveBeenCalledWith('parent', { title:'新的父标题' })
    await wrapper.get('[aria-label="任务标题"]').setValue(' ')
    await wrapper.get('[aria-label="任务标题"]').trigger('blur')
    expect(data.tasks[0]!.title).toBe('新的父标题')
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    await wrapper.get('[aria-label="任务标题"]').setValue('新的子标题')
    await sync()
    expect(update).toHaveBeenCalledWith('parent', 'child', { text:'新的子标题' })
    expect(wrapper.findAll('.task-select')[1]!.text()).toContain('新的子标题')
    expect(data.tasks[0]!.title).toBe('新的父标题')
  })
  it('离开清单串行提交同源子事项，等待期间正文保存不阻止取消', async () => {
    const { data, wrapper } = setup()
    data.tasks[0]!.subs.push({ id:'second', text:'第二子任务', done:false })
    const projection = projectWorkItems(data.tasks)
    await wrapper.setProps({ items:projection.items, contextItems:projection.items, itemRefs:projection.refs })
    let saving = false
    vi.spyOn(data, 'taskSaveState').mockImplementation(() => saving ? 'saving' : 'saved')
    vi.spyOn(data, 'flushTask').mockImplementation(async () => { await Promise.resolve(); saving = false })
    const state = vi.spyOn(data, 'setSubState').mockImplementation(() => { saving = true })
    await wrapper.get('[aria-label="完成 子任务"]').setValue(true)
    saving = true
    await wrapper.setProps({ query:'' })
    expect(wrapper.get('[aria-label="完成 子任务"]').attributes('disabled')).toBeUndefined()
    saving = false
    await wrapper.get('[aria-label="完成 第二子任务"]').setValue(true)
    wrapper.unmount(); await flushPromises()
    expect(state).toHaveBeenCalledWith('parent', 'child', '已完成')
    expect(state).toHaveBeenCalledWith('parent', 'second', '已完成')
  })
  it('完成保留三秒，可再次点击取消且不改原状态；到期才保存并隐藏', async () => {
    vi.useFakeTimers()
    const { data, wrapper } = setup()
    data.tasks[0]!.subs = []
    const sync = async () => {
      const projection = projectWorkItems(data.tasks)
      await wrapper.setProps({ items:projection.items, contextItems:projection.items, itemRefs:projection.refs })
    }
    await sync()
    vi.spyOn(data, 'flushTask').mockResolvedValue()
    const patch = vi.spyOn(data, 'patchTask').mockImplementation((id, values) => { Object.assign(data.taskById(id)!, values) })
    try {
      await wrapper.get('[aria-label="完成 父任务"]').setValue(true)
      await vi.advanceTimersByTimeAsync(2000)
      expect(wrapper.find('.checklist-row').exists()).toBe(true)
      expect(patch).not.toHaveBeenCalled()
      await wrapper.get('[aria-label="完成 父任务"]').setValue(false)
      await vi.advanceTimersByTimeAsync(1500)
      expect(patch).not.toHaveBeenCalled()
      expect(data.tasks[0]!.status).toBe('待办')
      await wrapper.get('[aria-label="完成 父任务"]').setValue(true)
      await vi.advanceTimersByTimeAsync(2999)
      expect(patch).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(1); await sync()
      expect(patch).toHaveBeenCalledWith('parent', { status:'已完成' })
      expect(wrapper.find('.checklist-row').exists()).toBe(false)
      await wrapper.setProps({ showDone:true })
      expect(wrapper.find('.checklist-row').exists()).toBe(true)
    } finally { wrapper.unmount(); vi.useRealTimers() }
  })
  it('宽度键盘调整保存到成员偏好，重新挂载恢复，切换成员隔离', async () => {
    const session = useSessionStore()
    session.accept({ accountId: 'test', member: { id: 'one', name: '甲', initial: '甲', role: '', color: '' }, identities: [], createdAt: '' })
    const { wrapper } = setup()
    await flushPromises()
    const divider = wrapper.get('[role=separator]')
    await divider.trigger('keydown', { key: 'ArrowRight' }); await flushPromises()
    expect(savePreference).toHaveBeenCalledWith('project-checklist-split-v1', expect.any(String))
    const saved = [...vi.mocked(savePreference).mock.calls].reverse().find(call => call[0] === 'project-checklist-split-v1')
    expect(Number(saved?.[1])).toBeCloseTo(.415)
    expect(divider.attributes('aria-valuenow')).toBe('42')
    wrapper.unmount()
    const restored = setup().wrapper
    await flushPromises()
    expect(restored.get('[role=separator]').attributes('aria-valuenow')).toBe('42')
    session.accept({ accountId: 'test', member: { id: 'two', name: '乙', initial: '乙', role: '', color: '' }, identities: [], createdAt: '' })
    await flushPromises()
    expect(restored.get('[role=separator]').attributes('aria-valuenow')).toBe('40')
    await restored.get('[role=separator]').trigger('keydown', { key: 'End' }); await flushPromises()
    expect(Number(restored.get('[role=separator]').attributes('aria-valuenow'))).toBeLessThanOrEqual(75)
  })
  it('旗标优先级保存到当前任务，详情底部显示直属子项并可切换', async () => {
    const { data, wrapper } = setup()
    const patch = vi.spyOn(data, 'patchTask').mockImplementation(() => undefined)
    const update = vi.spyOn(data, 'updateSub').mockImplementation(() => undefined)
    vi.spyOn(data, 'flushTask').mockResolvedValue(undefined)
    await wrapper.get('[aria-label="任务优先级"]').trigger('click')
    await wrapper.findAll('[role=menuitemradio]').find(b => b.text().includes('P0'))!.trigger('click')
    expect(patch).toHaveBeenCalledWith('parent', { pr: 'P0' })
    expect(wrapper.get('[aria-label="直属子任务"]').text()).toContain('子任务')
    await wrapper.setProps({ collapsed: ['parent'] })
    await wrapper.get('.child-title').trigger('click')
    expect((wrapper.get('[aria-label="任务标题"]').element as HTMLInputElement).value).toBe('子任务')
    expect(wrapper.emitted('toggle')).toContainEqual(['parent'])
    await wrapper.setProps({ collapsed:[] })
    expect(wrapper.get('.checklist-row.selected').text()).toContain('子任务')
    expect(wrapper.get('.parent-task-link').text()).toContain('父任务')
    expect(wrapper.find('[aria-label="直属子任务"]').exists()).toBe(false)
    await wrapper.get('[aria-label="任务优先级"]').trigger('click')
    await wrapper.findAll('[role=menuitemradio]').find(b => b.text().includes('P1'))!.trigger('click')
    expect(update).toHaveBeenCalledWith('parent', 'child', { pr: 'P1' })
    await wrapper.setProps({ collapsed: ['parent'], items: [...wrapper.props('items')] })
    expect((wrapper.get('[aria-label="任务标题"]').element as HTMLInputElement).value).toBe('子任务')
    await wrapper.get('.parent-task-link').trigger('click')
    expect((wrapper.get('[aria-label="任务标题"]').element as HTMLInputElement).value).toBe('父任务')
  })
  it('顶部切换分组和状态，子事项沿用父分组且只提供支持的状态', async () => {
    const { data, wrapper } = setup()
    data.projectById(data.tasks[0]!.project)!.taskGroups = [{ id: 'g1', name: '工作' }, { id: 'g2', name: '生活' }]
    const move = vi.spyOn(data, 'moveTaskToGroup').mockImplementation(() => undefined)
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue(undefined)
    const patch = vi.spyOn(data, 'patchTask').mockImplementation(() => undefined)
    await flushPromises()
    await wrapper.get('[aria-label="任务分组"]').trigger('click')
    await wrapper.findAll('[role=menuitemradio]').find(b => b.text() === '生活')!.trigger('click')
    expect(move).toHaveBeenCalledWith('parent', 'g2')
    await wrapper.get('[aria-label="任务状态"]').trigger('click')
    await wrapper.findAll('[role=menuitemradio]').find(b => b.text() === '进行中')!.trigger('click')
    expect(patch).toHaveBeenCalledWith('parent', { status: '进行中' })
    expect(flush).toHaveBeenCalledWith('parent')
    expect(wrapper.get('[aria-label="任务状态"]').text()).not.toContain('已完成')
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    expect(wrapper.get('[aria-label="任务分组"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="任务状态"]').trigger('click')
    expect(wrapper.findAll('[role=menuitemradio]')).toHaveLength(3)
  })
  it('点击子任务只切换右侧卡片，Markdown 写入真实子事项而非父正文', async () => {
    const { data, wrapper } = setup()
    const update = vi.spyOn(data, 'updateSub').mockImplementation(() => undefined)
    const patch = vi.spyOn(data, 'patchTask').mockImplementation(() => undefined)
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    await flushPromises()
    expect((wrapper.get('[aria-label="任务标题"]').element as HTMLInputElement).value).toBe('子任务')
    expect(useUiStore().openTaskId).toBeFalsy()
    wrapper.findComponent({ name: 'MarkdownEditor' }).vm.$emit('update:modelValue', '## 子任务记录')
    await flushPromises()
    expect(update).toHaveBeenCalledWith('parent', 'child', { desc: '## 子任务记录' })
    expect(patch).not.toHaveBeenCalled()
    await wrapper.findAll('.task-select')[0]!.trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
  it('查找子任务保留父级，折叠和筛选移除当前选择后不会展示旧详情', async () => {
    const { wrapper } = setup()
    await wrapper.setProps({ query: '子正文', collapsed: ['parent'] })
    expect(wrapper.findAll('.task-select')).toHaveLength(2)
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    await wrapper.setProps({ query: '', collapsed: ['parent'] })
    expect(wrapper.findAll('.task-select')).toHaveLength(1)
    expect((wrapper.get('[aria-label="任务标题"]').element as HTMLInputElement).value).toBe('父任务')
    await wrapper.setProps({ query: '不存在' })
    expect(wrapper.find('h2').exists()).toBe(false)
    expect(wrapper.text()).toContain('没有匹配的任务')
  })
  it('保存错误保留内容并提供原任务冲突处理入口', async () => {
    const { wrapper } = setup(true)
    await wrapper.setProps({ query: '父' })
    await flushPromises()
    expect(wrapper.text()).toContain('服务器已有新版本')
    await wrapper.findAll('button').find(b => b.text() === '查看保存问题')!.trigger('click')
    expect(useUiStore().openTaskId).toBe('parent')
  })
})
