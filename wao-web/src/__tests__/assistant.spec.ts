import { describe, expect, it, vi } from 'vitest'
import { streamChat } from '@/api/assistant'
import { streamTianrenInterpretation } from '@/api/tianren'

describe('AI assistant SSE', () => {
  it('保留 Spring SSE 多行 Markdown 答复', async () => {
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(
          'event: token\r\ndata: 第一行\r\ndata: 第二行\r\n\r\nevent: done\r\ndata: 完成\r\n\r\n',
        ))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 200 })))

    const chunks: string[] = []
    for await (const chunk of streamChat('问题', [], undefined, 'cv1')) chunks.push(chunk)

    expect(chunks).toEqual(['第一行\n第二行'])
    expect(fetch).toHaveBeenCalledWith('/api/assistant/chat/stream', expect.objectContaining({
      body: JSON.stringify({ message: '问题', history: [], pageContext: undefined, conversationId: 'cv1' }),
    }))
    vi.unstubAllGlobals()
  })

  it('识别 Spring SseEmitter 不带空格的事件名并立即展示天人解读', async () => {
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(
          'event:token\r\ndata:## 今日卦意\r\ndata:稳住节奏。\r\n\r\nevent:done\r\ndata:完成\r\n\r\n',
        ))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 200 })))

    const chunks: string[] = []
    for await (const chunk of streamTianrenInterpretation('今天怎样安排节奏')) chunks.push(chunk)

    expect(chunks).toEqual(['## 今日卦意\n稳住节奏。'])
    expect(fetch).toHaveBeenCalledWith(
      '/api/tianren/readings/today/interpret/stream',
      expect.objectContaining({ body: JSON.stringify({ focus: '今天怎样安排节奏' }) }),
    )
    vi.unstubAllGlobals()
  })
})
