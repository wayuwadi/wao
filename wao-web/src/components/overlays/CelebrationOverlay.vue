<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const canvas = ref<HTMLCanvasElement | null>(null)

interface Part {
  x: number; y: number; vx: number; vy: number; g: number; c: string; a: number
  dot: boolean; s?: number; w?: number; h?: number; r?: number; vr?: number
}

const COLORS = ['#4358D6', '#8A9BE8', '#E3BE62', '#5CB88A', '#C9CFE8']

function run() {
  const el = canvas.value
  const mode = ui.celebrating
  if (!el || !mode) return
  const dpr = window.devicePixelRatio || 1
  const W = window.innerWidth
  const H = window.innerHeight
  el.width = W * dpr
  el.height = H * dpr
  const ctx = el.getContext('2d')
  if (!ctx) return
  ctx.scale(dpr, dpr)

  const parts: Part[] = []
  if (mode === '柔和光点') {
    for (let i = 0; i < 60; i++)
      parts.push({ x: Math.random() * W, y: H * 0.55 + Math.random() * H * 0.45, vx: (Math.random() - 0.5) * 0.3, vy: -(0.6 + Math.random() * 1.2), g: -0.002, s: 1.5 + Math.random() * 2.5, c: COLORS[i % COLORS.length]!, a: 0.9, dot: true })
  } else {
    for (let i = 0; i < 90; i++) {
      const left = i % 2 === 0
      parts.push({ x: left ? W * 0.12 : W * 0.88, y: H * 0.82, vx: (left ? 1 : -1) * (1.4 + Math.random() * 3.4), vy: -(7 + Math.random() * 5), g: 0.16, w: 4 + Math.random() * 5, h: 6 + Math.random() * 6, r: Math.random() * Math.PI, vr: (Math.random() - 0.5) * 0.25, c: COLORS[i % COLORS.length]!, a: 0.95, dot: false })
    }
  }

  const t0 = performance.now()
  const loop = (t: number) => {
    if (!ui.celebrating || !canvas.value) return
    const dt = t - t0
    ctx.clearRect(0, 0, W, H)
    const fade = dt > 1200 ? Math.max(0, 1 - (dt - 1200) / 600) : 1
    for (const p of parts) {
      p.vy += p.g
      p.x += p.vx
      p.y += p.vy
      ctx.save()
      ctx.globalAlpha = Math.max(0, p.a * fade)
      ctx.translate(p.x, p.y)
      ctx.fillStyle = p.c
      if (p.dot) {
        ctx.beginPath()
        ctx.arc(0, 0, p.s!, 0, 6.3)
        ctx.fill()
      } else {
        p.vx *= 0.992
        p.r! += p.vr!
        ctx.rotate(p.r!)
        ctx.fillRect(-p.w! / 2, -p.h! / 2, p.w!, p.h!)
      }
      ctx.restore()
    }
    if (dt > 1800) {
      ctx.clearRect(0, 0, W, H)
      ui.stopCelebrate()
      return
    }
    requestAnimationFrame(loop)
  }
  requestAnimationFrame(loop)
}

onMounted(run)
</script>

<template>
  <canvas ref="canvas" class="celeb"></canvas>
  <div class="tip">按 Esc 跳过</div>
</template>

<style scoped>
.celeb {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  z-index: 200;
  pointer-events: none;
}
.tip {
  position: fixed;
  bottom: 26px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 201;
  font-size: 12px;
  color: var(--wao-text-weak);
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid var(--wao-line);
  border-radius: 999px;
  padding: 4px 12px;
  animation: waoFade 0.3s ease;
  pointer-events: none;
}
</style>
