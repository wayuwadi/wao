<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { toast } from '@/composables/toast'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { audioStreamUrl, audioTitle, formatPlaybackTime, isAudioFile } from '@/utils/audio'
import { useFloatingDrag } from '@/composables/floatingDrag'
import {
  beginNeteaseAuthorization,
  disconnectNeteaseMusic,
  getNeteaseLikedSongs,
  getNeteaseMusicStatus,
  getNeteasePlayUrl,
  reportNeteasePlayback,
  type NeteaseMusicStatus,
  type NeteaseMusicTrack,
  type NeteasePlayUrl,
} from '@/api/neteaseMusic'

type MusicSource = 'local' | 'netease'

interface PlayerTrack {
  id: string
  name: string
  artist: string
  detail: string
  source: MusicSource
  playable: boolean
  alg?: string
}

interface PlayerPreference {
  trackId?: string
  volume?: number
  muted?: boolean
  playlistOpen?: boolean
  source?: MusicSource
}

const STORAGE_KEY = 'wao-music-player-v1'
const AUDIO_PICKER = 'audio/*,.mp3,.m4a,.aac,.wav,.ogg,.oga,.opus,.flac,.webm'
const AUDIO_NAME = /\.(?:mp3|m4a|aac|wav|ogg|oga|opus|flac|webm)$/i

function savedPreference(): PlayerPreference {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as PlayerPreference
  } catch {
    return {}
  }
}

const saved = savedPreference()
const { style: launcherStyle, dragging: launcherDragging, onPointerDown: startLauncherDrag,
  consumeClick: consumeLauncherClick } = useFloatingDrag('wao-music-launcher-position-v1')
const data = useDataStore()
const preferences = usePreferenceStore()
const audio = ref<HTMLAudioElement | null>(null)
const picker = ref<HTMLInputElement | null>(null)
const open = ref(false)
const playlistOpen = ref(saved.playlistOpen !== false)
const source = ref<MusicSource>(saved.source === 'netease' ? 'netease' : 'local')
const currentId = ref(saved.trackId ?? '')
const volume = ref(Math.min(1, Math.max(0, saved.volume ?? 0.72)))
const muted = ref(saved.muted ?? false)
const playing = ref(false)
const buffering = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const uploadCount = ref(0)
const uploadError = ref('')
const playbackError = ref('')
const neteaseStatus = ref<NeteaseMusicStatus | null>(null)
const neteaseTracks = ref<NeteaseMusicTrack[]>([])
const neteasePlaylistId = ref('')
const neteaseTotal = ref(0)
const neteaseLoading = ref(false)
const neteaseError = ref('')
const bindingBusy = ref(false)
const neteaseStreamUrl = ref('')

function openFromLauncher(event: MouseEvent) {
  if (!consumeLauncherClick(event)) open.value = true
}

interface PlaybackSession {
  songId: string
  bitrate: number
  startLogTime: number
  playedSeconds: number
  lastMediaTime: number
  playlistId: string
  alg?: string
  auditionStart?: number
  auditionEnd?: number
  started: boolean
}
let playbackSession: PlaybackSession | null = null
let currentPlayInfo: NeteasePlayUrl | null = null
let initialVisibilityApplied = false

watch(() => preferences.loaded, (loaded) => {
  if (!loaded || initialVisibilityApplied) return
  open.value = preferences.musicPlayerDefaultOpen
  initialVisibilityApplied = true
}, { immediate: true })

const localTracks = computed<PlayerTrack[]>(() => data.files.filter(isAudioFile).map((track) => ({
  id: track.id!,
  name: audioTitle(track.name),
  artist: track.by || 'WAO 成员',
  detail: track.size,
  source: 'local',
  playable: true,
})))
const cloudTracks = computed<PlayerTrack[]>(() => neteaseTracks.value.map((track) => ({
  id: track.id,
  name: track.name,
  artist: track.artists.join(' / ') || '未知歌手',
  detail: track.album || formatPlaybackTime(track.duration / 1000),
  source: 'netease',
  playable: track.playable,
  alg: track.alg,
})))
const tracks = computed(() => source.value === 'local' ? localTracks.value : cloudTracks.value)
const currentTrack = computed(() => tracks.value.find((track) => track.id === currentId.value) ?? tracks.value[0] ?? null)
const currentIndex = computed(() => tracks.value.findIndex((track) => track.id === currentTrack.value?.id))
const streamUrl = computed(() => currentTrack.value?.source === 'local'
  ? audioStreamUrl(currentTrack.value.id)
  : neteaseStreamUrl.value)

watch(tracks, (next) => {
  if (!next.length) {
    currentId.value = ''
    playing.value = false
    return
  }
  if (!next.some((track) => track.id === currentId.value)) currentId.value = next[0]!.id!
}, { immediate: true })

watch([currentId, volume, muted, playlistOpen, source], () => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    trackId: currentId.value,
    volume: volume.value,
    muted: muted.value,
    playlistOpen: playlistOpen.value,
    source: source.value,
  } satisfies PlayerPreference))
}, { deep: false })

watch([volume, muted], () => {
  if (!audio.value) return
  audio.value.volume = volume.value
  audio.value.muted = muted.value
}, { immediate: true })

watch(() => currentTrack.value?.id, () => {
  currentTime.value = 0
  duration.value = 0
  playbackError.value = ''
})

async function loadNeteaseStatus() {
  neteaseError.value = ''
  try {
    neteaseStatus.value = await getNeteaseMusicStatus()
    if (neteaseStatus.value.connected) await loadNeteaseSongs(true)
  } catch (error) {
    neteaseError.value = error instanceof Error ? error.message : '网易云连接状态读取失败'
  }
}

async function loadNeteaseSongs(reset = false) {
  if (neteaseLoading.value) return
  neteaseLoading.value = true
  neteaseError.value = ''
  try {
    const offset = reset ? 0 : neteaseTracks.value.length
    const page = await getNeteaseLikedSongs(offset, 30)
    neteaseTracks.value = reset ? page.items : [...neteaseTracks.value, ...page.items]
    neteasePlaylistId.value = page.playlistId
    neteaseTotal.value = page.total
  } catch (error) {
    neteaseError.value = error instanceof Error ? error.message : '红心歌单读取失败'
  } finally {
    neteaseLoading.value = false
  }
}

async function bindNetease() {
  bindingBusy.value = true
  neteaseError.value = ''
  try {
    const { authorizeUrl } = await beginNeteaseAuthorization()
    window.location.assign(authorizeUrl)
  } catch (error) {
    neteaseError.value = error instanceof Error ? error.message : '暂时无法发起网易云授权'
    bindingBusy.value = false
  }
}

async function unbindNetease() {
  if (!window.confirm('确定解除当前 WAO 成员与网易云音乐的绑定吗？')) return
  bindingBusy.value = true
  try {
    await finishNeteasePlayback('ui')
    await disconnectNeteaseMusic()
    neteaseTracks.value = []
    neteaseStatus.value = { configured: true, connected: false, reauthRequired: false, message: '绑定自己的网易云音乐账号后，可播放红心歌单' }
    currentId.value = ''
    neteaseStreamUrl.value = ''
  } catch (error) {
    neteaseError.value = error instanceof Error ? error.message : '解除绑定失败'
  } finally {
    bindingBusy.value = false
  }
}

async function changeSource(next: MusicSource) {
  if (next === source.value) return
  await finishNeteasePlayback('ui')
  audio.value?.pause()
  source.value = next
  currentId.value = ''
  neteaseStreamUrl.value = ''
  currentPlayInfo = null
}

async function playCurrent() {
  if (!audio.value || !currentTrack.value || !currentTrack.value.playable) return
  playbackError.value = ''
  try {
    await audio.value.play()
    playing.value = true
  } catch {
    playing.value = false
    playbackError.value = '浏览器未能开始播放，请重试或检查音频格式'
  }
}

function togglePlayback() {
  if (!currentTrack.value) {
    picker.value?.click()
    return
  }
  if (audio.value?.paused) void playCurrent()
  else audio.value?.pause()
}

async function selectTrack(id: string, autoplay = true) {
  const target = tracks.value.find((track) => track.id === id)
  if (!target?.playable) return
  if (currentTrack.value?.source === 'netease' && currentTrack.value.id !== id) await finishNeteasePlayback('ui')
  currentId.value = id
  neteaseStreamUrl.value = ''
  if (target.source === 'netease') {
    playbackError.value = ''
    buffering.value = true
    try {
      const play = await getNeteasePlayUrl(id)
      neteaseStreamUrl.value = play.url
      currentPlayInfo = play
      createPlaybackSession(target, play)
    } catch (error) {
      buffering.value = false
      playbackError.value = error instanceof Error ? error.message : '这首歌暂时无法播放'
      return
    }
  }
  await nextTick()
  audio.value?.load()
  if (autoplay) await playCurrent()
}

function move(step: number) {
  if (!tracks.value.length) return
  const start = Math.max(0, currentIndex.value)
  const next = (start + step + tracks.value.length) % tracks.value.length
  void selectTrack(tracks.value[next]!.id!)
}

function createPlaybackSession(track: PlayerTrack, play: NeteasePlayUrl) {
  playbackSession = {
    songId: track.id,
    bitrate: play.bitrate,
    startLogTime: Date.now(),
    playedSeconds: 0,
    lastMediaTime: 0,
    playlistId: neteasePlaylistId.value,
    alg: track.alg,
    auditionStart: play.auditionStart,
    auditionEnd: play.auditionEnd,
    started: false,
  }
}

async function startNeteasePlayback() {
  if (!playbackSession && currentTrack.value?.source === 'netease' && currentPlayInfo) {
    createPlaybackSession(currentTrack.value, currentPlayInfo)
  }
  if (!playbackSession || playbackSession.started) return
  playbackSession.started = true
  await reportNeteasePlayback({
    songId: playbackSession.songId,
    action: 'startplay',
    bitrate: playbackSession.bitrate,
    startLogTime: playbackSession.startLogTime,
    playlistId: playbackSession.playlistId,
    alg: playbackSession.alg,
    auditionStart: playbackSession.auditionStart,
    auditionEnd: playbackSession.auditionEnd,
  }).catch(() => undefined)
}

function onAudioPlay() {
  playing.value = true
  buffering.value = false
  void startNeteasePlayback()
}

function onAudioPause() {
  playing.value = false
  buffering.value = false
}

async function finishNeteasePlayback(end: 'playend' | 'interrupt' | 'exception' | 'ui') {
  const session = playbackSession
  playbackSession = null
  if (!session || session.playedSeconds <= 0) return
  await reportNeteasePlayback({
    songId: session.songId,
    action: 'play',
    bitrate: session.bitrate,
    startLogTime: session.startLogTime,
    playedSeconds: session.playedSeconds,
    end,
    playlistId: session.playlistId,
    alg: session.alg,
    auditionStart: session.auditionStart,
    auditionEnd: session.auditionEnd,
  }).catch(() => undefined)
}

function onTimeUpdate() {
  const next = audio.value?.currentTime ?? 0
  if (playbackSession && playing.value) {
    const delta = next - playbackSession.lastMediaTime
    if (delta > 0 && delta < 5) playbackSession.playedSeconds += delta
    playbackSession.lastMediaTime = next
  }
  currentTime.value = next
}

async function onEnded() {
  await finishNeteasePlayback('playend')
  move(1)
}

function onPlaybackError() {
  playbackError.value = currentTrack.value?.source === 'netease'
    ? '网易云播放地址已失效或当前歌曲不可播放，请重试'
    : '音频加载失败，请确认文件未被删除且格式受浏览器支持'
  void finishNeteasePlayback('exception')
}

function seek(event: Event) {
  if (!audio.value) return
  const value = Number((event.target as HTMLInputElement).value)
  audio.value.currentTime = value
  currentTime.value = value
}

function setVolume(event: Event) {
  volume.value = Number((event.target as HTMLInputElement).value)
  if (volume.value > 0) muted.value = false
}

function syncMetadata() {
  duration.value = Number.isFinite(audio.value?.duration) ? audio.value!.duration : 0
  currentTime.value = audio.value?.currentTime ?? 0
  if (audio.value) {
    audio.value.volume = volume.value
    audio.value.muted = muted.value
  }
}

function retryPlayback() {
  audio.value?.load()
  void playCurrent()
}

async function uploadMusic(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = Array.from(input.files ?? [])
  input.value = ''
  if (!selected.length) return
  const accepted = selected.filter((file) => file.type.toLowerCase().startsWith('audio/') || AUDIO_NAME.test(file.name))
  if (!accepted.length) {
    uploadError.value = '请选择 MP3、M4A、AAC、WAV、OGG、FLAC、Opus 或 WebM 音频'
    return
  }
  uploadError.value = ''
  uploadCount.value = accepted.length
  let failed = selected.length - accepted.length
  let uploaded = 0
  let firstUploaded = ''
  try {
    for (const file of accepted) {
      if (file.size > data.MAX_FILE_BYTES) {
        failed++
        continue
      }
      const item = await data.uploadFile(file)
      if (!item?.id) failed++
      else {
        uploaded++
        if (!firstUploaded) firstUploaded = item.id
      }
      uploadCount.value--
    }
  } finally {
    uploadCount.value = 0
  }
  if (firstUploaded) {
    await selectTrack(firstUploaded, false)
    toast(`已添加 ${uploaded} 首音乐`, 'ri-music-2-line')
  }
  if (failed) uploadError.value = `${failed} 个文件上传失败、格式不支持或超过 50MB`
}

onMounted(() => {
  const result = new URLSearchParams(window.location.search).get('netease')
  if (result === 'connected' || result === 'failed') {
    source.value = 'netease'
    const cleanUrl = new URL(window.location.href)
    cleanUrl.searchParams.delete('netease')
    window.history.replaceState({}, '', `${cleanUrl.pathname}${cleanUrl.search}${cleanUrl.hash}`)
  }
  void loadNeteaseStatus()
  if (result === 'connected') toast('网易云音乐已绑定', 'ri-netease-cloud-music-line')
  else if (result === 'failed') neteaseError.value = '网易云授权未完成，请重试'
})

onBeforeUnmount(() => {
  if (audio.value && !audio.value.paused) audio.value.pause()
  void finishNeteasePlayback('interrupt')
})
</script>

<template>
  <div class="music-host">
    <aside v-if="preferences.loaded && open" class="music-player" aria-label="WAO 音乐播放器">
      <header class="player-head">
        <div class="brand-mark"><i class="ri-music-2-line"></i></div>
        <div>
          <b>WAO 音乐</b>
          <span>{{ source === 'local' ? '来自真实文件库' : (neteaseStatus?.nickname ? `已连接 ${neteaseStatus.nickname}` : '来自你绑定的网易云账号') }}</span>
        </div>
        <button type="button" :aria-pressed="playlistOpen" title="展开或收起播放列表" @click="playlistOpen = !playlistOpen">
          <i class="ri-play-list-2-line"></i>
        </button>
        <button type="button" title="最小化播放器" @click="open = false"><i class="ri-subtract-line"></i></button>
      </header>

      <nav class="source-tabs" aria-label="音乐来源">
        <button type="button" :class="{ active: source === 'local' }" @click="changeSource('local')">WAO 文件</button>
        <button type="button" :class="{ active: source === 'netease' }" @click="changeSource('netease')">
          <i class="ri-netease-cloud-music-line"></i>网易云
        </button>
      </nav>

      <div v-if="source === 'local' && data.fileLoadState === 'loading'" class="player-state" aria-live="polite">
        <i class="ri-loader-4-line spin"></i><span>正在读取音乐库…</span>
      </div>
      <div v-else-if="source === 'local' && data.fileLoadState === 'error'" class="player-state error" role="alert">
        <i class="ri-error-warning-line"></i>
        <span>{{ data.fileLoadError || '音乐库读取失败' }}</span>
        <button type="button" @click="data.refreshFiles()">重试</button>
      </div>
      <div v-else-if="source === 'local' && !tracks.length" class="player-empty">
        <div class="empty-disc"><i class="ri-music-line"></i></div>
        <b>音乐库还是空的</b>
        <span>上传真实音频后，就能在 WAO 各页面持续播放。</span>
        <button type="button" :disabled="uploadCount > 0" @click="picker?.click()">
          <i class="ri-add-line"></i>{{ uploadCount ? `上传中 ${uploadCount}…` : '添加音乐' }}
        </button>
      </div>

      <div v-else-if="source === 'netease' && !neteaseStatus && !neteaseError" class="player-state" aria-live="polite">
        <i class="ri-loader-4-line spin"></i><span>正在读取网易云连接状态…</span>
      </div>
      <div v-else-if="source === 'netease' && neteaseStatus && !neteaseStatus.configured" class="player-empty cloud-state">
        <div class="cloud-logo"><i class="ri-netease-cloud-music-line"></i></div>
        <b>网易云音乐尚未开放</b>
        <span>{{ neteaseStatus.message }}</span>
      </div>
      <div v-else-if="source === 'netease' && neteaseStatus && !neteaseStatus.connected" class="player-empty cloud-state">
        <div class="cloud-logo"><i class="ri-netease-cloud-music-line"></i></div>
        <b>{{ neteaseStatus.reauthRequired ? '网易云授权已过期' : '连接你的网易云音乐' }}</b>
        <span>{{ neteaseStatus.message }}</span>
        <button type="button" :disabled="bindingBusy" @click="bindNetease">
          {{ bindingBusy ? '正在前往授权…' : (neteaseStatus.reauthRequired ? '重新绑定' : '绑定我的账号') }}
        </button>
      </div>
      <div v-else-if="source === 'netease' && neteaseLoading && !neteaseTracks.length" class="player-state" aria-live="polite">
        <i class="ri-loader-4-line spin"></i><span>正在读取红心歌单…</span>
      </div>
      <div v-else-if="source === 'netease' && neteaseError && !neteaseTracks.length" class="player-state error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ neteaseError }}</span>
        <button type="button" @click="loadNeteaseStatus">重试</button>
      </div>
      <div v-else-if="source === 'netease' && !tracks.length" class="player-empty cloud-state">
        <div class="cloud-logo"><i class="ri-heart-3-line"></i></div>
        <b>红心歌单还是空的</b>
        <span>去网易云音乐收藏歌曲后，再回来刷新。</span>
        <button type="button" :disabled="neteaseLoading" @click="loadNeteaseSongs(true)">刷新歌单</button>
      </div>

      <template v-else>
        <section class="now-playing">
          <button class="record" :class="{ playing }" type="button" :aria-label="playing ? '暂停' : '播放'" @click="togglePlayback">
            <span><i :class="playing ? 'ri-pause-fill' : 'ri-play-fill'"></i></span>
          </button>
          <div class="track-copy">
            <small>{{ playing ? (buffering ? '缓冲中' : '正在播放') : '准备播放' }}</small>
            <strong :title="currentTrack!.name">{{ currentTrack!.name }}</strong>
            <span>{{ currentTrack!.artist }} · {{ currentTrack!.detail }}</span>
          </div>
          <time class="wao-tabular">{{ formatPlaybackTime(currentTime) }} / {{ formatPlaybackTime(duration) }}</time>
        </section>

        <div class="progress-row">
          <input aria-label="播放进度" type="range" min="0" :max="duration || 0" step="0.1" :value="currentTime" @input="seek" />
        </div>

        <div class="transport">
          <button type="button" aria-label="上一首" title="上一首" @click="move(-1)"><i class="ri-skip-back-fill"></i></button>
          <button class="main-play" type="button" :aria-label="playing ? '暂停' : '播放'" @click="togglePlayback">
            <i :class="playing ? 'ri-pause-fill' : 'ri-play-fill'"></i>
          </button>
          <button type="button" aria-label="下一首" title="下一首" @click="move(1)"><i class="ri-skip-forward-fill"></i></button>
          <div class="volume">
            <button type="button" :aria-label="muted ? '取消静音' : '静音'" @click="muted = !muted">
              <i :class="muted || volume === 0 ? 'ri-volume-mute-line' : 'ri-volume-up-line'"></i>
            </button>
            <input aria-label="音量" type="range" min="0" max="1" step="0.05" :value="volume" @input="setVolume" />
          </div>
        </div>

        <div v-if="playbackError" class="inline-error" role="alert">
          <span>{{ playbackError }}</span><button type="button" @click="retryPlayback">重试</button>
        </div>

        <ol v-if="playlistOpen" class="playlist" aria-label="播放列表">
          <li v-for="(track, index) in tracks" :key="track.id" :class="{ active: track.id === currentTrack?.id, unavailable: !track.playable }">
            <button type="button" :disabled="!track.playable" @click="selectTrack(track.id)">
              <span class="track-number">{{ track.id === currentTrack?.id && playing ? '♫' : index + 1 }}</span>
              <span class="track-name" :title="`${track.name} · ${track.artist}`">{{ track.name }}</span>
              <span class="track-size">{{ track.playable ? track.artist : '不可播放' }}</span>
            </button>
          </li>
        </ol>

        <footer class="player-foot">
          <span>{{ source === 'netease' ? `${tracks.length} / ${neteaseTotal} 首红心歌曲` : `${tracks.length} 首音乐` }}</span>
          <button v-if="source === 'local'" type="button" :disabled="uploadCount > 0" @click="picker?.click()">
            <i class="ri-add-line"></i>{{ uploadCount ? `上传中 ${uploadCount}…` : '添加音乐' }}
          </button>
          <button v-else-if="tracks.length < neteaseTotal" type="button" :disabled="neteaseLoading" @click="loadNeteaseSongs(false)">
            {{ neteaseLoading ? '加载中…' : '加载更多' }}
          </button>
          <button v-else type="button" :disabled="bindingBusy" @click="unbindNetease">解除绑定</button>
        </footer>
      </template>

      <p v-if="uploadError" class="upload-error" role="alert">{{ uploadError }}</p>
    </aside>

    <button v-else-if="preferences.loaded && preferences.musicPlayerLauncherVisible" class="music-launcher" :class="{ dragging: launcherDragging }" :style="launcherStyle" type="button" title="拖动调整位置，点击打开音乐播放器" aria-label="打开音乐播放器" @pointerdown="startLauncherDrag" @click="openFromLauncher">
      <i :class="playing ? 'ri-disc-fill' : 'ri-music-2-line'"></i>
      <span v-if="playing" class="playing-dot"></span>
    </button>

    <input ref="picker" type="file" :accept="AUDIO_PICKER" multiple hidden @change="uploadMusic" />
    <audio
      ref="audio"
      :src="streamUrl"
      preload="metadata"
      @loadedmetadata="syncMetadata"
      @durationchange="syncMetadata"
      @timeupdate="onTimeUpdate"
      @play="onAudioPlay"
      @pause="onAudioPause"
      @waiting="buffering = true"
      @canplay="buffering = false"
      @ended="onEnded"
      @error="onPlaybackError"
    ></audio>
  </div>
</template>

<style scoped>
.music-host { position: relative; z-index: 72; }
.music-player {
  position: fixed;
  right: 22px;
  bottom: 22px;
  width: min(350px, calc(100vw - 32px));
  max-height: min(680px, calc(100vh - 44px));
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 20%, var(--wao-line));
  border-radius: 18px;
  background: color-mix(in srgb, var(--wao-bg-card) 95%, transparent);
  box-shadow: 0 18px 48px rgba(20, 24, 44, .2);
  backdrop-filter: blur(18px);
  animation: waoPop .18s ease-out;
}
.player-head { height: 54px; padding: 0 12px 0 14px; display: flex; align-items: center; gap: 9px; border-bottom: 1px solid var(--wao-divider); }
.brand-mark { width: 31px; height: 31px; display: grid; place-items: center; border-radius: 10px; background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.brand-mark i { font-size: 17px; }
.player-head > div:nth-child(2) { min-width: 0; flex: 1; display: grid; }
.player-head b { color: var(--wao-text); font-size: 13px; }
.player-head span { color: var(--wao-text-weak); font-size: 9.5px; }
.player-head button, .transport button { border: 0; background: transparent; color: var(--wao-text-3); cursor: pointer; }
.player-head button { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 8px; }
.player-head button:hover { background: var(--wao-bg-hover-2); color: var(--wao-text); }
.source-tabs { padding: 6px; display: grid; grid-template-columns: 1fr 1fr; gap: 5px; border-bottom: 1px solid var(--wao-divider); background: var(--wao-bg-sub); }
.source-tabs button { min-height: 30px; border: 0; border-radius: 8px; background: transparent; color: var(--wao-text-weak); cursor: pointer; font-size: 11px; }
.source-tabs button.active { background: var(--wao-bg-card); color: var(--wao-primary-text); box-shadow: 0 1px 4px rgba(20, 24, 44, .08); font-weight: 650; }
.source-tabs i { margin-right: 4px; color: #e82d3f; }
.player-state, .player-empty { min-height: 188px; padding: 26px 22px; display: flex; align-items: center; justify-content: center; gap: 9px; color: var(--wao-text-weak); font-size: 12px; }
.player-state.error { flex-wrap: wrap; color: var(--wao-danger-strong); text-align: center; }
.player-state.error span { flex-basis: calc(100% - 34px); }
.player-state.error button, .inline-error button { border: 0; background: transparent; color: var(--wao-primary-text); cursor: pointer; font-size: 11px; }
.player-empty { flex-direction: column; text-align: center; }
.empty-disc { width: 58px; height: 58px; display: grid; place-items: center; border-radius: 50%; background: radial-gradient(circle at center, var(--wao-bg-card) 0 13%, transparent 14%), repeating-radial-gradient(circle, #424754 0 3px, #252934 4px 7px); color: #fff; box-shadow: 0 5px 14px rgba(0,0,0,.18); }
.empty-disc i { padding: 7px; border-radius: 50%; background: var(--wao-primary); }
.cloud-logo { width: 58px; height: 58px; display: grid; place-items: center; border-radius: 18px; background: #e82d3f; color: #fff; box-shadow: 0 5px 14px rgba(232, 45, 63, .22); }
.cloud-logo i { font-size: 29px; }
.cloud-state button { background: #e82d3f; }
.player-empty b { color: var(--wao-text); font-size: 14px; }
.player-empty span { max-width: 230px; line-height: 1.55; }
.player-empty button, .player-foot button { border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; }
.player-empty button { padding: 8px 13px; }
.now-playing { padding: 16px 16px 10px; display: grid; grid-template-columns: 70px minmax(0, 1fr) auto; align-items: center; gap: 12px; }
.record { width: 70px; height: 70px; padding: 0; display: grid; place-items: center; border: 3px solid var(--wao-bg-card); border-radius: 50%; background: repeating-radial-gradient(circle, #424754 0 4px, #252934 5px 9px); box-shadow: 0 5px 14px rgba(0,0,0,.2); color: #fff; cursor: pointer; }
.record.playing { animation: recordSpin 5s linear infinite; }
.record span { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; background: var(--wao-primary); box-shadow: 0 0 0 5px color-mix(in srgb, var(--wao-primary) 25%, transparent); }
.record i { font-size: 17px; }
.track-copy { min-width: 0; display: grid; gap: 3px; }
.track-copy small { color: var(--wao-primary-text); font-size: 9.5px; font-weight: 650; }
.track-copy strong { overflow: hidden; color: var(--wao-text); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.track-copy span { overflow: hidden; color: var(--wao-text-weak); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.now-playing time { align-self: start; color: var(--wao-text-weak); font-size: 9.5px; }
.progress-row { padding: 0 16px; }
input[type='range'] { height: 4px; accent-color: var(--wao-primary); cursor: pointer; }
.progress-row input { width: 100%; }
.transport { height: 49px; padding: 0 15px; display: flex; align-items: center; gap: 4px; }
.transport > button { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%; font-size: 18px; }
.transport > button:hover { background: var(--wao-bg-hover-2); color: var(--wao-primary-text); }
.transport .main-play { background: var(--wao-primary); color: #fff; box-shadow: 0 4px 12px color-mix(in srgb, var(--wao-primary) 28%, transparent); }
.transport .main-play:hover { background: var(--wao-primary-hover); color: #fff; }
.volume { margin-left: auto; display: flex; align-items: center; gap: 2px; }
.volume button { width: 28px; height: 30px; border: 0; background: transparent; color: var(--wao-text-3); cursor: pointer; }
.volume input { width: 74px; }
.inline-error, .upload-error { margin: 0 14px 9px; padding: 7px 9px; border-radius: 8px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); font-size: 10px; line-height: 1.45; }
.inline-error { display: flex; align-items: center; gap: 6px; }
.inline-error span { flex: 1; }
.playlist { max-height: 224px; margin: 0; padding: 5px 8px 8px; overflow-y: auto; border-top: 1px solid var(--wao-divider); list-style: none; }
.playlist li button { width: 100%; min-height: 37px; padding: 0 8px; display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 5px; border: 0; border-radius: 8px; background: transparent; color: var(--wao-text-2); cursor: pointer; text-align: left; }
.playlist li button:hover { background: var(--wao-bg-hover); }
.playlist li.unavailable { opacity: .58; }
.playlist li.unavailable button { cursor: not-allowed; }
.playlist li.active button { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.track-number { color: var(--wao-text-weak); font-size: 10px; text-align: center; }
.active .track-number { color: var(--wao-primary-text); }
.track-name { overflow: hidden; font-size: 11.5px; font-weight: 550; text-overflow: ellipsis; white-space: nowrap; }
.track-size { max-width: 96px; overflow: hidden; color: var(--wao-text-weak); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }
.player-foot { min-height: 43px; padding: 6px 13px; display: flex; align-items: center; border-top: 1px solid var(--wao-divider); color: var(--wao-text-weak); font-size: 9.5px; }
.player-foot button { margin-left: auto; padding: 6px 9px; font-size: 10px; }
.player-foot i, .player-empty i { margin-right: 3px; }
.upload-error { margin-top: 9px; }
.music-launcher { position: fixed; right: 22px; bottom: 22px; width: 48px; height: 48px; display: grid; place-items: center; border: 1px solid color-mix(in srgb, var(--wao-primary) 25%, transparent); border-radius: 50%; background: var(--wao-primary); color: #fff; box-shadow: 0 8px 24px color-mix(in srgb, var(--wao-primary) 32%, transparent); cursor: grab; touch-action: none; user-select: none; }
.music-launcher.dragging { cursor: grabbing; }
.music-launcher i { font-size: 21px; }
.playing-dot { position: absolute; top: 3px; right: 2px; width: 9px; height: 9px; border: 2px solid var(--wao-bg-card); border-radius: 50%; background: var(--wao-success); }
.spin { animation: spin .85s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes recordSpin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .record.playing, .spin { animation: none; } }
@media (max-width: 760px) {
  .music-player { right: 12px; bottom: calc(76px + env(safe-area-inset-bottom)); width: calc(100vw - 24px); max-height: min(66vh, 610px); }
  .music-launcher { right: 18px; bottom: calc(142px + env(safe-area-inset-bottom)); width: 46px; height: 46px; }
  .playlist { max-height: min(190px, 24vh); }
  .now-playing { grid-template-columns: 62px minmax(0, 1fr); }
  .record { width: 62px; height: 62px; }
  .now-playing time { grid-column: 2; margin-top: -7px; }
}
</style>
