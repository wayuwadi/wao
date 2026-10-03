import * as THREE from 'three'
import { createCompanionModels } from './companionModels'
import { boundCompanion, companionLayout, stepCompanion } from './companionMotion'
import { stepPlayBall, type PlayBall } from './companionPlay'
import { ACTIVITY_SECONDS, activityPose, companionActivities, type CompanionActivity } from './companionActivities'

interface Options {
  host: HTMLElement
  canvas: HTMLCanvasElement
  targets: HTMLButtonElement[]
  say: (text: string, x: number, y: number) => void
  failed: () => void
}

/** 一个首页实例拥有一个场景，隐藏、静止和卸载均停止绘制。 */
export function createCompanionScene({ host, canvas, targets, say, failed }: Options) {
  const renderer = new THREE.WebGLRenderer({
    canvas,
    antialias: true,
    alpha: true,
    powerPreference: 'low-power',
  })
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5))
  renderer.setClearColor(0xffffff, 0)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 0.98
  const scene = new THREE.Scene()
  const camera = new THREE.OrthographicCamera(-800, 800, 400, -400, 1, 3000)
  camera.position.z = 1300
  scene.add(new THREE.HemisphereLight(0xffffff, 0x9b81b5, 1.6))
  const key = new THREE.DirectionalLight(0xffffff, 2.1)
  key.position.set(-3, 5, 7)
  scene.add(key)
  const rim = new THREE.DirectionalLight(0xe2ebff, 0.8)
  rim.position.set(4, 2, -2)
  scene.add(rim)
  const pets = createCompanionModels()
  pets.forEach((p) => scene.add(p.group))
  const toy = new THREE.Mesh(new THREE.SphereGeometry(10, 20, 16), new THREE.MeshStandardMaterial({ color: 0xffbb43, roughness: 0.55, transparent: true }))
  toy.name = 'companion-play-ball'
  toy.visible = false
  scene.add(toy)
  let ball: PlayBall | null = null
  let ballStartedAt = 0
  let currentActivity: { kind: CompanionActivity; start: number } | null = null
  let suppressClickUntil = 0
  const softness = pets.map(() => ({ value: 1, velocity: 0 }))
  const strokes = pets.map(() => ({ x: 0, y: 0, seen: false }))
  function clearPlay() { ball = null; toy.visible = false; currentActivity = null }
  function resetBlinks() {
    pets.forEach((p, i) => {
      p.blink = 0
      p.blinkAt = performance.now() / 1000 + 2.5 + i * 0.7
    })
  }
  resetBlinks()
  const media = matchMedia('(prefers-reduced-motion: reduce)')
  let width = 0,
    height = 0,
    disposed = false,
    lost = false,
    active = true,
    visible = true
  let quiet = false,
    raf = 0,
    last = 0,
    paintedAt = 0,
    reduced = media.matches
  let pointer = { x: 0, active: false }
  let drag: {
    index: number
    id: number
    x: number
    y: number
    offsetX: number
    offsetY: number
    time: number
    distance: number
  } | null = null
  const cleanup: (() => void)[] = []
  function listen<T extends EventTarget>(node: T, name: string, fn: EventListener) {
    node.addEventListener(name, fn)
    cleanup.push(() => node.removeEventListener(name, fn))
  }
  const resting = () => quiet || reduced
  const runnable = () => !disposed && !lost && active && visible && !document.hidden
  function syncTarget(index: number) {
    const p = pets[index]!,
      el = targets[index]!
    const left = p.group.position.x + width / 2 - p.size * 1.09
    const top = height / 2 - p.group.position.y - p.size * 1.3
    // 底边露出的伙伴只保留可见热区，避免 focus/scrollIntoView 滚动隐藏容器。
    const x = Math.max(0, left), y = Math.max(0, top)
    el.style.width = `${Math.max(0, Math.min(width, left + p.size * 2.18) - x)}px`
    el.style.height = `${Math.max(0, Math.min(height, top + p.size * 2.55) - y)}px`
    el.style.transform = `translate(${x}px,${y}px)`
    el.style.zIndex = p.drag ? '3' : '1'
  }
  function layout() {
    clearPlay()
    const initial = !width
    width = host.clientWidth
    height = host.clientHeight
    if (!width || !height) return
    renderer.setSize(width, height, false)
    camera.left = -width / 2
    camera.right = width / 2
    camera.top = height / 2
    camera.bottom = -height / 2
    camera.updateProjectionMatrix()
    pets.forEach((p, i) => {
      Object.assign(p, companionLayout(width, height, i))
      // Resize also returns an in-flight character to its visible home.
      if (initial || !p.drag) {
        p.x = p.homeX
        p.y = p.homeY
        p.vx = p.vy = 0
      }
      p.group.scale.setScalar(p.size)
      p.group.position.set(p.x, p.y, 0)
      syncTarget(i)
    })
    wake()
  }
  function announce(index: number, text: string) {
    const p = pets[index]!
    say(
      text,
      Math.max(12, Math.min(width - 190, p.x + width / 2 - 70)),
      Math.max(8, height / 2 - p.y - p.size * 1.55),
    )
  }
  function point(e: MouseEvent) {
    const r = host.getBoundingClientRect()
    return { x: e.clientX - r.left - width / 2, y: height / 2 - (e.clientY - r.top) }
  }
  function play(x = 0, y = height / 2 - 24) {
    if (resting() || !runnable() || !width || !height) return false
    currentActivity = { kind: 'ball', start: performance.now() }
    ball = { x: Math.max(-width / 2 + 11, Math.min(width / 2 - 11, x)), y: Math.max(-height / 2 + 11, Math.min(height / 2 - 11, y)), vx: x > 0 ? -150 : 150, vy: 110, age: 0 }
    ballStartedAt = performance.now()
    toy.visible = true
    toy.material.opacity = 1
    announce(2, '大家一起接！十五秒后歇一歇。')
    wake()
    return true
  }
  listen(host, 'click', ((e: MouseEvent) => {
    if (e.target !== host || e.button !== 0 || window.getSelection()?.toString()) return
    const v = point(e)
    play(v.x, v.y)
  }) as EventListener)
  listen(document, 'keydown', ((e: KeyboardEvent) => {
    if (e.key === 'Escape' && currentActivity) { clearPlay(); wake() }
  }) as EventListener)
  function release(cancelled = false) {
    if (!drag) return
    const d = drag,
      p = pets[d.index]!,
      el = targets[d.index]!
    drag = null
    p.drag = false
    el.classList.remove('dragging')
    if (el.hasPointerCapture(d.id)) el.releasePointerCapture(d.id)
    if (resting()) {
      p.x = p.homeX
      p.y = p.homeY
      p.vx = p.vy = 0
    } else {
      p.vx = Math.max(-240, Math.min(240, p.vx * 0.32))
      p.vy = Math.max(-240, Math.min(240, p.vy * 0.32))
      p.action = 0.65
    }
    if (!cancelled) {
      if (d.distance >= 8) { suppressClickUntil = performance.now() + 500; announce(d.index, '接住啦。') }
    }
    wake()
  }
  targets.forEach((el, index) => {
    listen(el, 'pointerdown', ((e: PointerEvent) => {
      if (drag || e.button !== 0) return
      const p = pets[index]!,
        v = point(e)
      if (currentActivity) {
        p.x = p.group.position.x
        p.y = p.group.position.y
        clearPlay()
      }
      el.setPointerCapture(e.pointerId)
      el.focus({ preventScroll: true })
      el.classList.add('dragging')
      // 先轻轻压下，超过移动阈值后才进入拖动。
      drag = {
        index,
        id: e.pointerId,
        x: v.x,
        y: v.y,
        offsetX: v.x - p.x,
        offsetY: v.y - p.y,
        time: performance.now(),
        distance: 0,
      }
      e.preventDefault()
      wake()
    }) as EventListener)
    listen(el, 'pointermove', ((e: PointerEvent) => {
      const pet = pets[index]!, stroke = strokes[index]!, cursor = point(e)
      if (!resting() && !drag && stroke.seen && pet.brush) {
        pet.brush.point.value.set((cursor.x - pet.x) / pet.size, (cursor.y - pet.y) / pet.size)
        pet.brush.direction.value.set(cursor.x - stroke.x, cursor.y - stroke.y).normalize()
        pet.brush.strength.value = Math.min(1, Math.hypot(cursor.x - stroke.x, cursor.y - stroke.y) / 12)
      }
      Object.assign(stroke, { x: cursor.x, y: cursor.y, seen: true })
      if (!drag || drag.id !== e.pointerId) return
      const v = point(e),
        p = pets[drag.index]!,
        now = performance.now(),
        dt = Math.max(0.016, (now - drag.time) / 1000)
      const moved = Math.hypot(v.x - drag.x, v.y - drag.y)
      if (!p.drag && moved < 8) return
      drag.distance += moved
      p.drag = true
      p.vx = (v.x - drag.x) / dt
      p.vy = (v.y - drag.y) / dt
      Object.assign(
        p,
        boundCompanion(v.x - drag.offsetX, v.y - drag.offsetY, width, height, p.size),
      )
      drag.x = v.x
      drag.y = v.y
      drag.time = now
      wake()
    }) as EventListener)
    listen(el, 'pointerup', ((e: PointerEvent) => {
      if (drag?.id === e.pointerId) release()
    }) as EventListener)
    listen(el, 'pointercancel', (() => release(true)) as EventListener)
    listen(el, 'lostpointercapture', (() => release(true)) as EventListener)
    // 捕获阶段阻止拖动结束后的合成点击；普通轻点由 Vue 打开活动菜单。
    const blockDragClick = (e: MouseEvent) => { if (e.detail > 0 && performance.now() < suppressClickUntil) { e.preventDefault(); e.stopImmediatePropagation() } }
    el.addEventListener('click', blockDragClick, true)
    cleanup.push(() => el.removeEventListener('click', blockDragClick, true))
    listen(el, 'keydown', ((e: KeyboardEvent) => {
      if (e.key === 'Escape' && drag) {
        e.preventDefault()
        release(true)
      }
    }) as EventListener)
    listen(el, 'pointerenter', (() => {
      pets[index]!.hover = true
      wake()
    }) as EventListener)
    listen(el, 'pointerleave', (() => {
      pets[index]!.hover = false
      strokes[index]!.seen = false
      wake()
    }) as EventListener)
  })
  listen(host.parentElement!, 'pointermove', ((e: PointerEvent) => {
    pointer = { x: point(e).x, active: true }
  }) as EventListener)
  listen(host.parentElement!, 'pointerleave', (() => {
    pointer.active = false
  }) as EventListener)
  function stop() {
    clearPlay()
    cancelAnimationFrame(raf)
    raf = 0
    last = 0
  }
  function wake() {
    if (runnable() && !raf) raf = requestAnimationFrame(frame)
  }
  function frame(time: number) {
    raf = 0
    if (!runnable()) return
    // 空闲最多 30fps；拖动才提高刷新频率。
    if (!drag && time - paintedAt < 32 && !resting()) {
      wake()
      return
    }
    const dt = Math.min(0.034, (time - last) / 1000 || 0.016),
      t = time / 1000
    last = paintedAt = time
    const elapsed = currentActivity ? (time - currentActivity.start) / 1000 : 0
    if (elapsed >= ACTIVITY_SECONDS || resting()) clearPlay()
    const poses = pets.map((_, i) => currentActivity ? activityPose(currentActivity.kind, elapsed, i) : null)
    if (ball) {
      ball.age = Math.max(ball.age, (time - ballStartedAt) / 1000)
      if (!stepPlayBall(ball, dt, width, height)) clearPlay()
      else {
        if (elapsed > 0.9) {
          // 先接住用户抛出的球，再按站位接力，四个伙伴都参与。
          const passes = [0, 1, 2, 3, 2, 1, 0], leg = (elapsed - 0.9) / 1.4
          const segment = Math.floor(leg) % 6, u = leg % 1
          const a = pets[passes[segment]!]!, b = pets[passes[segment + 1]!]!
          const pa = poses[passes[segment]!]!, pb = poses[passes[segment + 1]!]!
          const blend = Math.min(1, (elapsed - 0.9) / 0.35)
          const x = (a.x + pa.x * a.size) * (1 - u) + (b.x + pb.x * b.size) * u
          const y = (a.y + (pa.y + 0.8) * a.size) * (1 - u) + (b.y + (pb.y + 0.8) * b.size) * u + Math.sin(u * Math.PI) * a.size
          ball.x += (x - ball.x) * blend
          ball.y += (y - ball.y) * blend
        }
        toy.position.set(Math.max(-width / 2 + 11, Math.min(width / 2 - 11, ball.x)), Math.min(height / 2 - 11, ball.y), 150)
        toy.material.opacity = Math.min(1, ACTIVITY_SECONDS - ball.age)
      }
    }
    pets.forEach((p, i) => {
      if (!p.drag && !resting()) stepCompanion(p, dt)
      p.action = Math.max(0, p.action - dt)
      const pose = poses[i]
      const wave = resting() ? 0 : Math.sin(t * 1.65 + p.phase) * 1.5
      const action = resting() ? 0 : Math.sin(p.action * 13) * p.action
      p.group.position.set(
        Math.max(-width / 2 + p.size * 1.1, Math.min(width / 2 - p.size * 1.1, p.x + (pose?.x ?? 0) * p.size)),
        Math.min(height / 2 - p.size * 1.4, p.y + wave + (pose?.y ?? 0) * p.size), p.drag ? 180 : 0)
      const gaze = resting()
        ? 0
        : Math.max(-0.23, Math.min(0.23, (ball ? ball.x - p.x : pointer.active ? pointer.x - p.x : 0) / 950))
      p.body.rotation.y = gaze + (pose?.turn ?? 0)
      let tilt = resting()
        ? 0
        : p.drag
          ? Math.max(-0.32, Math.min(0.32, -p.vx * 0.00045))
          : p.hover
            ? 0.055
            : Math.sin(t * 0.7 + p.phase) * 0.015
      if (drag && !p.drag && !resting()) tilt += Math.sign(pets[drag.index]!.x - p.x) * 0.055
      p.body.rotation.z = tilt + action * 0.06 + (pose?.tilt ?? 0)
      const pressed = drag?.index === i && !p.drag
      const targetStretch = resting() ? 1 : pressed ? 0.85 : p.drag ? 1.085 : (pose?.stretch ?? 1) + action * 0.025
      const soft = softness[i]!
      if (resting()) { soft.value = 1; soft.velocity = 0 }
      else {
        soft.velocity += ((targetStretch - soft.value) * 180 - soft.velocity * 15) * dt
        soft.value += soft.velocity * dt
      }
      p.body.scale.set(1 / Math.sqrt(soft.value), soft.value, 1)
      if (p.brush) p.brush.strength.value = resting() ? 0 : Math.max(0, p.brush.strength.value - dt * 1.8)
      p.shadow.visible = false
      if (t > p.blinkAt && !p.drag) {
        p.blink = 0.2
        p.blinkAt = t + 3.5 + (i + 1) * 0.8
      }
      p.blink = Math.max(0, p.blink - dt)
      const lid = quiet
        ? 0.2
        : resting()
          ? 1
          : p.blink > 0
            ? Math.max(0.12, Math.abs(p.blink - 0.1) / 0.1)
            : 1
      p.eyes.forEach((eye) => {
        if (i === 0) eye.scale.y = 0.12 * lid
        if (i === 3) eye.scale.y = 0.255 * (0.4 + 0.6 * lid)
      })
      p.pupils.forEach((eye, j) => {
        eye.position.x = (j === 0 ? -0.5 : 0.5) + gaze * 0.18
        eye.scale.y = 0.17 * lid
      })
      syncTarget(i)
    })
    renderer.render(scene, camera)
    if (!resting() || drag) wake()
  }
  const observer = new ResizeObserver(layout)
  observer.observe(host)
  const intersection = new IntersectionObserver((entries) => {
    visible = !!entries[0]?.isIntersecting
    if (visible) wake()
    else {
      release(true)
      stop()
    }
  })
  intersection.observe(host)
  listen(document, 'visibilitychange', (() => {
    if (document.hidden) {
      release(true)
      stop()
    } else {
      resetBlinks()
      wake()
    }
  }) as EventListener)
  const changeMotion = () => {
    reduced = media.matches
    reset()
    wake()
  }
  media.addEventListener('change', changeMotion)
  cleanup.push(() => media.removeEventListener('change', changeMotion))
  listen(canvas, 'webglcontextlost', ((e: Event) => {
    e.preventDefault()
    lost = true
    stop()
    failed()
  }) as EventListener)
  function reset() {
    clearPlay()
    release(true)
    pets.forEach((p) => {
      p.x = p.homeX
      p.y = p.homeY
      p.vx = p.vy = 0
      p.body.rotation.set(0, 0, 0)
    })
  }
  layout()
  return {
    play,
    activity(kind: CompanionActivity) {
      if (kind === 'ball') return play()
      if (resting() || !runnable()) return false
      clearPlay()
      currentActivity = { kind, start: performance.now() }
      announce(2, companionActivities.find(a => a.id === kind)!.greeting)
      wake()
      return true
    },
    setQuiet(value: boolean) {
      quiet = value
      reset()
      wake()
    },
    setActive(value: boolean) {
      active = value
      if (value) {
        resetBlinks()
        layout()
        wake()
      } else {
        release(true)
        stop()
      }
    },
    dispose() {
      disposed = true
      release(true)
      stop()
      observer.disconnect()
      intersection.disconnect()
      cleanup.forEach((fn) => fn())
      const materials = new Set<THREE.Material>(),
        geometries = new Set<THREE.BufferGeometry>()
      scene.traverse((o) => {
        const m = o as THREE.Mesh
        if (m.geometry) geometries.add(m.geometry)
        if (m.material)
          (Array.isArray(m.material) ? m.material : [m.material]).forEach((v) => materials.add(v))
      })
      geometries.forEach((g) => g.dispose())
      materials.forEach((m) => m.dispose())
      renderer.dispose()
      renderer.forceContextLoss()
    },
  }
}
