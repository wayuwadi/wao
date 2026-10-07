import * as THREE from 'three'
import { COMPANIONS, COMPANION_IDS } from '@/utils/companionIdentity'
import { createCompanionModels } from './companionModels'
import { createCompanionWardrobe, disposeWardrobe, wardrobeNecklines } from './companionWardrobe'
import { plainWeather, type LivingWeather } from './livingWeather'
import { boundCompanion, companionLayout, stepCompanion } from './companionMotion'
import { stepPlayBall, type PlayBall } from './companionPlay'
import { ACTIVITY_SECONDS, activityPose, companionActivities, type CompanionActivity } from './companionActivities'
import { bubblePose } from './companionBubble'
import { createBubbleCollection, randomSurprise, surpriseResponse, type SurpriseStyle } from './companionSurprises'
import { createWorldChoices, createWorldCombos, worldPose, type WorldStory, type WorldChoices } from './companionWorld'

interface Options {
  host: HTMLElement
  canvas: HTMLCanvasElement
  targets: HTMLButtonElement[]
  say: (text: string, x: number, y: number) => void
  failed: () => void
  surprise?: (style: SurpriseStyle) => void
  cancelSurprises?: () => void
  world?: (story: WorldStory) => void
  choices?: WorldChoices
}

/** 一个首页实例拥有一个场景，隐藏、静止和卸载均停止绘制。 */
export function createCompanionScene({ host, canvas, targets, say, failed, surprise, cancelSurprises, world, choices = createWorldChoices() }: Options) {
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
  let weather = plainWeather
  let wardrobes: THREE.Group[] = []
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
  const bubbleGeometry = new THREE.SphereGeometry(1, 12, 8)
  const rimGeometry = new THREE.RingGeometry(0.86, 1, 20)
  const bubbles = pets.map((_, index) => {
    const group = new THREE.Object3D()
    group.name = `companion-bubbles-${index}`
    const material = new THREE.MeshStandardMaterial({ color: [0xb7eeff, 0xffefab, 0xf8c4ee, 0xe3f6b4][index], roughness: 0.18, metalness: 0.12, transparent: true, opacity: 0.75, depthWrite: false })
    const rimMaterial = new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.8, depthWrite: false })
    for (let j = 0; j < 24; j++) {
      const orb = new THREE.Mesh(bubbleGeometry, material)
      const ring = new THREE.Mesh(rimGeometry, rimMaterial)
      ring.position.z = 0.55
      orb.add(ring)
      group.add(orb)
    }
    group.visible = false
    scene.add(group)
    return { group, material, rimMaterial }
  })
  const collection = createBubbleCollection()
  const combos = createWorldCombos()
  let story: { event: WorldStory; start: number } | null = null
  let reverse = false, pendingReverse = 0
  let highFive: { a: number; b: number; start: number } | null = null
  let lastInteraction = performance.now(), idleAt = lastInteraction
  let lastSurprise: SurpriseStyle | undefined
  let lastResponse: SurpriseStyle | undefined
  let response: { style: SurpriseStyle; start: number; source: number } | null = null
  const flights: ({ start: number; x: number; y: number; token: number; counted: boolean } | null)[] = pets.map(() => null)
  function clearFlights() {
    collection.reset()
    response = null
    story = null; reverse = false; pendingReverse = 0; highFive = null; combos.reset()
    cancelSurprises?.()
    pets.forEach((p, i) => {
      if (flights[i]) { p.x = p.homeX; p.y = p.homeY; p.vx = p.vy = 0 }
      flights[i] = null
      p.group.visible = true
      p.group.scale.setScalar(p.size)
      bubbles[i]!.group.visible = false
    })
  }
  function clearPlay() { ball = null; toy.visible = false; currentActivity = null }
  function resetBlinks() {
    lastInteraction = performance.now(); idleAt = lastInteraction - 60000
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
    started: number
    originX: number
    originY: number
    lastDirection: number
    reversals: number
    tickling: boolean
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
    clearFlights()
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
      if (weather.clothes !== 'plain') p.homeY += p.size * 0.17
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
      Math.max(12, Math.min(width - Math.min(220, width - 24) - 12, p.x + width / 2 - 70)),
      Math.max(8, height / 2 - p.y - p.size * 1.55),
    )
  }
  function point(e: MouseEvent) {
    const r = host.getBoundingClientRect()
    return { x: e.clientX - r.left - width / 2, y: height / 2 - (e.clientY - r.top) }
  }
  function play(x = 0, y = height / 2 - 24) {
    if (resting() || !runnable() || !width || !height) return false
    clearFlights()
    lastInteraction = performance.now()
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
    // 留白点击交由隐藏世界；集体活动从随机事件入口触发。
    pointer = { x: point(e).x, active: true }
  }) as EventListener)
  listen(document, 'keydown', ((e: KeyboardEvent) => {
    lastInteraction = performance.now()
    if (e.key === 'Escape') { clearPlay(); clearFlights(); wake() }
  }) as EventListener)
  listen(document, 'pointermove', (() => { lastInteraction = performance.now() }) as EventListener)
  listen(document, 'pointerdown', (() => { lastInteraction = performance.now() }) as EventListener)
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
      if (d.tickling) {
        suppressClickUntil = performance.now() + 500
      } else if (d.distance >= 8) {
        suppressClickUntil = performance.now() + 500
        announce(d.index, '接住啦。')
        if (!resting()) {
          const neighbor = pets.reduce((best, candidate, i) => i !== d.index && Math.abs(candidate.x - p.x) < Math.abs(pets[best]!.x - p.x) ? i : best, d.index === 0 ? 1 : 0)
          if (Math.hypot(p.x - pets[neighbor]!.x, p.y - pets[neighbor]!.y) < p.size * 2.1) {
            beginStory({ kind: 'visit', source: d.index, target: neighbor, tier: choices.tier(performance.now()) })
          } else respond(d.index)
        }
      }
    }
    wake()
  }
  targets.forEach((el, index) => {
    listen(el, 'pointerdown', ((e: PointerEvent) => {
      if (drag || flights[index] || e.button !== 0) return
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
        started: performance.now(), originX: v.x, originY: v.y, lastDirection: 0, reversals: 0, tickling: false,
      }
      lastInteraction = performance.now()
      story = null
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
      const horizontal = v.x - drag.x, direction = Math.abs(horizontal) >= 2 ? Math.sign(horizontal) : 0
      if (direction && drag.lastDirection && direction !== drag.lastDirection) drag.reversals++
      if (direction) drag.lastDirection = direction
      // 按住至少半秒后小幅来回移动进入挠痒；明显移动始终保持拖动。
      if (!resting() && now - drag.started >= 500 && Math.hypot(v.x - drag.originX, v.y - drag.originY) <= 20
        && drag.reversals >= 2 && !p.drag) {
        if (!drag.tickling) { drag.tickling = true; beginStory({ kind: 'tickle', source: drag.index, target: choices.partner(drag.index), tier: choices.tier(now) }) }
        drag.distance += moved
        drag.x = v.x; drag.y = v.y; drag.time = now
        wake(); return
      }
      if (drag.tickling) {
        if (Math.hypot(v.x - drag.originX, v.y - drag.originY) <= 20) return
        drag.tickling = false; story = null
      }
      if (!p.drag && now - drag.started >= 500 && moved <= 20 && drag.distance < 8 && Math.hypot(v.x - drag.originX, v.y - drag.originY) <= 20) {
        drag.x = v.x; drag.y = v.y; drag.time = now; return
      }
      if (!p.drag && moved < 8) return
      if (!p.drag) { collection.reset(); cancelSurprises?.() }
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
    // 捕获阶段阻止拖动结束后的合成点击；普通轻点由 Vue 触发升空。
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
      if (story?.event.kind === 'imitate' && story.event.target === index) {
        story = { event: { ...story.event, kind: 'watch' }, start: performance.now() }
        announce(index, '咳，我只是伸个懒腰。')
      }
      wake()
    }) as EventListener)
    listen(el, 'pointerleave', (() => {
      pets[index]!.hover = false
      strokes[index]!.seen = false
      wake()
    }) as EventListener)
    listen(el, 'focus', (() => { pets[index]!.hover = true; wake() }) as EventListener)
    listen(el, 'blur', (() => { pets[index]!.hover = false; wake() }) as EventListener)
  })
  listen(host.parentElement!, 'pointermove', ((e: PointerEvent) => {
    pointer = { x: point(e).x, active: true }
  }) as EventListener)
  listen(host.parentElement!, 'pointerleave', (() => {
    pointer.active = false
  }) as EventListener)
  function stop() {
    clearPlay()
    clearFlights()
    cancelAnimationFrame(raf)
    raf = 0
    last = 0
  }
  function wake() {
    if (runnable() && !raf) raf = requestAnimationFrame(frame)
  }
  function respond(source: number) {
    lastResponse = randomSurprise(lastResponse)
    response = { style: lastResponse, start: performance.now(), source }
  }
  function beginStory(event: WorldStory, silent = false) {
    if (resting() || !runnable() || currentActivity) return false
    story = { event, start: performance.now() }
    lastInteraction = performance.now()
    const names = COMPANION_IDS.map(id => COMPANIONS[id].name)
    const text: Partial<Record<WorldStory['kind'], string>> = {
      catch: [`${names[event.source]}把泡泡染成了颜色。`, `${names[1]}：瞄准，接住。`, `${names[2]}：这个归我啦。`, `${names[3]}抢先跳起来了。`][event.source],
      miss: event.tier === 'rare' ? '大家都扑空啦。' : '咦，泡泡去哪了？',
      wind: event.tier === 'rare' ? '帽子歪了，帮你扶一下。' : '借你一阵轻轻的风。',
      orbit: '你画的小小星系。', wave: '收到你的节拍。',
      visit: event.tier === 'rare' ? '碰一下，大家一起弹回来。' : '挪一点位置，靠一下。',
      tickle: '有点痒，邻居也笑了。', imitate: '我没学你……真的。', reverse: '倒着来，也整整齐齐。',
      pass: event.tier === 'rare' ? '传到一半，大家一起扑空。' : '把这点惊喜，传给下一位。',
    }
    if (!silent && text[event.kind]) announce(event.source, text[event.kind]!)
    world?.(event)
    wake()
    return true
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
    if (response && time - response.start > 1250) response = null
    if (story && time - story.start >= (story.event.kind === 'pass' ? 6500 : 3400)) story = null
    if (pendingReverse && time >= pendingReverse) { pendingReverse = 0; beginStory({ kind: 'reverse', source: 3, tier: 'common' }) }
    if (!resting() && !currentActivity && !drag && !flights.some(Boolean) && !story && time - lastInteraction > 22000 && time - idleAt > 60000) {
      idleAt = time
      const source = choices.partner()
      beginStory({ kind: source === 3 ? 'peek' : 'yawn', source, target: choices.partner(source), tier: choices.tier(time) }, true)
    }
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
      const reply = response && !resting() && !p.drag && !flights[i]
        ? surpriseResponse(response.style, (time - response.start) / 1000, i, response.source) : null
      const acting = story && !resting() && !p.drag && !flights[i] && !currentActivity
        ? worldPose(story.event, (time - story.start) / 1000, i) : null
      const wave = resting() ? 0 : Math.sin(t * 1.65 + p.phase) * 1.5
      const action = resting() ? 0 : Math.sin(p.action * 13) * p.action
      p.group.position.set(
        Math.max(-width / 2 + p.size * 1.1, Math.min(width / 2 - p.size * 1.1, p.x + (pose?.x ?? 0) * p.size + (acting?.x ?? 0))),
        Math.min(height / 2 - p.size * 1.4, p.y + wave + (pose?.y ?? 0) * p.size + (reply?.y ?? 0) + (acting?.y ?? 0) + (!resting() && p.hover && !p.drag ? 4 : 0)), p.drag ? 180 : 0)
      const gaze = resting()
        ? 0
        : Math.max(-0.23, Math.min(0.23, (ball ? ball.x - p.x : pointer.active ? pointer.x - p.x : 0) / 950))
      p.body.rotation.y = gaze + (pose?.turn ?? 0) + (acting?.turn ?? 0)
      let tilt = resting()
        ? 0
        : p.drag
          ? Math.max(-0.32, Math.min(0.32, -p.vx * 0.00045))
          : p.hover
            ? 0.055
            : Math.sin(t * 0.7 + p.phase) * 0.015
      if (drag && !p.drag && !resting()) tilt += Math.sign(pets[drag.index]!.x - p.x) * 0.055
      p.body.rotation.z = tilt + action * 0.06 + (pose?.tilt ?? 0) + (reply?.tilt ?? 0) + (acting?.tilt ?? 0)
      const hat = p.body.getObjectByName('companion-hat')
      if (hat) hat.rotation.z = acting?.hat ?? (!resting() && weather.windy ? Math.sin(t * 2.3) * 0.045 : 0)
      const scarf = p.body.getObjectByName('companion-scarf-tail')
      if (scarf) scarf.rotation.z = !resting() && weather.windy ? Math.sin(t * 3 + i) * 0.16 : 0
      const pressed = drag?.index === i && !p.drag && !drag.tickling
      const targetStretch = resting() ? 1 : pressed ? 0.85 : p.drag ? 1.085 : (pose?.stretch ?? 1) + action * 0.025 + (reply?.stretch ?? 1) - 1 + (acting?.stretch ?? 1) - 1
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
      const lid = (quiet
        ? 0.2
        : resting()
          ? 1
          : p.blink > 0
            ? Math.max(0.12, Math.abs(p.blink - 0.1) / 0.1)
            : 1) * (acting?.lid ?? 1)
      p.eyes.forEach((eye) => {
        if (i === 0) eye.scale.y = 0.12 * lid
        if (i === 1) eye.scale.y = lid
        if (i === 3) eye.scale.y = 0.255 * (0.4 + 0.6 * lid)
      })
      p.pupils.forEach((eye, j) => {
        eye.position.x = (j === 0 ? -0.5 : 0.5) + gaze * 0.18
        eye.scale.y = 0.17 * lid
      })
      const flight = flights[i]
      if (flight) {
        const motion = bubblePose((time - flight.start) / 1000)
        if (motion.returning && !flight.counted) {
          flight.counted = true
          const collected = collection.complete(i, flight.token, time)
          if (collected) {
            lastSurprise = randomSurprise(lastSurprise)
            surprise?.(lastSurprise)
            if (reverse) { pendingReverse = time + 3300; reverse = false }
          }
        }
        const top = height / 2 - p.size * 1.65
        if (motion.done) {
          flights[i] = null
          p.x = p.homeX; p.y = p.homeY; p.vx = p.vy = 0
          p.group.position.set(p.homeX, p.homeY, 0)
          p.group.scale.setScalar(p.size)
          p.group.visible = true
          bubbles[i]!.group.visible = false
        } else {
          p.group.position.set(motion.returning ? p.homeX : flight.x + Math.sin(motion.rise * Math.PI * 2) * p.size * 0.12,
            motion.returning ? p.homeY : flight.y + (top - flight.y) * motion.rise, 100)
          p.group.scale.setScalar(p.size * motion.scale)
          if (highFive && (highFive.a === i || highFive.b === i) && !motion.returning) {
            const other = flights[highFive.a === i ? highFive.b : highFive.a]
            const seconds = (time - highFive.start) / 1000
            if (other && seconds > 0.65 && seconds < 2.3) {
              const blend = Math.sin((seconds - 0.65) / 1.65 * Math.PI)
              p.group.position.x += ((flight.x + other.x) / 2 - p.group.position.x) * blend
              p.group.position.y += (top - p.group.position.y) * blend * 0.55
            }
          }
          p.group.visible = motion.scale > 0.001
          const foam = bubbles[i]!
          foam.group.visible = motion.bubbles
          foam.group.position.set(flight.x, top, 120)
          foam.material.opacity = (1 - motion.burst) * 0.72
          foam.rimMaterial.opacity = (1 - motion.burst) * 0.85
          foam.group.children.forEach((orb, j) => {
            const angle = j * 2.39996, spread = p.size * (0.15 + Math.sqrt(j / 24) * (0.55 + motion.burst * 0.8))
            orb.position.set(Math.cos(angle) * spread, Math.sin(angle) * spread - motion.burst ** 2 * p.size * 0.35, j * 0.1)
            orb.scale.setScalar(p.size * (0.055 + (j % 5) * 0.026) * (1 + motion.burst * 0.45))
          })
        }
      }
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
    clearFlights()
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
    idle: () => runnable() && !resting() && !drag && !currentActivity && !story && !response && !flights.some(Boolean) && !pendingReverse,
    setWeather(value: LivingWeather) {
      const changed = weather.clothes !== value.clothes || weather.scarf !== value.scarf
      weather = value
      if (changed) {
        wardrobes.forEach(disposeWardrobe)
        wardrobes = pets.map(p => createCompanionWardrobe(p.body, p.i, value))
        pets.forEach((p, i) => {
          if (p.brush?.clothingCut) p.brush.clothingCut.value = weather.clothes === 'plain' ? -10 : wardrobeNecklines[i]!
          const homeY = companionLayout(width, height, i).homeY + (weather.clothes !== 'plain' ? p.size * 0.17 : 0)
          p.y += homeY - p.homeY
          p.homeY = homeY
        })
      }
      wake()
    },
    settle() { reset(); wake() },
    story: beginStory,
    anchors() {
      const bounds = host.getBoundingClientRect()
      return pets.map((p, i) => ({ x: bounds.left + width / 2 + p.group.position.x, y: bounds.top + height / 2 - p.group.position.y - p.size * 0.7, size: p.size,
        available: runnable() && !resting() && !currentActivity && !p.drag && !flights[i] }))
    },
    pop(index: number) {
      if (resting() || !runnable() || !pets[index]) return false
      if (performance.now() - lastInteraction > 15000) reverse = false
      lastInteraction = performance.now()
      const combo = combos.pop(index, lastInteraction)
      if (combo.imitate) beginStory({ kind: 'imitate', source: index, target: choices.partner(index), tier: choices.tier(lastInteraction) })
      if (combo.reverse) reverse = true
      if (flights[index]) return true
      clearPlay()
      const p = pets[index]!
      const start = performance.now()
      flights[index] = { start, x: p.group.position.x, y: p.group.position.y, token: collection.start(index, start), counted: false }
      if (combo.pair !== undefined && flights[combo.pair]) highFive = { a: combo.pair, b: index, start }
      respond(index)
      if (weather.clothes !== 'plain') {
        const clothes = { light: '轻薄上衣', jacket: '薄外套', winter: '厚外套', raincoat: '雨衣' }[weather.clothes]
        announce(index, `今天穿${clothes}${weather.scarf ? '，围巾也系好了' : '，舒服一点'}。`)
      }
      p.action = 0
      p.vx = p.vy = 0
      wake()
      return true
    },
    activity(kind: CompanionActivity) {
      if (kind === 'ball') return play()
      if (resting() || !runnable()) return false
      clearPlay()
      clearFlights()
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
        lastInteraction = idleAt = performance.now()
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
