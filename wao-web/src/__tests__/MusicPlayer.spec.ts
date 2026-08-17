import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MusicPlayer from '@/components/music/MusicPlayer.vue'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'

const neteaseApi = vi.hoisted(() => ({
  getNeteaseMusicStatus: vi.fn(),
  getNeteaseLikedSongs: vi.fn(),
  getNeteasePlayUrl: vi.fn(),
  reportNeteasePlayback: vi.fn(),
  beginNeteaseAuthorization: vi.fn(),
  disconnectNeteaseMusic: vi.fn(),
}))

vi.mock('@/api/neteaseMusic', () => neteaseApi)

const base = { size: '3.2 MB', icon: 'ri-file-line', time: '刚刚', by: 'wg', link: '/download' }

describe('全局音乐播放器', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    const preferences = usePreferenceStore()
    preferences.loaded = true
    preferences.musicPlayerDefaultOpen = true
    preferences.musicPlayerLauncherVisible = true
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined)
    neteaseApi.getNeteaseMusicStatus.mockResolvedValue({
      configured: false,
      connected: false,
      reauthRequired: false,
      message: '管理员尚未完成网易云音乐开放平台配置',
    })
    neteaseApi.getNeteaseLikedSongs.mockReset()
    neteaseApi.getNeteasePlayUrl.mockReset()
    neteaseApi.reportNeteasePlayback.mockReset().mockResolvedValue(undefined)
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

  it('最小化后保留音频节点，且不用本机状态覆盖成员默认开合', async () => {
    const data = useDataStore()
    data.fileLoadState = 'loaded'
    data.files = [{ ...base, id: 'song-1', name: '雨夜.mp3', contentType: 'audio/mpeg' }]
    const wrapper = mount(MusicPlayer)

    await wrapper.find('.player-head button:last-child').trigger('click')

    expect(wrapper.find('.music-player').exists()).toBe(false)
    expect(wrapper.find('.music-launcher').exists()).toBe(true)
    expect(wrapper.find('audio').exists()).toBe(true)
    expect(JSON.parse(localStorage.getItem('wao-music-player-v1') ?? '{}')).not.toHaveProperty('open')
  })

  it('新成员默认不展开面板，并可按成员偏好隐藏入口', async () => {
    const preferences = usePreferenceStore()
    preferences.musicPlayerDefaultOpen = false
    localStorage.setItem('wao-music-player-v1', JSON.stringify({ open: true }))

    const wrapper = mount(MusicPlayer)
    expect(wrapper.find('.music-player').exists()).toBe(false)
    expect(wrapper.find('.music-launcher').exists()).toBe(true)

    preferences.musicPlayerLauncherVisible = false
    await flushPromises()
    expect(wrapper.find('.music-launcher').exists()).toBe(false)
    expect(wrapper.find('audio').exists()).toBe(true)
  })

  it('只加载当前成员绑定账号的红心歌单，并使用官方临时地址播放和上报', async () => {
    const data = useDataStore()
    data.fileLoadState = 'loaded'
    data.files = []
    neteaseApi.getNeteaseMusicStatus.mockResolvedValue({
      configured: true,
      connected: true,
      reauthRequired: false,
      nickname: '我的网易云',
      message: '已连接',
    })
    neteaseApi.getNeteaseLikedSongs.mockResolvedValue({
      items: [{ id: '163-1', name: '夜曲', duration: 240000, artists: ['周杰伦'], album: '十一月的萧邦', playable: true, audition: false, alg: 'itembased' }],
      total: 1,
      offset: 0,
      limit: 30,
      playlistId: 'liked-1',
      playlistName: '我喜欢的音乐',
    })
    neteaseApi.getNeteasePlayUrl.mockResolvedValue({ url: 'https://music.example/song.mp3', bitrate: 320 })

    const wrapper = mount(MusicPlayer)
    await flushPromises()
    await wrapper.findAll('.source-tabs button')[1]!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.playlist').text()).toContain('夜曲')
    expect(wrapper.find('.playlist').text()).toContain('周杰伦')
    expect(wrapper.text()).not.toContain('accessToken')

    await wrapper.find('.playlist li button').trigger('click')
    await flushPromises()
    expect(neteaseApi.getNeteasePlayUrl).toHaveBeenCalledWith('163-1')
    expect(wrapper.find('audio').attributes('src')).toBe('https://music.example/song.mp3')

    await wrapper.find('audio').trigger('play')
    await flushPromises()
    expect(neteaseApi.reportNeteasePlayback).toHaveBeenCalledWith(expect.objectContaining({
      songId: '163-1',
      action: 'startplay',
      playlistId: 'liked-1',
    }))

    const audio = wrapper.find('audio')
    Object.defineProperty(audio.element, 'currentTime', { configurable: true, value: 2 })
    await audio.trigger('timeupdate')
    await audio.trigger('pause')
    await flushPromises()
    expect(neteaseApi.reportNeteasePlayback).toHaveBeenCalledTimes(1)

    await wrapper.findAll('.source-tabs button')[0]!.trigger('click')
    await flushPromises()
    expect(neteaseApi.reportNeteasePlayback).toHaveBeenLastCalledWith(expect.objectContaining({
      songId: '163-1',
      action: 'play',
      end: 'ui',
      playedSeconds: 2,
    }))
  })
})
