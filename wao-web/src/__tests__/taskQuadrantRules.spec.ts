import { describe, expect, it } from 'vitest'
import { decodeQuadrantRules, defaultQuadrantRules } from '@/utils/taskQuadrantRules'
describe('quadrant rule persistence', () => {
 it('keeps original priority mappings when preferences are absent or malformed', () => {
  for(const raw of [null,'broken','{}','[]']) expect(decodeQuadrantRules(raw).map(r=>r.priorities)).toEqual([['P0'],['P1'],['P2'],['P3']])
 })
 it('roundtrips independent custom filters and only repairs malformed entries', () => {
  const rules=defaultQuadrantRules(); rules[0]={...rules[0]!,name:'联调',custom:true,tags:['联调'],projects:['p1'],date:'tomorrow',priorities:[]}
  const decoded=decodeQuadrantRules(JSON.stringify(rules)); expect(decoded).toEqual(rules)
  expect(decodeQuadrantRules(JSON.stringify([rules[0],null,rules[2],rules[3]]))[0]).toEqual(rules[0])
  expect(decodeQuadrantRules(JSON.stringify([rules[0],null,rules[2],rules[3]]))[1]).toEqual(defaultQuadrantRules()[1])
 })
})
