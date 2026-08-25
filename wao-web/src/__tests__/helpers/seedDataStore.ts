import { useDataStore } from '@/stores/data'
import * as fixtures from '@/data/fixtures'

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

/**
 * 给需要完整领域上下文的单元测试显式注入原型样本。
 *
 * 生产 store 必须保持空初值；测试不能再借生产启动回退隐式获得这些数据。
 */
export function seedDataStore(data = useDataStore()) {
  data.areas = clone(fixtures.AREAS)
  data.projects = clone(fixtures.PROJECTS)
  data.tasks = clone(fixtures.TASKS)
  data.kbs = clone(fixtures.KBS)
  Object.keys(data.docs).forEach((id) => delete data.docs[id])
  Object.assign(data.docs, clone(fixtures.DOCS))
  data.autos = clone(fixtures.AUTOS)
  data.files = clone(fixtures.FILES)
  data.imgs = clone(fixtures.IMGS)
  data.vault = clone(fixtures.VAULT)
  data.schedule = clone(fixtures.SCHED)
  data.suggestions = clone(fixtures.SUGG)
  data.goals = clone(fixtures.GOALS)
  data.myGroups = [{ id: 'g1', name: '我的知识库' }]
  for (const domain of Object.keys(data.loadStates) as Array<keyof typeof data.loadStates>) {
    data.loadStates[domain] = 'loaded'
  }
  data.taskLoadState = 'loaded'
  data.fileLoadState = 'loaded'
  return data
}
