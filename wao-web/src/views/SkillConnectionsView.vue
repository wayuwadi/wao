<template>
  <main class="connections-page">
    <section class="connections-hero">
      <div class="hero-icon" aria-hidden="true"><i class="ri-apps-2-line"></i></div>
      <div>
        <span class="eyebrow">规划中 · 尚未开放授权</span>
        <h1>应用 Skill 接入</h1>
        <p>这里介绍外部应用接入规划。目前还不能连接应用账号，无需进行配置。</p>
      </div>
    </section>

    <section class="connection-card" aria-labelledby="connection-empty-title">
      <div class="empty-icon" aria-hidden="true"><i class="ri-link-unlink-m"></i></div>
      <h2 id="connection-empty-title">外部应用接入尚未开放</h2>
      <p>
        微信读书、得到等应用仍在规划中，尚无可用的授权入口。如果你想查找可复用的 AI 工作流包，可以前往下方 Skill 市场。
      </p>
    </section>

    <section class="boundary-card">
      <div>
        <span class="section-kicker">两种 Skill，各有职责</span>
        <h2>应用账号能力与 WAO 工作流包分开管理</h2>
        <p>本页负责外部应用账号能力的接入；经过审核、可下载的 AI 工作流包继续由 Skill 市场管理。</p>
      </div>
      <RouterLink class="market-link" to="/wao/skills">
        浏览 Skill 市场<i class="ri-arrow-right-line" aria-hidden="true"></i>
      </RouterLink>
    </section>

    <section class="requirements" aria-label="后续接入要求">
      <article>
        <i class="ri-shield-keyhole-line" aria-hidden="true"></i>
        <div>
          <h3>安全授权</h3>
          <p>凭据由服务端保护，可撤销且不会进入页面、日志或 Skill 包。</p>
        </div>
      </article>
      <article>
        <i class="ri-pulse-line" aria-hidden="true"></i>
        <div>
          <h3>真实状态</h3>
          <p>连接、失效和重试都来自提供方真实响应，不以静态状态代替。</p>
        </div>
      </article>
      <article>
        <i class="ri-route-line" aria-hidden="true"></i>
        <div>
          <h3>明确能力</h3>
          <p>每项能力标明读写范围、数据去向和失败后的恢复方式。</p>
        </div>
      </article>
    </section>
  </main>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<style scoped>
.connections-page {
  width: min(1040px, calc(100% - 40px));
  margin: 0 auto;
  padding: 42px 0 70px;
  color: var(--wao-text);
}
.connections-hero {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 28px;
}
.hero-icon {
  width: 64px;
  height: 64px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: 20px;
  color: #fff;
  font-size: 28px;
  background: linear-gradient(145deg, var(--wao-primary), #7584e8);
  box-shadow: var(--wao-shadow-pop);
}
.eyebrow,
.section-kicker {
  display: block;
  color: var(--wao-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 1.6px;
  margin-bottom: 8px;
}
h1,
h2,
h3,
p {
  margin-top: 0;
}
h1 {
  margin-bottom: 8px;
  font-size: 30px;
  letter-spacing: -0.5px;
}
.connections-hero p,
.boundary-card p {
  margin-bottom: 0;
  color: var(--wao-text-3);
  line-height: 1.7;
}
.connection-card,
.boundary-card,
.requirements article {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  box-shadow: var(--wao-shadow-card-hover);
}
.connection-card {
  border-radius: 20px;
  padding: 58px 28px;
  text-align: center;
}
.empty-icon {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  margin: 0 auto 18px;
  border-radius: 18px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  font-size: 25px;
}
.connection-card h2 {
  margin-bottom: 10px;
  font-size: 20px;
}
.connection-card p {
  max-width: 650px;
  margin: 0 auto;
  color: var(--wao-text-3);
  line-height: 1.8;
}
.boundary-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 18px;
  padding: 24px 26px;
  border-radius: 18px;
}
.boundary-card h2 {
  margin-bottom: 8px;
  font-size: 18px;
}
.market-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  min-height: 42px;
  padding: 0 16px;
  border-radius: 11px;
  background: var(--wao-primary);
  color: #fff;
  font-weight: 700;
}
.market-link:hover {
  color: #fff;
  text-decoration: none;
  background: var(--wao-primary-hover);
}
.requirements {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-top: 18px;
}
.requirements article {
  display: flex;
  gap: 13px;
  padding: 20px;
  border-radius: 16px;
}
.requirements article > i {
  color: var(--wao-primary);
  font-size: 22px;
}
.requirements h3 {
  margin-bottom: 7px;
  font-size: 15px;
}
.requirements p {
  margin-bottom: 0;
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.65;
}
@media (max-width: 760px) {
  .connections-page {
    width: calc(100% - 24px);
    padding: 26px 0 46px;
  }
  .connections-hero {
    align-items: flex-start;
  }
  .hero-icon {
    width: 52px;
    height: 52px;
    border-radius: 16px;
    font-size: 23px;
  }
  h1 {
    font-size: 24px;
  }
  .connection-card {
    padding: 42px 20px;
  }
  .boundary-card {
    align-items: flex-start;
    flex-direction: column;
    padding: 22px;
  }
  .requirements {
    grid-template-columns: 1fr;
  }
}
</style>
