<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import SiteFooter from '@/components/SiteFooter.vue'
import {
  detectClientPlatform,
  fetchMobileDownloads,
  fetchWindowsDownload,
  fetchMacosDownload,
  type ClientDownload,
  type ClientPlatform,
} from '@/api/clientDownloads'

const mobile = ref<ClientDownload[]>([])
const desktop = ref<ClientDownload[]>([])
const mobileState = ref('loading')
const desktopState = ref('loading')
const macos = ref<ClientDownload[]>([])
const macosState = ref('loading')
const currentPlatform = detectClientPlatform(
  navigator.userAgent,
  navigator.platform,
  navigator.maxTouchPoints,
)
const mobilePlatforms = computed(() =>
  currentPlatform === 'ios' ? (['ios', 'android'] as const) : (['android', 'ios'] as const),
)
const copied = ref('')
const copyError = ref(false)
const details = {
  android: {
    name: 'Android',
    icon: 'ri-android-fill',
    suffix: 'APK · Android 9.0 及以上',
    action: '下载 Android 版',
  },
  ios: {
    name: 'iPhone / iPad',
    icon: 'ri-apple-fill',
    suffix: '未签名 IPA · iOS 15.0 及以上',
    action: '下载 iOS 安装包',
  },
}
const getFile = (platform: ClientPlatform) =>
  mobile.value.find((file) => file.platform === platform)
const sizeText = (size: number) => `${(size / 1024 / 1024).toFixed(1)} MB`
async function loadMobile() {
  mobileState.value = 'loading'
  try {
    mobile.value = await fetchMobileDownloads()
    mobileState.value = 'ready'
  } catch {
    mobileState.value = 'error'
  }
}
async function loadDesktop() {
  desktopState.value = 'loading'
  try {
    desktop.value = await fetchWindowsDownload()
    desktopState.value = 'ready'
  } catch {
    desktopState.value = 'error'
  }
}
async function loadMacos() {
  macosState.value = 'loading'
  try {
    macos.value = await fetchMacosDownload()
    macosState.value = 'ready'
  } catch {
    macosState.value = 'error'
  }
}
async function copyHash(file: ClientDownload) {
  copyError.value = false
  try {
    await navigator.clipboard.writeText(file.sha256!)
    copied.value = file.platform
  } catch {
    copyError.value = true
  }
}
onMounted(() => {
  void loadMobile()
  void loadDesktop()
  void loadMacos()
  void nextTick(() => document.getElementById(location.hash.slice(1))?.scrollIntoView())
})
</script>

<template>
  <div class="download-page">
    <header class="download-header">
      <a href="/" class="wordmark" aria-label="WAO 首页">wao</a>
      <nav aria-label="下载页导航"><a href="#mobile">移动端</a><a href="#desktop">桌面端</a></nav>
      <a href="/" class="web-entry"
        >打开网页版 <i class="ri-arrow-right-up-line" aria-hidden="true"></i
      ></a>
    </header>
    <main>
      <section id="mobile" class="mobile-section" aria-labelledby="mobile-title">
        <div class="hero-copy">
          <p class="eyebrow"><span></span> 历史客户端 · 已停止维护</p>
          <h1 id="mobile-title">历史客户端安装包</h1>
          <p class="intro">WAO 现在仅维护浏览器版本。<br />这里保留旧安装包和安装说明，不再发布客户端新版。</p>
          <div class="mobile-actions" :aria-busy="mobileState === 'loading'">
            <div v-for="platform in mobilePlatforms" :key="platform" class="platform-action">
              <a
                v-if="mobileState === 'ready' && getFile(platform)"
                class="download-button"
                :class="{ secondary: platform === 'ios' }"
                :href="getFile(platform)!.url"
                download
              >
                <i :class="details[platform].icon" aria-hidden="true"></i
                >{{ details[platform].action }}<i class="ri-download-2-line" aria-hidden="true"></i>
              </a>
              <button v-else class="download-button" disabled>
                <i :class="details[platform].icon" aria-hidden="true"></i
                >{{
                  mobileState === 'loading'
                    ? '正在检查版本…'
                    : mobileState === 'error'
                      ? '暂时无法下载'
                      : '未找到历史安装包'
                }}
              </button>
              <small>{{ details[platform].suffix }}</small>
              <small v-if="mobileState === 'ready' && getFile(platform)"
                >v{{ getFile(platform)!.version }} · {{ sizeText(getFile(platform)!.size)
                }}<span v-if="currentPlatform === platform" class="current-device"
                  >适合当前设备</span
                ></small
              >
            </div>
          </div>
          <p v-if="mobileState === 'error'" class="load-error" role="alert">
            发布信息加载失败。<button @click="loadMobile">重新加载</button>
          </p>
          <a href="#install" class="install-link"
            >iOS 需个人签名，查看安装说明 <i class="ri-arrow-right-line" aria-hidden="true"></i
          ></a>
        </div>
        <div class="mobile-stage" aria-label="WAO 移动端界面示意">
          <span class="stage-orbit orbit-one"></span><span class="stage-orbit orbit-two"></span>
          <div class="floating-note">
            <i class="ri-sticky-note-line"></i
            ><span>一闪而过的想法<br /><b>也值得被记下来</b></span>
          </div>
          <div class="phone">
            <div class="phone-camera"></div>
            <div class="phone-status"><b>9:41</b><span>••• ▰</span></div>
            <div class="phone-content">
              <div class="phone-brand">wao <span>让日常，轻一点</span></div>
              <p class="phone-date">美好的一天，从这里开始</p>
              <h2>今天，慢慢来<span>☀</span></h2>
              <div class="phone-tabs"><b>今天</b><span>日历</span><span>全部待办</span></div>
              <div class="progress-card">
                <span>给重要的事，留一点时间</span><b>一步一步，也是在向前。</b>
                <div><span></span></div>
              </div>
              <div class="mock-task">
                <i class="ri-checkbox-circle-fill"></i><span class="done">喝一杯水，开启今天</span>
              </div>
              <div class="mock-task">
                <i class="ri-checkbox-blank-circle-line"></i
                ><span>整理这周的小计划<small>个人成长 · 今天</small></span
                ><em>计划</em>
              </div>
              <div class="mock-task">
                <i class="ri-checkbox-blank-circle-line"></i
                ><span>傍晚出去走一走<small>生活 · 今天</small></span
                ><em class="peach">生活</em>
              </div>
              <div class="note-card">
                <span>随手小记 <i class="ri-sparkling-line"></i></span>
                <p>不必把每一天填满，<br />留一点空白给自己。</p>
              </div>
              <div class="phone-bottom">
                <b><i class="ri-checkbox-circle-line"></i>待办</b
                ><span><i class="ri-calendar-line"></i>日历</span
                ><span><i class="ri-sticky-note-line"></i>小记</span
                ><span><i class="ri-user-line"></i>我的</span>
              </div>
            </div>
          </div>
          <span class="preview-caption">界面示意 · 待办 / 日历 / 小记</span>
        </div>
      </section>
      <section id="desktop" class="desktop-section" aria-labelledby="desktop-title">
        <div class="section-copy">
          <p class="eyebrow">保留旧安装包与兼容入口</p>
          <h2 id="desktop-title">历史桌面客户端</h2>
          <p>把任务、项目和小记放在一个工作区里。<br />随时打开，接着上次的思路继续。</p>
        </div>
        <div class="desktop-actions" :aria-busy="desktopState === 'loading'">
          <div>
            <a
              v-if="desktopState === 'ready' && desktop[0]"
              class="download-button"
              :href="desktop[0].url"
              download
              ><i class="ri-windows-fill"></i>下载 Windows 版<i class="ri-download-2-line"></i></a
            ><button v-else class="download-button" disabled>
              <i class="ri-windows-fill"></i
              >{{
                desktopState === 'loading'
                  ? '正在检查版本…'
                  : desktopState === 'error'
                    ? '暂时无法下载'
                    : '未找到历史 Windows 安装包'
              }}</button
            ><small
              >Windows 10 / 11 · x64<span v-if="desktopState === 'ready' && desktop[0]">
                · v{{ desktop[0].version }}</span
              ></small
            >
          </div>
          <div class="macos-download" :aria-busy="macosState === 'loading'">
            <a
              v-if="macosState === 'ready' && macos[0]"
              class="download-button secondary"
              :href="macos[0].url"
              download
            >
              <i class="ri-apple-fill"></i>下载 Mac 版<i class="ri-download-2-line"></i>
            </a>
            <button v-else class="download-button secondary" disabled>
              <i class="ri-apple-fill"></i
              >{{
                macosState === 'loading'
                  ? '正在检查版本…'
                  : macosState === 'error'
                    ? 'Mac 版暂时无法下载'
                    : 'Mac 未找到历史安装包'
              }}
            </button>
            <small>macOS 11.3 及以上 · Intel 与 Apple 芯片通用</small>
            <small v-if="macosState === 'ready' && macos[0]"
              >v{{ macos[0].version }} · {{ sizeText(macos[0].size)
              }}<span v-if="currentPlatform === 'macos'" class="current-device"
                >适合当前设备</span
              ></small
            >
            <small><a href="#mac-install">查看 Mac 安装说明</a></small>
          </div>
        </div>
        <p v-if="desktopState === 'error'" class="load-error" role="alert">
          桌面版发布信息加载失败。<button @click="loadDesktop">重新加载</button>
        </p>
        <p v-if="macosState === 'error'" class="load-error" role="alert">
          Mac 版发布信息加载失败。<button @click="loadMacos">重新加载 Mac 版</button>
        </p>
        <div class="desktop-stage" aria-label="WAO 桌面工作区界面示意">
          <div class="workspace">
            <div class="window-bar">
              <span>● ● ●</span><b>WAO 工作区</b><i class="ri-search-line"></i>
            </div>
            <div class="workspace-body">
              <aside>
                <b class="mini-logo">wao</b
                ><span class="selected"><i class="ri-sun-line"></i>我的工作台</span
                ><span><i class="ri-checkbox-circle-line"></i>任务</span
                ><span><i class="ri-calendar-line"></i>日历</span
                ><span><i class="ri-sticky-note-line"></i>小记</span><small>我的空间</small
                ><span>◎ 个人成长</span><span>◎ 日常生活</span>
              </aside>
              <div class="workspace-main">
                <p>每一步，都算数。</p>
                <h3>今天，专注在重要的事上。</h3>
                <div class="workspace-stats">
                  <div>
                    <small>今日待办</small><b>03 <span>件小事</span></b>
                  </div>
                  <div>
                    <small>留给自己</small><b>01 <span>段专注时光</span></b>
                  </div>
                </div>
                <div class="workspace-list">
                  <b>我的待办 <span>＋ 新建任务</span></b>
                  <p>○ <span>整理这周的小计划</span><em>今天</em></p>
                  <p>○ <span>把脑海中的想法写下来</span><em>今天</em></p>
                  <p>○ <span>傍晚出去走一走</span><em>生活</em></p>
                </div>
              </div>
            </div>
          </div>
          <span class="preview-caption">界面示意 · 完整桌面工作区</span>
        </div>
      </section>
      <section id="install" class="install-section" aria-labelledby="install-title">
        <p class="eyebrow">从这里开始</p>
        <h2 id="install-title">下载之后，简单几步。</h2>
        <div class="install-grid">
          <article>
            <span class="step-number">01</span>
            <h3>Android，直接安装</h3>
            <p>在手机上下载 APK，按系统提示允许本次安装。旧版本覆盖安装通常保留应用数据。</p>
          </article>
          <article>
            <span class="step-number">02</span>
            <h3>iOS，先完成个人签名</h3>
            <p>
              这里提供未签名 IPA，不能点击直接安装。请在电脑上使用你自己的签名工具与 Apple
              账号签名，再安装到 iPhone 或 iPad。签名过期后需重新签名。
            </p>
          </article>
          <article>
            <span class="step-number">03</span>
            <h3>登录，开始记录</h3>
            <p>
              使用已有 WAO 账号登录。在「今日」进入松风江湖，使用游戏账号继续旅程，无需另装游戏 App。待办、日历和小记仍可照常使用。
            </p>
          </article>
        </div>
        <article id="mac-install" class="mac-install">
          <h3>Mac，拖入“应用程序”即可安装</h3>
          <p>
            下载并打开 DMG，把 WAO 拖入 Applications（应用程序），再从应用程序中打开。Intel 和 Apple
            芯片使用同一个安装包；关闭窗口后，可从 Dock 或菜单栏找回工作区。
          </p>
          <p>
            当前 Mac 版尚未经过 Apple Developer ID 签名与公证。首次打开若被 macOS
            拦截，请确认文件来自本站，再到“系统设置 →
            隐私与安全性”查看该应用的打开选项。原版本的更新包会验证 WAO 发布签名，当前已停止客户端发布。
          </p>
          <details v-if="macosState === 'ready' && macos[0]" class="checksums">
            <summary>查看 Mac 安装包 SHA-256</summary>
            <code>{{ macos[0].sha256 }}</code>
          </details>
        </article>
        <details v-if="mobileState === 'ready' && mobile.length" class="checksums">
          <summary>查看安装包 SHA-256 校验信息</summary>
          <div v-for="file in mobile" :key="file.platform">
            <b>{{ details[file.platform as 'android' | 'ios'].name }} · v{{ file.version }}</b
            ><code>{{ file.sha256 }}</code
            ><button @click="copyHash(file)">
              {{ copied === file.platform ? '已复制' : '复制校验和' }}
            </button>
          </div>
          <p v-if="copyError" role="status">复制失败，请选中上方校验和手动复制。</p>
        </details>
      </section>
      <aside class="pet-link">
        <span><i class="ri-bear-smile-line"></i>另有历史桌面宠物安装包</span
        ><a href="/desktop">查看历史桌面宠物 <i class="ri-arrow-right-up-line"></i></a>
      </aside>
    </main>
    <SiteFooter />
  </div>
</template>

<style scoped>
.download-page {
  --ink: #203a34;
  --muted: #66756e;
  --green: #245e4e;
  background: #fafbf8;
  color: var(--ink);
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  min-height: 100vh;
  overflow-x: clip;
}
.download-page * {
  box-sizing: border-box;
}
.download-page a {
  color: inherit;
  text-decoration: none;
}
.download-page button,
.download-page a {
  touch-action: manipulation;
}
.download-page a:focus-visible,
.download-page button:focus-visible,
.download-page summary:focus-visible {
  outline: 3px solid #b3873f;
  outline-offset: 5px;
}
.download-header {
  height: 90px;
  max-width: 1240px;
  margin: auto;
  padding: 0 48px;
  display: flex;
  align-items: center;
  gap: 48px;
}
.wordmark {
  font-size: 38px;
  font-weight: 850;
  letter-spacing: -3px;
  line-height: 1;
}
.wordmark span {
  font-size: 12px;
  vertical-align: top;
  letter-spacing: 0;
  margin-left: 4px;
}
.download-header nav {
  display: flex;
  gap: 28px;
  font-size: 14px;
}
.web-entry {
  margin-left: auto;
  font-size: 13px;
  border: 1px solid #dce2da;
  padding: 11px 16px;
  border-radius: 30px;
}
.mobile-section {
  max-width: 1240px;
  margin: auto;
  padding: 64px 48px 96px;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 42px;
  align-items: center;
  scroll-margin-top: 20px;
}
.eyebrow {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 2px;
  margin: 0 0 25px;
}
.eyebrow > span {
  display: inline-block;
  background: #7b9c76;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 8px;
}
.hero-copy h1 {
  font-size: clamp(48px, 5.1vw, 70px);
  font-weight: 650;
  line-height: 1.18;
  letter-spacing: -3px;
  margin: 0 0 26px;
}
.intro,
.section-copy > p:last-child {
  font-size: 17px;
  line-height: 1.85;
  color: var(--muted);
}
.mobile-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 34px;
}
.download-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  border: 1px solid var(--green);
  background: var(--green);
  color: white !important;
  border-radius: 32px;
  min-height: 50px;
  padding: 13px 20px;
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background 0.15s,
    transform 0.15s;
}
.download-button:hover {
  background: #174d3e;
  transform: translateY(-1px);
}
.download-button i {
  font-size: 19px;
}
.download-button.secondary {
  color: var(--ink) !important;
  background: transparent;
  border-color: #cad4cc;
}
.download-button.secondary:hover {
  background: #eef2ec;
}
.download-button:disabled {
  background: #e7ece5;
  border-color: #dce3d9;
  color: #667368 !important;
  cursor: default;
  transform: none;
}
.platform-action > small,
.desktop-actions small {
  display: block;
  font-size: 10px;
  color: var(--muted);
  margin: 9px 2px 0;
  line-height: 1.6;
}
.current-device {
  margin-left: 8px;
  color: var(--green);
}
.install-link {
  display: inline-block;
  margin-top: 23px;
  font-size: 12px;
  border-bottom: 1px solid #b9c8bc;
  padding-bottom: 4px;
}
.mobile-stage {
  height: 580px;
  border-radius: 220px 220px 100px 100px;
  background: #e8eee1;
  position: relative;
  display: grid;
  place-items: center;
  isolation: isolate;
}
.stage-orbit {
  position: absolute;
  border: 1px solid #cedac5;
  border-radius: 50%;
  z-index: -1;
  width: 460px;
  height: 460px;
}
.orbit-one {
  transform: rotate(-30deg) scaleX(0.7);
}
.orbit-two {
  transform: rotate(35deg) scaleX(0.7);
}
.phone {
  width: 254px;
  height: 516px;
  border-radius: 39px;
  background: #f9faf5;
  border: 7px solid #243c35;
  box-shadow: 12px 22px 36px #314e3424;
  transform: rotate(5deg);
  position: relative;
  overflow: hidden;
}
.phone-camera {
  position: absolute;
  width: 74px;
  height: 18px;
  background: #243c35;
  border-radius: 0 0 13px 13px;
  top: 0;
  left: calc(50% - 37px);
}
.phone-status {
  font-size: 9px;
  padding: 10px 18px 0;
  display: flex;
  justify-content: space-between;
}
.phone-content {
  padding: 16px 15px 0;
}
.phone-brand {
  font-size: 22px;
  font-weight: 850;
  letter-spacing: -1px;
}
.phone-brand > span {
  font-size: 7px;
  letter-spacing: 0;
  float: right;
  margin-top: 10px;
  color: #79857a;
}
.phone-date {
  font-size: 8px;
  color: #7c8b7d;
  margin: 12px 0 6px;
}
.phone-content h2 {
  font-size: 21px;
  letter-spacing: -0.7px;
  margin: 0;
}
.phone-content h2 > span {
  float: right;
  color: #b59d51;
}
.phone-tabs {
  display: flex;
  gap: 20px;
  font-size: 9px;
  margin: 14px 0 10px;
  color: #8a9186;
}
.phone-tabs b {
  color: #245e4e;
  border-bottom: 2px solid;
  padding-bottom: 6px;
}
.progress-card {
  padding: 13px 12px;
  background: #e7edde;
  border-radius: 12px;
  display: grid;
  gap: 7px;
  font-size: 8px;
  color: #698062;
}
.progress-card b {
  font-size: 10px;
  color: #35523b;
}
.progress-card > div {
  height: 3px;
  background: #d5dfcd;
  margin-top: 4px;
  border-radius: 5px;
}
.progress-card > div > span {
  display: block;
  width: 34%;
  height: 100%;
  background: #5b8064;
}
.mock-task {
  display: flex;
  gap: 7px;
  align-items: center;
  font-size: 9px;
  padding: 10px 0;
  border-bottom: 1px solid #e9ece4;
}
.mock-task > i {
  font-size: 15px;
  color: #6e8b68;
}
.mock-task small {
  display: block;
  color: #92998c;
  font-size: 7px;
  margin-top: 5px;
}
.mock-task em {
  margin-left: auto;
  font-size: 7px;
  font-style: normal;
  border-radius: 4px;
  padding: 3px 5px;
  background: #e8eddf;
  color: #6c805e;
}
.mock-task em.peach {
  background: #f3e8dd;
  color: #a48662;
}
.done {
  text-decoration: line-through;
  color: #97a08e;
}
.note-card {
  border-radius: 12px;
  background: #f3eee0;
  padding: 12px;
  margin-top: 12px;
  font-size: 8px;
}
.note-card > span {
  color: #9e8b68;
}
.note-card i {
  float: right;
}
.note-card p {
  font-size: 10px;
  line-height: 1.7;
  margin: 7px 0 0;
}
.phone-bottom {
  display: flex;
  justify-content: space-between;
  padding: 10px 8px;
  font-size: 7px;
  color: #a0a79c;
}
.phone-bottom i {
  display: block;
  font-size: 16px;
  margin-bottom: 3px;
}
.phone-bottom b {
  color: #355f47;
}
.floating-note {
  position: absolute;
  right: -8px;
  top: 85px;
  padding: 16px 19px;
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid #fff;
  border-radius: 16px;
  background: #ffffffdf;
  box-shadow: 0 8px 25px #3b4f2c09;
  z-index: 2;
  transform: rotate(-5deg);
  font-size: 10px;
  line-height: 1.8;
  color: #86917c;
}
.floating-note > i {
  font-size: 25px;
  color: #6f8560;
}
.floating-note b {
  color: #4c6144;
  font-weight: 500;
}
.preview-caption {
  position: absolute;
  bottom: 12px;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 9px;
  letter-spacing: 1px;
  color: #7c8976;
}
.desktop-section {
  background: #edf0ea;
  padding: 76px 24px 0;
  text-align: center;
  scroll-margin-top: 20px;
  overflow: hidden;
}
.section-copy h2,
.install-section h2 {
  font-size: clamp(28px, 3.4vw, 42px);
  font-weight: 600;
  letter-spacing: -1.4px;
  margin: 0 0 16px;
}
.section-copy .eyebrow {
  margin-bottom: 18px;
}
.desktop-actions {
  display: flex;
  justify-content: center;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 28px;
}
.desktop-actions small a {
  text-decoration: underline;
}
.desktop-stage {
  position: relative;
  max-width: 850px;
  margin: 52px auto 0;
  padding: 0 25px 32px;
}
.workspace {
  border: 1px solid #d4dbd0;
  border-radius: 14px 14px 0 0;
  background: #fafbf8;
  box-shadow:
    0 0 0 7px #ffffff77,
    0 25px 70px #3a4e3120;
  text-align: left;
  overflow: hidden;
}
.window-bar {
  height: 35px;
  border-bottom: 1px solid #e2e7de;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 15px;
  font-size: 9px;
  color: #8d9787;
}
.window-bar > span {
  color: #bbcab6;
  letter-spacing: 3px;
}
.window-bar b {
  font-weight: 400;
}
.workspace-body {
  display: grid;
  grid-template-columns: 160px 1fr;
  min-height: 338px;
}
.workspace-body aside {
  border-right: 1px solid #e5e9e0;
  padding: 20px 13px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 10px;
  color: #77816f;
}
.mini-logo {
  font-size: 24px;
  letter-spacing: -2px;
  color: #3c5944;
  margin: 0 8px 20px;
}
.workspace-body aside > span {
  padding: 8px;
  border-radius: 5px;
}
.workspace-body aside i {
  margin-right: 7px;
}
.workspace-body aside .selected {
  background: #e8eede;
  color: #49623c;
}
.workspace-body aside > small {
  font-size: 8px;
  margin: 13px 8px 2px;
  color: #9aa28e;
}
.workspace-main {
  padding: 26px 32px;
}
.workspace-main > p {
  font-size: 9px;
  color: #8a977e;
  margin: 0 0 7px;
}
.workspace-main h3 {
  font-size: 20px;
  letter-spacing: -0.7px;
  margin: 0 0 20px;
}
.workspace-stats {
  display: flex;
  gap: 12px;
}
.workspace-stats > div {
  flex: 1;
  background: #edf1e7;
  border-radius: 8px;
  padding: 14px;
}
.workspace-stats small {
  font-size: 8px;
  color: #899577;
}
.workspace-stats b {
  display: block;
  font-size: 22px;
  margin-top: 7px;
  font-weight: 500;
}
.workspace-stats b span {
  font-size: 8px;
  color: #8f9a80;
  margin-left: 6px;
}
.workspace-list {
  margin-top: 22px;
  font-size: 9px;
}
.workspace-list > b {
  display: block;
  font-size: 10px;
}
.workspace-list > b > span {
  float: right;
  font-weight: 400;
  color: #738263;
  font-size: 8px;
}
.workspace-list p {
  border-bottom: 1px solid #e5eadf;
  padding: 10px 0;
  margin: 0;
  display: flex;
  gap: 9px;
  color: #82946f;
}
.workspace-list p span {
  color: #667658;
}
.workspace-list em {
  font-style: normal;
  font-size: 7px;
  margin-left: auto;
  background: #edf1e7;
  padding: 2px 5px;
}
.install-section {
  max-width: 1120px;
  margin: 0 auto;
  padding: 86px 40px 60px;
  scroll-margin-top: 20px;
}
.install-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 36px;
  margin-top: 38px;
}
.step-number {
  font-size: 12px;
  color: #a0ae94;
}
.install-grid h3 {
  font-size: 17px;
  font-weight: 600;
  margin: 15px 0 12px;
}
.install-grid p {
  font-size: 13px;
  line-height: 1.9;
  color: var(--muted);
  margin: 0;
}
.mac-install {
  margin-top: 38px;
  padding-top: 24px;
  border-top: 1px solid #e0e6da;
  scroll-margin-top: 24px;
}
.mac-install p {
  font-size: 13px;
  line-height: 1.9;
  color: var(--muted);
}
.checksums {
  margin-top: 40px;
  border-top: 1px solid #e0e6da;
  padding-top: 22px;
  font-size: 12px;
  color: var(--muted);
}
.checksums summary {
  cursor: pointer;
}
.checksums > div {
  margin-top: 18px;
}
.checksums code {
  display: block;
  overflow-wrap: anywhere;
  padding: 10px 0;
  font-size: 11px;
}
.checksums button,
.load-error button {
  border: 0;
  background: none;
  color: var(--green);
  text-decoration: underline;
  cursor: pointer;
  padding: 4px;
}
.load-error {
  font-size: 12px;
  color: #885848;
  margin: 12px 0;
}
.pet-link {
  max-width: 1040px;
  margin: 0 auto 32px;
  border-top: 1px solid #e0e6da;
  padding: 28px 0;
  display: flex;
  justify-content: space-between;
  gap: 15px;
  font-size: 13px;
  color: var(--muted);
}
.pet-link > span > i {
  margin-right: 8px;
}
.pet-link > a {
  color: var(--green);
}
@media (max-width: 1050px) {
  .download-header {
    padding: 0 30px;
  }
  .mobile-section {
    padding: 45px 30px 70px;
    gap: 25px;
  }
  .hero-copy h1 {
    font-size: 56px;
  }
  .mobile-stage {
    height: 565px;
  }
  .floating-note {
    right: 0;
    padding: 12px;
    top: 65px;
    font-size: 9px;
  }
  .mobile-actions {
    gap: 14px;
  }
  .platform-action > small {
    font-size: 10px;
  }
  .pet-link {
    margin-left: 40px;
    margin-right: 40px;
  }
  .stage-orbit {
    width: 370px;
    height: 440px;
  }
}
@media (max-width: 760px) {
  .download-header {
    height: 76px;
    padding: 0 22px;
    gap: 25px;
  }
  .wordmark {
    font-size: 32px;
  }
  .download-header nav {
    gap: 16px;
    font-size: 12px;
  }
  .web-entry {
    font-size: 11px;
    padding: 9px 11px;
  }
  .mobile-section {
    grid-template-columns: 1fr;
    padding: 45px 24px 52px;
    gap: 42px;
  }
  .hero-copy {
    text-align: center;
  }
  .eyebrow {
    font-size: 10px;
    margin-bottom: 20px;
  }
  .hero-copy h1 {
    font-size: 54px;
    letter-spacing: -2px;
  }
  .intro {
    font-size: 15px;
  }
  .mobile-actions {
    justify-content: center;
    margin-top: 28px;
    gap: 18px;
  }
  .download-button {
    font-size: 13px;
    padding: 13px 17px;
    min-height: 48px;
  }
  .platform-action > small {
    text-align: center;
  }
  .mobile-stage {
    height: 545px;
    max-width: 440px;
    width: 100%;
    margin: auto;
    border-radius: 200px 200px 70px 70px;
  }
  .floating-note {
    right: 0;
    top: 80px;
  }
  .desktop-section {
    padding: 55px 18px 0;
  }
  .section-copy > p:last-child {
    font-size: 14px;
  }
  .desktop-actions {
    gap: 20px;
  }
  .desktop-stage {
    padding: 0 0 25px;
    margin-top: 40px;
  }
  .workspace-body {
    grid-template-columns: 90px 1fr;
    min-height: 260px;
  }
  .workspace-body aside {
    padding: 14px 6px;
    font-size: 7px;
    gap: 3px;
  }
  .workspace-body aside > span {
    padding: 7px 3px;
  }
  .workspace-body aside i {
    margin-right: 3px;
  }
  .mini-logo {
    font-size: 20px;
    margin: 0 6px 10px;
  }
  .workspace-main {
    padding: 20px 14px;
  }
  .workspace-main h3 {
    font-size: 13px;
    margin-bottom: 13px;
  }
  .workspace-stats > div {
    padding: 9px;
  }
  .workspace-stats small {
    font-size: 6px;
  }
  .workspace-stats b {
    font-size: 17px;
  }
  .workspace-stats b span {
    display: block;
    font-size: 6px;
    margin: 3px 0 0;
  }
  .workspace-list {
    font-size: 7px;
    margin-top: 14px;
  }
  .workspace-list > b {
    font-size: 8px;
  }
  .workspace-list > b > span {
    font-size: 6px;
  }
  .workspace-list p {
    padding: 9px 0;
    gap: 5px;
  }
  .workspace-list em {
    font-size: 6px;
  }
  .window-bar {
    font-size: 7px;
    height: 27px;
  }
  .install-section {
    padding: 55px 26px 38px;
  }
  .install-grid {
    grid-template-columns: 1fr;
    gap: 27px;
    margin-top: 26px;
  }
  .install-grid h3 {
    margin: 8px 0;
  }
  .pet-link {
    margin: 0 26px 20px;
    flex-direction: column;
    padding: 24px 0;
    font-size: 12px;
  }
  .checksums {
    margin-top: 28px;
  }
  .preview-caption {
    font-size: 8px;
  }
}
@media (max-width: 380px) {
  .download-header {
    padding: 0 16px;
    gap: 14px;
  }
  .download-header nav {
    gap: 12px;
  }
  .wordmark {
    font-size: 30px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .download-button {
    transition: none;
  }
}
</style>
