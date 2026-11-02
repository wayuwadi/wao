import { beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { installKnowledgeResume } from '@/utils/knowledgeResume'
describe('知识库恢复上次文档', () => {
  beforeEach(() => localStorage.clear())
  function setup(member = () => 'account:member') {
    const router = createRouter({ history:createMemoryHistory(), routes:[{path:'/knowledge',component:{}},{path:'/kb/:id',component:{}},{path:'/notes',component:{}}] })
    installKnowledgeResume(router, member)
    return router
  }
  it('第一次进入首页，切走再点击入口及刷新后恢复上次文档', async () => {
    const router = setup()
    await router.push('/knowledge'); expect(router.currentRoute.value.fullPath).toBe('/knowledge')
    await router.push('/knowledge?kb=kb1&doc=doc1'); await router.push('/notes'); await router.push('/knowledge')
    expect(router.currentRoute.value.query).toEqual({kb:'kb1',doc:'doc1'})
    const restored = setup(); await restored.push('/knowledge')
    expect(restored.currentRoute.value.query).toEqual({kb:'kb1',doc:'doc1'})
  })
  it('支持旧文档链接；明确首页及其他文档链接不被覆盖', async () => {
    const router = setup(); await router.push('/kb/legacy?doc=old')
    await router.push('/knowledge?home=1'); expect(router.currentRoute.value.query).toEqual({home:'1'})
    await router.push('/knowledge'); expect(router.currentRoute.value.query).toEqual({kb:'legacy',doc:'old'})
    await router.push('/knowledge?kb=other&doc=new'); expect(router.currentRoute.value.query.doc).toBe('new')
  })
  it('账号隔离，损坏的记录不影响打开', async () => {
    let account='a'; const router = setup(()=>account)
    await router.push('/knowledge?kb=private&doc=one'); await router.push('/notes'); account='b'
    await router.push('/knowledge'); expect(router.currentRoute.value.query).toEqual({})
    await router.push('/notes'); localStorage.setItem('wao-knowledge-last-document-v1:b','{"kb":"https://bad.example","doc":"x"}')
    await router.push('/knowledge'); expect(router.currentRoute.value.query).toEqual({})
  })
})
