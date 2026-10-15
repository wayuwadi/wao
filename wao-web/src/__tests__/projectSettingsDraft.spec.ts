import { beforeEach, expect, it } from 'vitest'
import { readProjectSettingsDraft, writeProjectSettingsDraft, type ProjectSettingsDraft } from '@/utils/projectSettingsDraft'
beforeEach(() => localStorage.clear())
const draft: ProjectSettingsDraft = { base: { name: '原名', goal: '', icon: '', due: '', area: 'career', members: ['m1'] }, value: { name: '改名', goal: '', icon: '', due: '', area: 'career', members: ['m1'] }, revision: 'first' }
it('隔离成员和项目，旧保存不删除新窗口的草稿', () => {
  writeProjectSettingsDraft('m1', 'p1', draft)
  expect(readProjectSettingsDraft('m2', 'p1')).toBeNull()
  expect(readProjectSettingsDraft('m1', 'p2')).toBeNull()
  writeProjectSettingsDraft('m1', 'p1', { ...draft, revision: 'second' })
  writeProjectSettingsDraft('m1', 'p1', null, 'first')
  expect(readProjectSettingsDraft('m1', 'p1')?.revision).toBe('second')
  writeProjectSettingsDraft('m1', 'p1', null, 'second')
  expect(readProjectSettingsDraft('m1', 'p1')).toBeNull()
})
it('损坏和非完整草稿不进入表单', () => {
  localStorage.setItem('wao-project-settings-draft-v1:m1:p1', '{invalid')
  expect(readProjectSettingsDraft('m1', 'p1')).toBeNull()
  localStorage.setItem('wao-project-settings-draft-v1:m1:p1', JSON.stringify({ ...draft, value: { name: '不完整' } }))
  expect(readProjectSettingsDraft('m1', 'p1')).toBeNull()
})
