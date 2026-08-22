<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import fruitKernelRaccoon from '@/assets/wao-fruit-kernel-raccoon.png'
import {
  detectDesktopPlatform,
  orderedDesktopDownloads,
  type DesktopPlatform,
} from '@/utils/desktopDownload'

interface LatestManifest {
  version?: string
  pub_date?: string
}

const platform = ref<DesktopPlatform>('other')
const latest = ref<LatestManifest | null>(null)
const loading = ref(true)
const available = ref<Record<'windows' | 'macos', boolean>>({ windows: false, macos: false })

const downloads = computed(() => orderedDesktopDownloads(platform.value))
const versionText = computed(() => latest.value?.version ? `最新版本 v${latest.value.version}` : '版本信息暂不可用')

async function exists(url: string) {
  try {
    const response = await fetch(url, { method: 'HEAD', cache: 'no-store' })
    return response.ok
  } catch {
    return false
  }
}

onMounted(async () => {
  platform.value = detectDesktopPlatform(navigator.userAgent, navigator.platform)
  try {
    const [manifestResponse, windows, macos] = await Promise.all([
      fetch('/downloads/desktop/latest.json', { cache: 'no-store' }),
      exists('/downloads/desktop/WAO-Pet-windows-x64-setup.exe'),
      exists('/downloads/desktop/WAO-Pet-macos-universal.dmg'),
    ])
    const contentType = manifestResponse.headers.get('content-type') ?? ''
    if (manifestResponse.ok && contentType.includes('application/json')) {
      latest.value = await manifestResponse.json() as LatestManifest
    }
    available.value = latest.value?.version ? { windows, macos } : { windows: false, macos: false }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <main class="desktop-page">
    <header class="desktop-header">
      <a class="brand" href="/" aria-label="返回 WAO"><span>WAO</span><b>桌面宠物</b></a>
      <a class="back" href="/"><i class="ri-arrow-left-line"></i>返回网页版</a>
    </header>

    <section class="hero">
      <div class="hero-copy">
        <span class="eyebrow">WAO DESKTOP COMPANION</span>
        <h1>把 WAO 留在桌面，<br>安静陪你做重要的事。</h1>
        <p>Windows 与 macOS 使用同一个轻量桌面宠物。第一次从这里安装，以后直接在宠物上检查、下载并应用更新。</p>
        <div class="status"><i :class="loading ? 'ri-loader-4-line spin' : 'ri-checkbox-circle-line'"></i>{{ loading ? '正在读取发布状态…' : versionText }}</div>
      </div>
      <div class="pet-preview" aria-label="WAO 桌面宠物预览">
        <div class="preview-bubble">今天也一起，把重要的事情慢慢做好。</div>
        <img :src="fruitKernelRaccoon" alt="WAO 果核狸桌面宠物">
      </div>
    </section>

    <section class="downloads" aria-labelledby="download-title">
      <div class="section-heading"><span>01</span><div><h2 id="download-title">选择你的电脑</h2><p>已把当前系统排在前面，也可以手动下载另一平台。</p></div></div>
      <div class="download-grid">
        <article v-for="item in downloads" :key="item.id" :class="{ recommended: platform === item.id }">
          <span v-if="platform === item.id" class="recommended-label">适合当前电脑</span>
          <div class="platform-icon"><i :class="item.id === 'windows' ? 'ri-windows-fill' : 'ri-apple-fill'"></i></div>
          <h3>{{ item.label }}</h3>
          <p>{{ item.detail }}</p>
          <a v-if="available[item.id]" :href="item.href" download><i class="ri-download-cloud-2-line"></i>下载安装包</a>
          <button v-else disabled><i class="ri-time-line"></i>{{ loading ? '正在检查…' : '首次安装包正在准备' }}</button>
          <small>{{ item.fileName }}</small>
        </article>
      </div>
    </section>

    <section class="steps">
      <div class="section-heading"><span>02</span><div><h2>第一次安装之后</h2><p>不再反复卸载，也不用每次回来找安装包。</p></div></div>
      <div class="step-grid">
        <article><b>1</b><i class="ri-drag-move-2-line"></i><h3>放在顺手的位置</h3><p>拖动宠物到桌面任意位置；隐藏后可从系统托盘或菜单栏重新唤回。</p></article>
        <article><b>2</b><i class="ri-refresh-line"></i><h3>宠物自己检查更新</h3><p>启动后安静检查，也能随时点击宠物上的更新按钮。</p></article>
        <article><b>3</b><i class="ri-shield-check-line"></i><h3>确认后快速更新</h3><p>更新包通过专用签名校验，下载完成后自动安装并重启。</p></article>
      </div>
    </section>

    <aside class="install-note">
      <i class="ri-information-line"></i>
      <div><b>关于首次安装提示</b><p>当前是少量自用版本，尚未购买 Windows Authenticode 与 Apple Developer ID 证书。Windows 可能显示 SmartScreen；macOS 可能需要在“隐私与安全性”中确认打开。应用内后续更新仍会强制验证 WAO 的独立更新签名。</p></div>
    </aside>
  </main>
</template>

<style scoped>
.desktop-page { min-height: 100vh; color: #22263e; background: radial-gradient(circle at 82% 14%, #e5e4ff 0, transparent 30%), #f8f8fc; font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif; }
.desktop-header { height: 68px; display: flex; align-items: center; justify-content: space-between; max-width: 1120px; margin: auto; padding: 0 28px; }
.brand { display: flex; align-items: center; gap: 10px; color: inherit; text-decoration: none; }.brand span { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 10px; color: white; background: #6668dc; font-weight: 800; font-size: 11px; }.brand b { font-size: 14px; }
.back { display: flex; align-items: center; gap: 5px; color: #64687d; text-decoration: none; font-size: 13px; }.back:hover { color: #5558c6; }
.hero { max-width: 1064px; min-height: 470px; margin: auto; padding: 62px 34px 42px; display: grid; grid-template-columns: 1.2fr .8fr; align-items: center; gap: 60px; }
.eyebrow { color: #6668dc; font-size: 11px; font-weight: 800; letter-spacing: 2px; }.hero h1 { margin: 14px 0 18px; font-size: clamp(36px, 4.6vw, 58px); line-height: 1.12; letter-spacing: -2px; }.hero-copy > p { max-width: 590px; color: #696d81; font-size: 16px; line-height: 1.8; }.status { width: fit-content; margin-top: 26px; padding: 9px 14px; border: 1px solid #dfe0ec; border-radius: 99px; background: rgba(255,255,255,.75); color: #565a71; font-size: 12px; }.status i { margin-right: 7px; color: #6164d0; }
.pet-preview { position: relative; width: 310px; height: 340px; justify-self: center; animation: hover 3s ease-in-out infinite; }.preview-bubble { position: absolute; top: 0; left: 5px; right: 5px; padding: 18px 20px; border: 1px solid #e1e1ee; border-radius: 20px; background: rgba(255,255,255,.9); box-shadow: 0 18px 45px #41437720; color: #4f536b; font-size: 13px; text-align: center; }.preview-bubble::after { content: ''; position: absolute; left: 50%; bottom: -8px; width: 15px; height: 15px; background: white; border-right: 1px solid #e1e1ee; border-bottom: 1px solid #e1e1ee; transform: translateX(-50%) rotate(45deg); }
.pet-preview img { position: absolute; left: 25px; bottom: 4px; width: 270px; height: 250px; object-fit: contain; filter: drop-shadow(0 18px 24px #34366a2b); }
.downloads,.steps { max-width: 1064px; margin: auto; padding: 58px 34px; }.section-heading { display: flex; gap: 16px; align-items: flex-start; margin-bottom: 28px; }.section-heading > span { color: #696bd9; font-size: 11px; font-weight: 800; letter-spacing: 1px; margin-top: 6px; }.section-heading h2 { margin: 0; font-size: 25px; }.section-heading p { margin: 7px 0 0; color: #7a7e91; font-size: 13px; }
.download-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 18px; }.download-grid article { position: relative; padding: 28px; border: 1px solid #e1e2ec; border-radius: 20px; background: white; box-shadow: 0 10px 28px #3e41620b; }.download-grid article.recommended { border-color: #7779e9; box-shadow: 0 12px 36px #5a5cca1c; }.recommended-label { position: absolute; top: 15px; right: 15px; padding: 5px 8px; border-radius: 99px; color: #5b5dcc; background: #eeeefe; font-size: 10px; font-weight: 700; }.platform-icon { width: 50px; height: 50px; display: grid; place-items: center; border-radius: 14px; color: #5b5dcc; background: #f0f0fe; font-size: 25px; }.download-grid h3 { margin: 18px 0 6px; font-size: 20px; }.download-grid p { margin: 0 0 22px; color: #74788c; font-size: 13px; }.download-grid a,.download-grid button { width: 100%; height: 42px; display: flex; align-items: center; justify-content: center; gap: 7px; border: 0; border-radius: 11px; color: white; background: #6264d1; text-decoration: none; font-weight: 700; font-size: 13px; cursor: pointer; }.download-grid button:disabled { color: #85899a; background: #edeef3; cursor: default; }.download-grid small { display: block; margin-top: 10px; color: #a0a3b1; font-size: 10px; text-align: center; }
.step-grid { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 16px; }.step-grid article { position: relative; padding: 25px 23px; border-radius: 18px; background: #edeefe; }.step-grid article > b { position: absolute; right: 18px; top: 15px; color: #c8c9ef; font-size: 28px; }.step-grid i { color: #6062ce; font-size: 24px; }.step-grid h3 { margin: 17px 0 8px; font-size: 15px; }.step-grid p { margin: 0; color: #6f738a; font-size: 12px; line-height: 1.7; }
.install-note { max-width: 996px; margin: 8px auto 70px; padding: 20px 22px; display: flex; gap: 13px; border: 1px solid #e5dfce; border-radius: 15px; background: #fffaf0; color: #6d6249; }.install-note > i { color: #b58a32; font-size: 20px; }.install-note b { font-size: 13px; }.install-note p { margin: 5px 0 0; font-size: 12px; line-height: 1.7; }
.spin { display: inline-block; animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }@keyframes hover { 50% { transform: translateY(-8px); } }
@media (max-width:760px) { .desktop-header { padding: 0 18px; }.hero { grid-template-columns: 1fr; padding: 44px 22px 20px; gap: 20px; }.hero h1 { font-size: 38px; }.pet-preview { transform: scale(.82); margin: -20px auto; }.downloads,.steps { padding: 44px 22px; }.download-grid,.step-grid { grid-template-columns: 1fr; }.install-note { margin: 8px 22px 50px; } }
@media (prefers-reduced-motion:reduce) { * { animation-duration: .001ms !important; animation-iteration-count: 1 !important; } }
</style>
