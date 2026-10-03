<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { createCompanionModels } from '@/components/home/companionModels'
import type { CompanionKind } from '@/composables/quietCompanions'

const props = defineProps<{ kind: CompanionKind }>()
const emit = defineEmits<{ ready: []; failed: [] }>()
const canvas = ref<HTMLCanvasElement>()
let renderer: THREE.WebGLRenderer | undefined
let frame = 0
let scene: THREE.Scene | undefined
let motion: MediaQueryList | undefined
function stop() { cancelAnimationFrame(frame) }
function lost(event: Event) { event.preventDefault(); stop(); emit('failed') }
onMounted(() => {
  try {
    renderer = new THREE.WebGLRenderer({ canvas: canvas.value, alpha: true, antialias: true, powerPreference: 'low-power' })
    renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5))
    renderer.setSize(72, 72, false)
    renderer.outputColorSpace = THREE.SRGBColorSpace
    renderer.toneMapping = THREE.ACESFilmicToneMapping
    scene = new THREE.Scene()
    const camera = new THREE.OrthographicCamera(-1.5, 1.5, 1.65, -1.35, .1, 20)
    camera.position.z = 8
    scene.add(new THREE.HemisphereLight(0xffffff, 0xada6cf, 1.6))
    const light = new THREE.DirectionalLight(0xffffff, 2.1)
    light.position.set(-3, 5, 7); scene.add(light)
    const selected = { note: 0, task: 2, empty: 1 }[props.kind]
    const models = createCompanionModels(selected)
    models.forEach(p => { p.shadow.visible = false; scene!.add(p.group) })
    const pet = models[0]!
    motion = matchMedia('(prefers-reduced-motion: reduce)')
    motion.addEventListener('change', stop)
    canvas.value?.addEventListener('webglcontextlost', lost)
    const start = performance.now()
    function draw(now: number) {
      const t = (now - start) / 1000
      if (!motion?.matches) {
        pet.body.rotation.z = Math.sin(t * 5) * .06 * Math.max(0, 1 - t / 1.6)
        pet.group.position.y = props.kind === 'task' ? Math.sin(Math.min(t * 3, Math.PI)) * .22 : 0
      }
      renderer?.render(scene!, camera)
      if (t < 1.6 && !motion?.matches) frame = requestAnimationFrame(draw)
    }
    draw(start)
    emit('ready')
  } catch { stop(); emit('failed') }
})
onBeforeUnmount(() => {
  stop()
  motion?.removeEventListener('change', stop)
  canvas.value?.removeEventListener('webglcontextlost', lost)
  const geometries = new Set<THREE.BufferGeometry>(), materials = new Set<THREE.Material>()
  scene?.traverse(obj => {
    const mesh = obj as THREE.Mesh
    if (mesh.geometry) geometries.add(mesh.geometry)
    if (mesh.material) (Array.isArray(mesh.material) ? mesh.material : [mesh.material]).forEach(m => materials.add(m))
  })
  geometries.forEach(g => g.dispose()); materials.forEach(m => m.dispose())
  renderer?.dispose(); renderer?.forceContextLoss()
})
</script>
<template><canvas ref="canvas" width="72" height="72" /></template>
