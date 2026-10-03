import { beforeEach, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ get: vi.fn(), mining: vi.fn(), doc: vi.fn() }))
vi.mock('@/api/http', () => ({ http: { get: mocks.get } }))
vi.mock('@/api/noteMining', () => ({ fetchNoteMining: mocks.mining }))
vi.mock('@/api/board', () => ({ fetchDoc: mocks.doc }))
import { discoveryHttpStatus, fetchDiscoveryPage, fetchDiscoverySource } from '@/api/noteDiscoveries'
beforeEach(() => vi.clearAllMocks())
it('使用真实请求设施包装后的 HTTP 状态，也支持原始 Axios 状态', () => { expect(discoveryHttpStatus({ status: 409 })).toBe(409); expect(discoveryHttpStatus({ response: { status: 404 } })).toBe(404) })
it('旧后端只读适配明确标注历史产物，不回退手动分析', async () => {
  mocks.get.mockRejectedValue({ status: 404 }); mocks.doc.mockResolvedValue({ id: 'synthetic-doc' })
  mocks.mining.mockResolvedValue({ job: { id: 'old' }, artifacts: [{ id: 'old-a', content: '合成旧产物', rationale: '合成原因', feedbackStatus: 'SAVED', sources: [{ docId: 'synthetic-doc', docRev: 1, title: '合成来源', excerpt: '合成摘录' }] }] })
  const result = await fetchDiscoveryPage('saved'); expect(result.nextCursor).toBeNull(); expect(result.items[0]).toMatchObject({ origin: 'LEGACY', readOnly: true, restricted: false })
  await fetchDiscoverySource('old-a', 'synthetic-doc'); expect(mocks.doc).toHaveBeenCalledWith('synthetic-doc'); expect(mocks.get).toHaveBeenCalledTimes(1)
})
it('旧后端失去来源权限时隐藏原文、结论与图片', async () => {
  mocks.get.mockRejectedValue({ status: 410 }); mocks.doc.mockRejectedValue({ status: 404 })
  mocks.mining.mockResolvedValue({ job: { id: 'old' }, artifacts: [{ id: 'restricted-a', content: '不能泄露', rationale: '不能泄露', feedbackStatus: 'SAVED', sources: [{ docId: 'removed', docRev: 1, title: '私有', excerpt: '不能泄露' }] }] })
  expect((await fetchDiscoveryPage('saved')).items[0]).toMatchObject({ restricted: true, evidence: [], observation: '', valueReason: '' })
})
it('读取失败不会当作旧服务或空结果', async () => { mocks.get.mockRejectedValue({ status: 503 }); await expect(fetchDiscoveryPage('saved')).rejects.toMatchObject({ status: 503 }); expect(mocks.mining).not.toHaveBeenCalled() })
