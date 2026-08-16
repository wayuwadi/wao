import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MusicPlayer from '@/components/music/MusicPlayer.vue'
import { useDataStore } from '@/stores/data'

const base = { size: '3.2 MB', icon: 'ri-file-line', time: '刚刚', by: 'wg', link: '/download' }

describe('全局音乐播放器', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined)
  })

  it('只展示真实文件库中的音频，并能切歌和开始播放', async () => {
    const data = useDataStore()
    data.fileLoadState = 'loaded'
    data.files = [
      { ...base, id: 'song-1', name: '雨夜.mp3', contentType: 'audio/mpeg' },
      { ...base, id: 'doc-1', name: '说明.pdf', contentType: 'application/pdf' },
      { ...base, id: 'song-2', name: '清晨.flac', contentType: 'application/octet-stream' },
    ]

    const wrapper = mount(MusicPlayer)
    expect(wrapper.findAll('.playlist li')).toHaveLength(2)
    expect(wrapper.find('.track-copy strong').text()).toBe('雨夜')
    expect(wrapper.find('.playlist').text()).not.toContain('说明')

    await wrapper.find('.main-play').trigger('click')
    await flushPromises()
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalled()
    expect(wrapper.find('.track-copy small').text()).toContain('正在播放')

    await wrapper.findAll('.transport > button')[2]!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.track-copy strong').text()).toBe('清晨')
    expect(wrapper.find('audio').attributes('src')).toBe('/api/files/song-2/stream')
  })

  it('明确区分读取失败、空音乐库和重试动作', async () => {
    const data = useDataStore()
    data.files = []
    data.fileLoadState = 'error'
    data.fileLoadError = '文件服务暂时离线'
    const retry = vi.spyOn(data, 'refreshFiles').mockResolvedValue(true)

    const wrapper = mount(MusicPlayer)
    expect(wrapper.find('.player-state.error').text()).toContain('文件服务暂时离线')
    await wrapper.find('.player-state.error button').trigger('click')
    expect(retry).toHaveBeenCalledOnce()

    data.fileLoadState = 'loaded'
    await flushPromises()
    expect(wrapper.find('.player-empty').text()).toContain('音乐库还是空的')
    expect(wrapper.find('.player-empty').text()).toContain('添加音乐')
  })

  it('最小化后保留音频节点，并记住播放器偏好', async () => {
    const data = useDataStore()
    data.fileLoadState = 'loaded'
    data.files = [{ ...base, id: 'song-1', name: '雨夜.mp3', contentType: 'audio/mpeg' }]
    const wrapper = mount(MusicPlayer)

    await wrapper.find('.player-head button:last-child').trigger('click')

    expect(wrapper.find('.music-player').exists()).toBe(false)
    expect(wrapper.find('.music-launcher').exists()).toBe(true)
    expect(wrapper.find('audio').exists()).toBe(true)
    expect(JSON.parse(localStorage.getItem('wao-music-player-v1') ?? '{}').open).toBe(false)
  })
})
