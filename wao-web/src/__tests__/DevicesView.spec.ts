import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DevicesView from '@/views/DevicesView.vue'

function mediaDevice(kind: MediaDeviceKind, deviceId: string, label: string): MediaDeviceInfo {
  return {
    kind,
    deviceId,
    label,
    groupId: `${deviceId}-group`,
    toJSON: () => ({}),
  }
}

function mediaTrack(kind: 'video' | 'audio') {
  return {
    kind,
    label: kind === 'video' ? '桌面摄像头' : 'USB 麦克风',
    stop: vi.fn<() => void>(),
    getSettings: () =>
      kind === 'video'
        ? { deviceId: 'camera-1', width: 1280, height: 720, frameRate: 30 }
        : { deviceId: 'microphone-1', sampleRate: 48000, channelCount: 1 },
  } as unknown as MediaStreamTrack
}

function mediaStream(track: MediaStreamTrack): MediaStream {
  return {
    getTracks: () => [track],
    getVideoTracks: () => (track.kind === 'video' ? [track] : []),
    getAudioTracks: () => (track.kind === 'audio' ? [track] : []),
  } as unknown as MediaStream
}

describe('终端设备页', () => {
  const originalMediaDevices = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices')
  const originalAudioContext = globalThis.AudioContext
  const originalRequestAnimationFrame = globalThis.requestAnimationFrame
  const originalCancelAnimationFrame = globalThis.cancelAnimationFrame
  const enumerateDevices = vi.fn<() => Promise<MediaDeviceInfo[]>>()
  const getUserMedia = vi.fn<(constraints: MediaStreamConstraints) => Promise<MediaStream>>()
  const addEventListener = vi.fn<(...args: unknown[]) => void>()
  const removeEventListener = vi.fn<(...args: unknown[]) => void>()

  beforeEach(() => {
    vi.clearAllMocks()
    enumerateDevices.mockResolvedValue([
      mediaDevice('videoinput', 'camera-1', '桌面摄像头'),
      mediaDevice('audioinput', 'microphone-1', 'USB 麦克风'),
    ])
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { enumerateDevices, getUserMedia, addEventListener, removeEventListener },
    })
    globalThis.requestAnimationFrame = vi.fn<(callback: FrameRequestCallback) => number>(() => 17)
    globalThis.cancelAnimationFrame = vi.fn<(handle: number) => void>()
    globalThis.AudioContext = class {
      state: AudioContextState = 'running'
      resume = vi.fn<() => Promise<void>>().mockResolvedValue(undefined)
      close = vi.fn<() => Promise<void>>().mockResolvedValue(undefined)
      createAnalyser = () => ({
        fftSize: 256,
        smoothingTimeConstant: 0,
        getByteTimeDomainData: (samples: Uint8Array) => samples.fill(132),
      })
      createMediaStreamSource = () => ({
        connect: vi.fn<(destination: AudioNode) => AudioNode>(),
        disconnect: vi.fn<() => void>(),
      })
    } as unknown as typeof AudioContext
  })

  afterEach(() => {
    if (originalMediaDevices) Object.defineProperty(navigator, 'mediaDevices', originalMediaDevices)
    else Reflect.deleteProperty(navigator, 'mediaDevices')
    globalThis.AudioContext = originalAudioContext
    globalThis.requestAnimationFrame = originalRequestAnimationFrame
    globalThis.cancelAnimationFrame = originalCancelAnimationFrame
  })

  it('只展示浏览器真实枚举的设备，并在用户点击后开启摄像头预览', async () => {
    const track = mediaTrack('video')
    const stream = mediaStream(track)
    getUserMedia.mockResolvedValue(stream)
    const wrapper = mount(DevicesView)
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('终端设备')
    expect(wrapper.text()).toContain('检测到 2 个输入设备')
    expect(wrapper.text()).toContain('桌面摄像头')
    expect(wrapper.text()).toContain('USB 麦克风')
    expect(getUserMedia).not.toHaveBeenCalled()

    await wrapper.get('.camera-card .primary-action').trigger('click')
    await flushPromises()

    expect(getUserMedia).toHaveBeenCalledWith({
      video: { deviceId: { exact: 'camera-1' } },
      audio: false,
    })
    expect(wrapper.get('.camera-card').text()).toContain('预览中')
    expect(wrapper.get('.camera-card').text()).toContain('1280 × 720 · 30 FPS')
    expect((wrapper.get('video').element as HTMLVideoElement).srcObject).toBe(stream)

    wrapper.unmount()
    expect(track.stop).toHaveBeenCalledOnce()
    expect(removeEventListener).toHaveBeenCalledWith('devicechange', expect.any(Function))
  })

  it('麦克风只做本机电平分析，拒绝权限时给出可恢复提示', async () => {
    getUserMedia.mockRejectedValue(new DOMException('denied', 'NotAllowedError'))
    const wrapper = mount(DevicesView)
    await flushPromises()

    await wrapper.get('.microphone-card .primary-action').trigger('click')
    await flushPromises()

    expect(getUserMedia).toHaveBeenCalledWith({
      video: false,
      audio: { deviceId: { exact: 'microphone-1' } },
    })
    expect(wrapper.get('.microphone-card').text()).toContain('浏览器没有获得设备权限')
    expect(wrapper.text()).toContain('不上传，不录制，不保存')
    wrapper.unmount()
  })

  it('无媒体设备时展示真实空状态并禁用调试动作', async () => {
    enumerateDevices.mockResolvedValue([])
    const wrapper = mount(DevicesView)
    await flushPromises()

    expect(wrapper.text()).toContain('没有检测到摄像头或麦克风')
    expect(wrapper.get('.camera-card .primary-action').attributes()).toHaveProperty('disabled')
    expect(wrapper.get('.microphone-card .primary-action').attributes()).toHaveProperty('disabled')
    expect(getUserMedia).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
