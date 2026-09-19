import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'

describe('data store · 项目知识库', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('新建项目不再自动生成关联知识库', async () => {
    const data = useDataStore()
    const before = data.kbs.length

    const project = await data.addProject({
      name: '只管理任务的项目',
      area: 'career',
      goal: '验证默认知识库已取消',
      visAll: false,
    })

    expect(data.kbs).toHaveLength(before)
    expect(data.kbs.some((kb) => kb.project === project.id)).toBe(false)
  })
  it('历史项目知识库创建文档不继承项目，删除项目不移除知识库', async () => {
    const data = useDataStore()
    const kb = await data.addKb({ name: '独立资料', project: 'p-old' })
    expect(kb!.project).toBeNull()
    kb!.project = 'p-old'
    const id = await data.addDoc(kb!.id, kb!.folders[0]!.id)
    expect(data.docById(id!)!.links).toEqual([])
    await data.deleteProject('p-old')
    expect(data.kbById(kb!.id)).toBeDefined()
    expect(data.docById(id!)).toBeDefined()
  })

})
