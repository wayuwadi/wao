import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiPromptEditor from '@/components/ui/AiPromptEditor.vue'
const props = {
  modelValue: '',
  defaultValue: '实际默认方法',
  label: '提示词',
  editorId: 'title',
  emptyUsesDefault: true,
}
describe('提示词默认内容与恢复保护', () => {
  it('默认内容只读，查看不会把默认文本写成自定义；明确基于默认才复制', async () => {
    const w = mount(AiPromptEditor, { props })
    expect(w.get<HTMLTextAreaElement>('textarea').element.value).toBe('实际默认方法')
    expect(w.get('textarea').attributes('readonly')).toBeDefined()
    expect(w.emitted('update:modelValue')).toBeUndefined()
    await w.get('button').trigger('click')
    expect(w.emitted('update:modelValue')).toEqual([['实际默认方法']])
  })
  it('恢复前比较当前与默认，可取消；确认才清空默认覆盖值', async () => {
    const w = mount(AiPromptEditor, { props: { ...props, modelValue: '我的方法' } })
    await w
      .findAll('button')
      .find((b) => b.text() === '恢复默认')!
      .trigger('click')
    expect(w.get('[aria-label="恢复默认前的内容对比"]').text()).toContain('我的方法')
    expect(w.emitted('update:modelValue')).toBeUndefined()
    await w
      .findAll('button')
      .find((b) => b.text() === '保留当前内容')!
      .trigger('click')
    expect(w.emitted('update:modelValue')).toBeUndefined()
    await w
      .findAll('button')
      .find((b) => b.text() === '恢复默认')!
      .trigger('click')
    await w
      .findAll('button')
      .find((b) => b.text() === '确认恢复默认')!
      .trigger('click')
    expect(w.emitted('update:modelValue')).toEqual([['']])
  })
  it('卦象恢复保留非空默认，切换编辑段关闭对比', async () => {
    const w = mount(AiPromptEditor, {
      props: { ...props, modelValue: '我的方法', emptyUsesDefault: false },
    })
    await w
      .findAll('button')
      .find((b) => b.text() === '恢复默认')!
      .trigger('click')
    await w.setProps({ editorId: 'image', defaultValue: '图片默认' })
    expect(w.find('[aria-label="恢复默认前的内容对比"]').exists()).toBe(false)
    await w
      .findAll('button')
      .find((b) => b.text() === '恢复默认')!
      .trigger('click')
    await w
      .findAll('button')
      .find((b) => b.text() === '确认恢复默认')!
      .trigger('click')
    expect(w.emitted('update:modelValue')).toEqual([['图片默认']])
  })
  it('旧服务缺少默认时不编造内容，不覆盖已有自定义输入', async () => {
    const w = mount(AiPromptEditor, {
      props: { ...props, defaultValue: '', modelValue: '本机草稿' },
    })
    expect(w.get<HTMLTextAreaElement>('textarea').element.value).toBe('本机草稿')
    expect(w.text()).toContain('当前服务未提供默认内容')
    expect(w.findAll('button').every((b) => b.attributes('disabled') !== undefined)).toBe(true)
  })
})
