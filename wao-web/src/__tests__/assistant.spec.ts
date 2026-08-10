import { describe, expect, it, vi } from 'vitest'
import { streamChat } from '@/api/assistant'

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
    for await (const chunk of streamChat('问题', [])) chunks.push(chunk)

    expect(chunks).toEqual(['第一行\n第二行'])
    vi.unstubAllGlobals()
  })
})
