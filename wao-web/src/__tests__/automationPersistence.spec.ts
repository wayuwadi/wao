import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import type { AutomationJob } from '@/types'

const automationApi = vi.hoisted(() => ({
  apiRunJob: vi.fn<(id: string) => Promise<AutomationJob>>(),
  apiPauseJob: vi.fn<(id: string) => Promise<AutomationJob>>(),
}))

vi.mock('@/api/board', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/board')>()),
  apiRunJob: automationApi.apiRunJob,
  apiPauseJob: automationApi.apiPauseJob,
}))

function job(): AutomationJob {
  return {
    id: 'job-real',
    name: '真实自动化',
    icon: 'ri-flashlight-line',
    schedule: '仅手动触发',
    status: '正常',
    last: '尚未运行',
    desc: '',
    source: '',
    target: '',
    steps: [],
    runs: [],
    handler: 'real-handler',
  }
}

describe('自动化真实持久化', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('服务不可用时不生成本地成功运行', async () => {
    const data = useDataStore()
    data.autos = [job()]

    await expect(data.runJob('job-real')).rejects.toThrow('未执行任何更改')
    expect(data.autos[0]!.runs).toEqual([])
    expect(data.autos[0]!.last).toBe('尚未运行')
    expect(automationApi.apiRunJob).not.toHaveBeenCalled()
  })

  it('运行接口失败时保留服务端原状态', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.autos = [job()]
    automationApi.apiRunJob.mockRejectedValueOnce(new Error('执行器不可用'))

    await expect(data.runJob('job-real')).rejects.toThrow('执行器不可用')
    expect(data.autos[0]!.runs).toEqual([])
    expect(data.autos[0]!.last).toBe('尚未运行')
  })

  it('运行接口成功后才接收真实记录与耗时', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.autos = [job()]
    automationApi.apiRunJob.mockResolvedValueOnce({
      ...job(),
      last: '今天 14:00 · 成功 · 共 3 条',
      runs: [
        {
          time: '今天 14:00',
          executor: '当前成员',
          result: '成功 · 共 3 条',
          runAt: '2026-08-27T14:00:00',
          durationMs: 27,
        },
      ],
    })

    await data.runJob('job-real')
    expect(data.autos[0]!.runs).toHaveLength(1)
    expect(data.autos[0]!.runs[0]!.durationMs).toBe(27)
  })

  it('暂停接口失败时不伪装为已暂停', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.autos = [job()]
    automationApi.apiPauseJob.mockRejectedValueOnce(new Error('保存失败'))

    await expect(data.pauseJob('job-real')).rejects.toThrow('保存失败')
    expect(data.autos[0]!.status).toBe('正常')
  })
})
