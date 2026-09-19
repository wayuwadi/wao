import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiSessionResult from '@/components/ai/AiSessionResult.vue'
import type { AiRun } from '@/types'
const run = { id: 'r1', status: 'succeeded', sessionId: 's1', runnerId: 'n1', resultSummary: '**已提交**，还需检查页面', reportUrl: '/reports/r1', progress: [] } as unknown as AiRun

describe('会话结果与下一步', () => {
  it('结果和报告直接可见，不把执行结束当用户验收，操作仅发出定位事件', async () => {
    const w = mount(AiSessionResult, { props: { run } })
    expect(w.get('h3').text()).toBe('本轮执行结束')
    expect(w.text()).toContain('本页没有用户验收记录')
    expect(w.get('.result-summary strong').text()).toBe('已提交')
    expect(w.get('a').attributes('href')).toBe('/reports/r1')
    await w.get('button').trigger('click')
    expect(w.emitted('continue')).toHaveLength(1)
    expect(w.emitted('newSession')).toBeUndefined()
  })
  it('失败材料缺失明确说明，不推断成果或重试，旧会话给出新建入口', async () => {
    const w = mount(AiSessionResult, { props: { run: { ...run, status: 'failed', sessionId: null, resultSummary: null, reportUrl: null, deliveryStage: 'not_required' } } })
    expect(w.get('h3').text()).toBe('本轮执行遇到问题')
    expect(w.text()).toContain('记录未提供结构化失败原因')
    expect(w.text()).toContain('无法据此确认哪些工作已完成')
    expect(w.text()).toContain('本页不能据此确认')
    expect(w.find('a').exists()).toBe(false)
    await w.get('button').trigger('click')
    expect(w.emitted('newSession')).toHaveLength(1)
  })
  it('真实故障名称与待接收状态优先展示，禁止重复续聊', () => {
    const w = mount(AiSessionResult, { props: { run: { ...run, status: 'failed', failureLabel: '执行节点离线', continuationPending: true } } })
    expect(w.text()).toContain('执行节点离线')
    expect(w.text()).toContain('正在等待原执行电脑接收')
    expect(w.get('button').attributes('disabled')).toBeDefined()
  })
  it('正在执行时不展示旧结果为当前终态', () => {
    const w = mount(AiSessionResult, { props: { run: { ...run, status: 'running' } } })
    expect(w.find('section').exists()).toBe(false)
  })
  it('把明确的账号模型不兼容错误解释为可操作原因，原始技术记录默认折叠', () => {
    const w = mount(AiSessionResult, { props: { run: { ...run, status: 'failed', resultSummary: "The 'example-model' model is not supported when using Codex with a ChatGPT account." } } })
    expect(w.text()).toContain('执行账号不支持所选模型')
    expect(w.text()).toContain('仅重发要求可能再次失败')
    expect(w.get('details').attributes('open')).toBeUndefined()
    expect(w.findAll('a').some(a => a.attributes('href') === '/settings?tab=ai-accounts')).toBe(true)
  })

})
