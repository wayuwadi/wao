<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

type ProbeState = 'loading' | 'ready' | 'error' | 'unsupported'
type MediaState = 'idle' | 'requesting' | 'active' | 'error'

const devicesState = ref<ProbeState>('loading')
const devicesError = ref('')
const cameras = ref<MediaDeviceInfo[]>([])
const microphones = ref<MediaDeviceInfo[]>([])
const selectedCameraId = ref('')
const selectedMicrophoneId = ref('')

const cameraState = ref<MediaState>('idle')
const cameraError = ref('')
const cameraVideo = ref<HTMLVideoElement | null>(null)
const cameraSettings = ref<MediaTrackSettings | null>(null)
let cameraStream: MediaStream | null = null

const microphoneState = ref<MediaState>('idle')
const microphoneError = ref('')
const microphoneLevel = ref(0)
const microphoneSettings = ref<MediaTrackSettings | null>(null)
let microphoneStream: MediaStream | null = null
let audioContext: AudioContext | null = null
let audioSource: MediaStreamAudioSourceNode | null = null
let analyser: AnalyserNode | null = null
let animationFrame = 0

const mediaSupported = computed(
  () => window.isSecureContext !== false && Boolean(navigator.mediaDevices?.enumerateDevices),
)
const totalDevices = computed(() => cameras.value.length + microphones.value.length)

function deviceName(device: MediaDeviceInfo, index: number, kind: 'camera' | 'microphone') {
  if (device.label) return device.label
  return kind === 'camera' ? `摄像头 ${index + 1}` : `麦克风 ${index + 1}`
}

async function refreshDevices(quiet = false) {
  if (!mediaSupported.value) {
    devicesState.value = 'unsupported'
    devicesError.value =
      window.isSecureContext === false
        ? '浏览器只允许在 HTTPS 安全页面使用摄像头和麦克风'
        : '当前浏览器不支持媒体设备检测，请升级浏览器后重试'
    return
  }
  if (!quiet) devicesState.value = 'loading'
  devicesError.value = ''
  try {
    const detected = await navigator.mediaDevices.enumerateDevices()
    cameras.value = detected.filter((device) => device.kind === 'videoinput')
    microphones.value = detected.filter((device) => device.kind === 'audioinput')
    selectedCameraId.value = keepAvailableSelection(cameras.value, selectedCameraId.value)
    selectedMicrophoneId.value = keepAvailableSelection(
      microphones.value,
      selectedMicrophoneId.value,
    )
    devicesState.value = 'ready'
  } catch (error) {
    devicesState.value = 'error'
    devicesError.value = mediaErrorMessage(error, '设备检测失败，请检查浏览器设置后重试')
  }
}

function keepAvailableSelection(devices: MediaDeviceInfo[], selected: string) {
  return devices.some((device) => device.deviceId === selected)
    ? selected
    : (devices[0]?.deviceId ?? '')
}

async function toggleCamera() {
  if (cameraState.value === 'active') {
    stopCamera()
    return
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    cameraState.value = 'error'
    cameraError.value = '当前浏览器不支持摄像头预览'
    return
  }
  stopCamera()
  cameraState.value = 'requesting'
  cameraError.value = ''
  try {
    const video = selectedCameraId.value ? { deviceId: { exact: selectedCameraId.value } } : true
    cameraStream = await navigator.mediaDevices.getUserMedia({ video, audio: false })
    cameraSettings.value = cameraStream.getVideoTracks()[0]?.getSettings() ?? null
    await nextTick()
    if (cameraVideo.value) cameraVideo.value.srcObject = cameraStream
    cameraState.value = 'active'
    await refreshDevices(true)
  } catch (error) {
    stopCamera()
    cameraState.value = 'error'
    cameraError.value = mediaErrorMessage(error, '摄像头开启失败，请重新检测设备后重试')
  }
}

function stopCamera() {
  cameraStream?.getTracks().forEach((track) => track.stop())
  cameraStream = null
  cameraSettings.value = null
  if (cameraVideo.value) cameraVideo.value.srcObject = null
  if (cameraState.value !== 'error') cameraState.value = 'idle'
}

async function toggleMicrophone() {
  if (microphoneState.value === 'active') {
    void stopMicrophone()
    return
  }
  if (!navigator.mediaDevices?.getUserMedia || typeof AudioContext === 'undefined') {
    microphoneState.value = 'error'
    microphoneError.value = '当前浏览器不支持麦克风实时调试'
    return
  }
  await stopMicrophone()
  microphoneState.value = 'requesting'
  microphoneError.value = ''
  try {
    const audio = selectedMicrophoneId.value
      ? { deviceId: { exact: selectedMicrophoneId.value } }
      : true
    microphoneStream = await navigator.mediaDevices.getUserMedia({ video: false, audio })
    microphoneSettings.value = microphoneStream.getAudioTracks()[0]?.getSettings() ?? null
    audioContext = new AudioContext()
    await audioContext.resume()
    analyser = audioContext.createAnalyser()
    analyser.fftSize = 256
    analyser.smoothingTimeConstant = 0.72
    audioSource = audioContext.createMediaStreamSource(microphoneStream)
    audioSource.connect(analyser)
    microphoneState.value = 'active'
    sampleMicrophoneLevel()
    await refreshDevices(true)
  } catch (error) {
    await stopMicrophone()
    microphoneState.value = 'error'
    microphoneError.value = mediaErrorMessage(error, '麦克风开启失败，请重新检测设备后重试')
  }
}

function sampleMicrophoneLevel() {
  if (!analyser || microphoneState.value !== 'active') return
  const samples = new Uint8Array(analyser.fftSize)
  analyser.getByteTimeDomainData(samples)
  let squareSum = 0
  for (const sample of samples) {
    const normalized = (sample - 128) / 128
    squareSum += normalized * normalized
  }
  microphoneLevel.value = Math.min(100, Math.round(Math.sqrt(squareSum / samples.length) * 360))
  animationFrame = requestAnimationFrame(sampleMicrophoneLevel)
}

async function stopMicrophone() {
  if (animationFrame) cancelAnimationFrame(animationFrame)
  animationFrame = 0
  audioSource?.disconnect()
  audioSource = null
  analyser = null
  microphoneStream?.getTracks().forEach((track) => track.stop())
  microphoneStream = null
  microphoneSettings.value = null
  microphoneLevel.value = 0
  const closingContext = audioContext
  audioContext = null
  if (closingContext && closingContext.state !== 'closed') await closingContext.close()
  if (microphoneState.value !== 'error') microphoneState.value = 'idle'
}

function cameraChanged() {
  if (cameraState.value === 'active') stopCamera()
  cameraError.value = ''
}

function microphoneChanged() {
  if (microphoneState.value === 'active') void stopMicrophone()
  microphoneError.value = ''
}

function mediaErrorMessage(error: unknown, fallback: string) {
  if (!(error instanceof DOMException)) return error instanceof Error ? error.message : fallback
  if (error.name === 'NotAllowedError' || error.name === 'SecurityError') {
    return '浏览器没有获得设备权限，请在地址栏站点设置中允许后重试'
  }
  if (error.name === 'NotFoundError' || error.name === 'OverconstrainedError') {
    return '所选设备已断开或不可用，请重新检测并选择其它设备'
  }
  if (error.name === 'NotReadableError' || error.name === 'AbortError') {
    return '设备正被其它应用占用，请关闭占用程序后重试'
  }
  return fallback
}

function formatCameraSettings(settings: MediaTrackSettings | null) {
  if (!settings) return ''
  const resolution =
    settings.width && settings.height ? `${settings.width} × ${settings.height}` : ''
  const frameRate = settings.frameRate ? `${Math.round(settings.frameRate)} FPS` : ''
  return [resolution, frameRate].filter(Boolean).join(' · ')
}

function formatMicrophoneSettings(settings: MediaTrackSettings | null) {
  if (!settings) return ''
  const sampleRate = settings.sampleRate ? `${Math.round(settings.sampleRate / 1000)} kHz` : ''
  const channels = settings.channelCount ? `${settings.channelCount} 声道` : ''
  return [sampleRate, channels].filter(Boolean).join(' · ')
}

function handleDeviceChange() {
  void refreshDevices(true)
}

onMounted(() => {
  void refreshDevices()
  navigator.mediaDevices?.addEventListener?.('devicechange', handleDeviceChange)
})

onBeforeUnmount(() => {
  navigator.mediaDevices?.removeEventListener?.('devicechange', handleDeviceChange)
  stopCamera()
  void stopMicrophone()
})
</script>

<template>
  <main class="devices-page">
    <header class="hero">
      <div>
        <span class="eyebrow">LOCAL MEDIA DEVICES</span>
        <h1>终端设备</h1>
        <p>检测这台设备连接的摄像头和麦克风，直接完成画面预览与声音连通调试。</p>
      </div>
      <div class="privacy-badge">
        <i class="ri-shield-check-line"></i
        ><span><b>仅在本机使用</b><small>不上传，不录制，不保存</small></span>
      </div>
    </header>

    <section class="probe-panel" aria-live="polite">
      <div class="probe-summary">
        <i :class="devicesState === 'loading' ? 'ri-loader-4-line spin' : 'ri-radar-line'"></i>
        <div>
          <b v-if="devicesState === 'loading'">正在检测本机媒体设备…</b>
          <b v-else-if="devicesState === 'ready' && totalDevices"
            >检测到 {{ totalDevices }} 个输入设备</b
          >
          <b v-else-if="devicesState === 'ready'">没有检测到摄像头或麦克风</b>
          <b v-else>暂时无法检测媒体设备</b>
          <span v-if="devicesState === 'ready'"
            >{{ cameras.length }} 个摄像头 · {{ microphones.length }} 个麦克风</span
          >
          <span v-else>{{ devicesError }}</span>
        </div>
      </div>
      <button :disabled="devicesState === 'loading'" @click="refreshDevices()">
        <i class="ri-refresh-line"></i>重新检测
      </button>
    </section>

    <div class="device-grid">
      <section class="device-card camera-card">
        <div class="card-heading">
          <div class="device-icon camera"><i class="ri-camera-3-line"></i></div>
          <div>
            <span>CAMERA</span>
            <h2>摄像头预览</h2>
          </div>
          <em :class="cameraState">{{
            cameraState === 'active'
              ? '预览中'
              : cameraState === 'requesting'
                ? '连接中'
                : cameraState === 'error'
                  ? '连接失败'
                  : '未启用'
          }}</em>
        </div>

        <label class="device-select">
          <span>选择摄像头</span>
          <select
            v-model="selectedCameraId"
            :disabled="!cameras.length || cameraState === 'requesting'"
            @change="cameraChanged"
          >
            <option v-if="!cameras.length" value="">未检测到摄像头</option>
            <option
              v-for="(device, index) in cameras"
              :key="device.deviceId || index"
              :value="device.deviceId"
            >
              {{ deviceName(device, index, 'camera') }}
            </option>
          </select>
        </label>

        <div class="camera-preview" :class="{ active: cameraState === 'active' }">
          <video ref="cameraVideo" autoplay muted playsinline></video>
          <div v-if="cameraState !== 'active'" class="preview-empty">
            <i
              :class="cameraState === 'requesting' ? 'ri-loader-4-line spin' : 'ri-camera-off-line'"
            ></i>
            <b>{{ cameraState === 'requesting' ? '正在申请摄像头权限…' : '画面尚未开启' }}</b>
            <span>点击下方按钮后浏览器才会申请摄像头权限</span>
          </div>
          <span
            v-if="cameraState === 'active' && formatCameraSettings(cameraSettings)"
            class="track-settings"
            >{{ formatCameraSettings(cameraSettings) }}</span
          >
        </div>

        <p v-if="cameraError" class="error-message">
          <i class="ri-error-warning-line"></i>{{ cameraError }}
        </p>
        <button
          class="primary-action"
          :class="{ stop: cameraState === 'active' }"
          :disabled="!cameras.length || cameraState === 'requesting'"
          @click="toggleCamera"
        >
          <i
            :class="
              cameraState === 'active'
                ? 'ri-stop-circle-line'
                : cameraState === 'requesting'
                  ? 'ri-loader-4-line spin'
                  : 'ri-play-circle-line'
            "
          ></i>
          {{
            cameraState === 'active'
              ? '停止预览'
              : cameraState === 'requesting'
                ? '正在开启…'
                : '开启预览'
          }}
        </button>
      </section>

      <section class="device-card microphone-card">
        <div class="card-heading">
          <div class="device-icon microphone"><i class="ri-mic-line"></i></div>
          <div>
            <span>MICROPHONE</span>
            <h2>麦克风调试</h2>
          </div>
          <em :class="microphoneState">{{
            microphoneState === 'active'
              ? '监听中'
              : microphoneState === 'requesting'
                ? '连接中'
                : microphoneState === 'error'
                  ? '连接失败'
                  : '未启用'
          }}</em>
        </div>

        <label class="device-select">
          <span>选择麦克风</span>
          <select
            v-model="selectedMicrophoneId"
            :disabled="!microphones.length || microphoneState === 'requesting'"
            @change="microphoneChanged"
          >
            <option v-if="!microphones.length" value="">未检测到麦克风</option>
            <option
              v-for="(device, index) in microphones"
              :key="device.deviceId || index"
              :value="device.deviceId"
            >
              {{ deviceName(device, index, 'microphone') }}
            </option>
          </select>
        </label>

        <div class="microphone-monitor">
          <div class="meter-header">
            <span>实时输入电平</span
            ><b>{{ microphoneState === 'active' ? `${microphoneLevel}%` : '等待测试' }}</b>
          </div>
          <div
            class="meter"
            role="progressbar"
            aria-label="麦克风实时输入电平"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="microphoneLevel"
          >
            <span :style="{ width: `${microphoneLevel}%` }"></span>
          </div>
          <div class="meter-scale"><span>安静</span><span>正常说话</span><span>较响</span></div>
          <div class="mic-visual" :class="{ active: microphoneState === 'active' }">
            <i
              :class="
                microphoneState === 'requesting' ? 'ri-loader-4-line spin' : 'ri-sound-module-line'
              "
            ></i>
            <div>
              <b>{{
                microphoneState === 'active'
                  ? '请对着麦克风说话'
                  : microphoneState === 'requesting'
                    ? '正在申请麦克风权限…'
                    : '开始测试后观察电平变化'
              }}</b
              ><span>{{
                microphoneState === 'active'
                  ? formatMicrophoneSettings(microphoneSettings) || '已建立本机音频分析链路'
                  : '声音只用于本机实时分析，不会播放或保存'
              }}</span>
            </div>
          </div>
        </div>

        <p v-if="microphoneError" class="error-message">
          <i class="ri-error-warning-line"></i>{{ microphoneError }}
        </p>
        <button
          class="primary-action microphone-action"
          :class="{ stop: microphoneState === 'active' }"
          :disabled="!microphones.length || microphoneState === 'requesting'"
          @click="toggleMicrophone"
        >
          <i
            :class="
              microphoneState === 'active'
                ? 'ri-stop-circle-line'
                : microphoneState === 'requesting'
                  ? 'ri-loader-4-line spin'
                  : 'ri-pulse-line'
            "
          ></i>
          {{
            microphoneState === 'active'
              ? '停止测试'
              : microphoneState === 'requesting'
                ? '正在连接…'
                : '开始测试'
          }}
        </button>
      </section>
    </div>

    <section class="boundary-note">
      <i class="ri-information-line"></i>
      <div>
        <b>本期接入边界</b>
        <p>
          这里管理的是当前浏览器能直接访问的本机媒体输入。网络摄像头、远程麦克风和跨设备绑定需要具体硬件协议与授权，尚未接入时不会显示虚假设备。
        </p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.devices-page {
  max-width: 1240px;
  margin: 0 auto;
  padding: 28px 30px 72px;
  color: var(--wao-text);
}
button,
select {
  color: inherit;
}
button {
  cursor: pointer;
}
.hero {
  position: relative;
  overflow: hidden;
  min-height: 188px;
  border-radius: 24px;
  padding: 32px 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  color: #fff;
  background: linear-gradient(125deg, #17294b, #245c91 60%, #1c8e9e);
  box-shadow: 0 18px 50px rgba(20, 54, 89, 0.2);
}
.hero::after {
  content: '';
  position: absolute;
  width: 320px;
  height: 320px;
  border: 52px solid rgba(255, 255, 255, 0.07);
  border-radius: 50%;
  right: -100px;
  top: -170px;
}
.eyebrow {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.16em;
  color: rgba(255, 255, 255, 0.66);
}
.hero h1 {
  margin: 8px 0 8px;
  font-size: clamp(28px, 4vw, 42px);
  letter-spacing: -0.04em;
}
.hero p {
  margin: 0;
  max-width: 680px;
  line-height: 1.75;
  color: rgba(255, 255, 255, 0.78);
}
.privacy-badge {
  position: relative;
  z-index: 1;
  min-width: 220px;
  padding: 18px;
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 18px;
  background: rgba(7, 25, 46, 0.25);
  backdrop-filter: blur(10px);
}
.privacy-badge > i {
  font-size: 30px;
  color: #72e4c5;
}
.privacy-badge span {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.privacy-badge small {
  color: rgba(255, 255, 255, 0.66);
}
.probe-panel {
  margin: 16px 0;
  padding: 15px 17px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}
.probe-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.probe-summary > i {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary);
  font-size: 19px;
}
.probe-summary div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.probe-summary span {
  color: var(--wao-text-weak);
  font-size: 11px;
}
.probe-panel button {
  flex: none;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 9px 12px;
  background: var(--wao-bg-sub);
}
.probe-panel button i {
  margin-right: 5px;
}
.probe-panel button:disabled {
  opacity: 0.55;
  cursor: wait;
}
.device-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.device-card {
  min-width: 0;
  padding: 21px;
  border: 1px solid var(--wao-line);
  border-radius: 18px;
  background: var(--wao-bg-card);
  box-shadow: 0 8px 26px rgba(26, 39, 65, 0.05);
}
.card-heading {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 18px;
}
.device-icon {
  width: 43px;
  height: 43px;
  display: grid;
  place-items: center;
  flex: none;
  border-radius: 12px;
  font-size: 21px;
}
.device-icon.camera {
  color: #236eb4;
  background: rgba(45, 132, 210, 0.12);
}
.device-icon.microphone {
  color: #168b75;
  background: rgba(33, 168, 140, 0.12);
}
.card-heading > div:nth-child(2) {
  min-width: 0;
}
.card-heading span {
  font-size: 9px;
  letter-spacing: 0.13em;
  color: var(--wao-text-weak);
}
.card-heading h2 {
  margin: 2px 0 0;
  font-size: 18px;
}
.card-heading em {
  margin-left: auto;
  flex: none;
  padding: 5px 8px;
  border-radius: 999px;
  background: var(--wao-bg-sub-2);
  color: var(--wao-text-weak);
  font-size: 10px;
  font-style: normal;
}
.card-heading em.active {
  color: var(--wao-success);
  background: var(--wao-success-bg);
}
.card-heading em.requesting {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
}
.card-heading em.error {
  color: var(--wao-danger);
  background: var(--wao-danger-bg);
}
.device-select {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-bottom: 13px;
  font-size: 11px;
  font-weight: 700;
}
.device-select select {
  width: 100%;
  min-height: 41px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 0 11px;
  background: var(--wao-bg-sub);
}
.device-select select:disabled {
  opacity: 0.58;
}
.camera-preview {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 10;
  border-radius: 13px;
  background: #0f1724;
}
.camera-preview video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.preview-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 7px;
  padding: 24px;
  text-align: center;
  color: rgba(255, 255, 255, 0.72);
}
.preview-empty i {
  font-size: 32px;
  color: rgba(255, 255, 255, 0.5);
}
.preview-empty b {
  font-size: 13px;
}
.preview-empty span {
  font-size: 10px;
  color: rgba(255, 255, 255, 0.48);
}
.track-settings {
  position: absolute;
  right: 9px;
  bottom: 9px;
  padding: 5px 8px;
  border-radius: 7px;
  background: rgba(5, 12, 20, 0.64);
  color: #fff;
  font-size: 9px;
}
.microphone-monitor {
  padding: 17px;
  border: 1px solid var(--wao-divider);
  border-radius: 13px;
  background: var(--wao-bg-sub);
}
.meter-header,
.meter-scale {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.meter-header {
  align-items: center;
  font-size: 11px;
}
.meter-header b {
  color: var(--wao-primary-text);
}
.meter {
  overflow: hidden;
  height: 11px;
  margin: 12px 0 6px;
  border-radius: 999px;
  background: var(--wao-bg-sub-2);
}
.meter span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #26b793, #6cc551 68%, #f2a840);
  transition: width 80ms linear;
}
.meter-scale {
  color: var(--wao-text-weak);
  font-size: 8px;
}
.mic-visual {
  min-height: 111px;
  margin-top: 17px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border-top: 1px dashed var(--wao-line-strong);
  color: var(--wao-text-weak);
}
.mic-visual > i {
  font-size: 29px;
}
.mic-visual div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.mic-visual b {
  color: var(--wao-text-2);
  font-size: 12px;
}
.mic-visual span {
  font-size: 9px;
}
.mic-visual.active > i {
  color: var(--wao-success);
  animation: mic-pulse 1.2s ease-in-out infinite;
}
.error-message {
  display: flex;
  gap: 6px;
  min-height: 34px;
  margin: 10px 0 -4px;
  padding: 8px 9px;
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger);
  font-size: 10px;
  line-height: 1.5;
}
.primary-action {
  width: 100%;
  min-height: 42px;
  margin-top: 13px;
  border: 0;
  border-radius: 10px;
  background: #2879bd;
  color: #fff;
  font-weight: 800;
}
.primary-action i {
  margin-right: 6px;
}
.primary-action.microphone-action {
  background: #168b75;
}
.primary-action.stop {
  background: var(--wao-bg-sub-2);
  color: var(--wao-text-2);
  border: 1px solid var(--wao-line-control);
}
.primary-action:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.boundary-note {
  margin-top: 16px;
  padding: 15px 17px;
  display: flex;
  gap: 11px;
  border-radius: 13px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
}
.boundary-note > i {
  color: var(--wao-primary);
  font-size: 19px;
}
.boundary-note b {
  color: var(--wao-text-2);
  font-size: 12px;
}
.boundary-note p {
  margin: 5px 0 0;
  font-size: 10px;
  line-height: 1.65;
}
.spin {
  display: inline-block;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes mic-pulse {
  50% {
    transform: scale(1.14);
    opacity: 0.7;
  }
}
@media (max-width: 880px) {
  .devices-page {
    padding: 18px 14px 64px;
  }
  .hero {
    align-items: flex-start;
  }
  .privacy-badge {
    min-width: 190px;
  }
  .device-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 560px) {
  .devices-page {
    padding: 10px 9px 58px;
  }
  .hero {
    display: block;
    min-height: 0;
    padding: 24px 19px;
    border-radius: 17px;
  }
  .hero p {
    font-size: 12px;
  }
  .privacy-badge {
    width: 100%;
    margin-top: 20px;
  }
  .probe-panel {
    align-items: stretch;
    flex-direction: column;
  }
  .probe-panel button {
    min-height: 42px;
  }
  .device-card {
    padding: 17px 14px;
    border-radius: 14px;
  }
  .primary-action {
    min-height: 46px;
  }
  .boundary-note {
    align-items: flex-start;
  }
}
</style>
