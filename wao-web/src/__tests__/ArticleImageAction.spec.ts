import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArticleImageAction from '@/components/ui/ArticleImageAction.vue'
import { freezeNoteAiInput, retainNoteAiResult } from '@/api/noteOriginals'
import { startArticleImage, fetchArticleImage } from '@/api/articleImages'
vi.mock('@/api/noteOriginals', () => ({ freezeNoteAiInput: vi.fn(), retainNoteAiResult: vi.fn() }))
vi.mock('@/api/articleImages', () => ({ startArticleImage: vi.fn(), fetchArticleImage: vi.fn() }))
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn().mockResolvedValue(''), savePreference: vi.fn() }))
vi.mock('@/api/codexModels', () => ({ fetchCodexModels: vi.fn().mockResolvedValue({ models: [], stale: false, message: '' }) }))
beforeEach(() => {
 localStorage.clear(); vi.clearAllMocks()
 vi.mocked(freezeNoteAiInput).mockResolvedValue({ snapshotId: 'snapshot', overviewImageUrl: null })
 vi.mocked(retainNoteAiResult).mockResolvedValue()
 vi.mocked(startArticleImage).mockResolvedValue({ id: 'job', status: 'QUEUED', message: '', imageUrl: null })
 vi.mocked(fetchArticleImage).mockResolvedValue({ id: 'job', status: 'SUCCEEDED', message: '', imageUrl: '/api/images/generated/raw' })
})
function editor() {
 let markdown = '原始输入'
 const insertImage = vi.fn().mockReturnValue(true)
 const wrapper = mount(ArticleImageAction, { props: { scopeKey: 'note:first', getText: () => markdown, insertImage,
  noteAiOrigin: { stableId: 'first', title: '', blocks: [], links: [] },
  getSourceContent: () => ({ title: '', markdown, document: null, blocks: [], links: [] }),
 } })
 return { wrapper, insertImage, edit: (value: string) => { markdown = value } }
}
it('原稿冻结失败时不能提交模型或插入配图', async () => {
 vi.mocked(freezeNoteAiInput).mockRejectedValueOnce(new Error('来源保存失败'))
 const {wrapper,insertImage}=editor(); await flushPromises(); await wrapper.get('.generate-image').trigger('click'); await flushPromises()
 expect(startArticleImage).not.toHaveBeenCalled(); expect(insertImage).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('来源保存失败'); wrapper.unmount()
})
it('结果留存请求期间继续编辑，不能应用旧配图', async () => {
 let done!: () => void; vi.mocked(retainNoteAiResult).mockImplementationOnce(() => new Promise(resolve => { done=resolve }))
 const {wrapper,insertImage,edit}=editor(); await flushPromises(); await wrapper.get('.generate-image').trigger('click'); await flushPromises()
 expect(retainNoteAiResult).toHaveBeenCalledOnce(); edit('等待期间的新输入'); done(); await flushPromises()
 expect(insertImage).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('旧配图未应用'); wrapper.unmount()
})
it('冻结请求期间切换作用域，迟到响应不能在新账号或文档上提交模型', async () => {
 let done!: (value: {snapshotId: string; overviewImageUrl: null}) => void
 vi.mocked(freezeNoteAiInput).mockImplementationOnce(() => new Promise(resolve => {done=resolve}))
 const {wrapper,insertImage}=editor(); await flushPromises(); await wrapper.get('.generate-image').trigger('click'); await flushPromises()
 await wrapper.setProps({scopeKey:'note:second'}); done({snapshotId:'first',overviewImageUrl:null}); await flushPromises()
 expect(startArticleImage).not.toHaveBeenCalled(); expect(insertImage).not.toHaveBeenCalled(); wrapper.unmount()
})
