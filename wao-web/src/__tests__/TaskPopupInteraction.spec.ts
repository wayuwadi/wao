import { afterEach, describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CellMenu from '@/components/task/CellMenu.vue'
import TaskFilterSelect from '@/components/task/TaskFilterSelect.vue'

afterEach(()=>{document.body.innerHTML=''})
describe('task popup interactions',()=>{
 it('属性菜单支持方向键跳过禁用项，Esc 关闭并还原触发焦点',async()=>{
  const anchor=document.createElement('button');document.body.append(anchor)
  const wrapper=mount(CellMenu,{props:{anchor},slots:{default:'<button>未开始</button><button disabled>不可选</button><button>进行中</button>'},attachTo:document.body})
  await flushPromises()
  const buttons=document.querySelectorAll<HTMLButtonElement>('.cell-menu button')
  expect(document.activeElement).toBe(buttons[0])
  buttons[0]!.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true}))
  expect(document.activeElement).toBe(buttons[2])
  buttons[2]!.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))
  expect(wrapper.emitted('close')).toHaveLength(1);expect(document.activeElement).toBe(anchor)
  wrapper.unmount()
 })
 it('象限多选点击外部收起，不丢选择；Esc 恢复焦点并阻止关闭外层',async()=>{
  const wrapper=mount(TaskFilterSelect,{props:{modelValue:['meeting'],label:'象限标签',options:[{value:'meeting',label:'会议'}]},attachTo:document.body})
  await wrapper.get('button').trigger('click')
  expect(wrapper.get('.filter-options label').classes()).toContain('selected')
  await wrapper.get('input').setValue(false)
  expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[]])
  document.body.dispatchEvent(new Event('pointerdown',{bubbles:true}));await flushPromises()
  expect(wrapper.find('.filter-options').exists()).toBe(false)
  await wrapper.get('button').trigger('click')
  await wrapper.get('input').trigger('keydown',{key:'Escape'})
  await flushPromises()
  expect(wrapper.find('.filter-options').exists()).toBe(false)
  expect(document.activeElement).toBe(wrapper.get('button').element)
  wrapper.unmount()
 })
})
