import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NoteOriginalReader from '@/components/notes/NoteOriginalReader.vue'
import { fetchNoteOriginals, type NoteOriginals, type NoteSourceSnapshot } from '@/api/noteOriginals'
vi.mock('@/api/noteOriginals', () => ({ fetchNoteOriginals: vi.fn() }))
const snapshot = (id: string, markdown: string, sourceType = 'BEFORE_AI'): NoteSourceSnapshot => ({ id, title: '原始标题', markdown, document: null, blocks: [], links: [], sourceType, sourceRev: 1, createdAt: '2026-10-03' })
const data = (): NoteOriginals => ({ before: snapshot('round', '本次输入'), initial: snapshot('first', '  最初记录\n\n', 'USER_INITIAL'), explanation: '最初记录在首次 AI 加工前留存', appliedRev: 2 })
const create = () => mount(NoteOriginalReader, { props: { docId: 'note', memberId: 'member', currentRev: 3 }, global: { stubs: { MarkdownEditor: { props: {modelValue:String,readonly:Boolean}, template: '<pre :data-readonly="readonly">{{ modelValue }}</pre>' } } } })
describe('按篇只读原文', () => {
 beforeEach(() => { vi.clearAllMocks(); vi.mocked(fetchNoteOriginals).mockResolvedValue(data()) })
 it('默认本次来源，切换最初记录只改变阅读状态', async () => {
  const w=create();await flushPromises();expect(fetchNoteOriginals).toHaveBeenCalledExactlyOnceWith('note');expect(w.text()).toContain('本次输入');expect(w.text()).toContain('该次润色之后有更新');
  await w.get('button[aria-pressed="false"]').trigger('click');expect(w.get('pre').attributes('data-readonly')).toBe('true');expect(w.get('pre').text()).toBe('最初记录');expect(fetchNoteOriginals).toHaveBeenCalledTimes(1);w.unmount()
 })
 it('历史留存与首次留存不冒充最初记录，读取失败可重试', async () => {
  vi.mocked(fetchNoteOriginals).mockRejectedValueOnce(new Error('读取失败'));
  const w=create();await flushPromises();expect(w.get('[role=alert]').text()).toContain('读取失败');
  vi.mocked(fetchNoteOriginals).mockResolvedValue({ ...data(), before: snapshot('legacy', '历史来源', 'LEGACY_BEFORE_AI'), initial: snapshot('retained', '首次留存', 'BEFORE_AI') });
  await w.get('button').trigger('click');await flushPromises();expect(w.text()).toContain('历史留存的润色前内容');expect(w.text()).toContain('首次留存内容');expect(w.text()).not.toContain('>最初记录<');w.unmount()
 })
 it('切换成员阻止迟到响应，也不以当前正文填补缺失来源', async () => {
  let resolve!: (value: NoteOriginals) => void;
  vi.mocked(fetchNoteOriginals).mockImplementationOnce(() => new Promise(done => {resolve=done}));
  const w=create();await w.setProps({memberId:'second'});await flushPromises();
  vi.mocked(fetchNoteOriginals).mockResolvedValue({before:null,initial:null,explanation:'无法恢复',appliedRev:null});await w.setProps({memberId:'third'});await flushPromises();resolve(data());await flushPromises();
  expect(w.text()).toContain('没有可用原文');expect(w.text()).not.toContain('最初记录');w.unmount()
 })
})
